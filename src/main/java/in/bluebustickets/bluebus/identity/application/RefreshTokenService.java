package in.bluebustickets.bluebus.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.JwtConfiguration.IssuedAccessToken;
import in.bluebustickets.bluebus.foundation.security.JwtConfiguration.JwtTokenService;
import in.bluebustickets.bluebus.identity.api.dto.LoginResponse;
import in.bluebustickets.bluebus.identity.application.RefreshTokenHasher.GeneratedRefreshToken;
import in.bluebustickets.bluebus.identity.domain.RefreshToken;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.RefreshTokenRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh-token issuance, rotation, reuse detection, and logout.
 * Raw tokens are never logged or persisted — only SHA-256 digests.
 *
 * <p>Reuse revocation commits inside the transactional worker; the public {@link #refresh}
 * method maps failure outcomes to {@code 401} only after that transaction has completed,
 * so family revocation is not rolled back with the authentication error.
 *
 * <p>A concurrent refresh loser that presents a just-rotated predecessor while the replacement
 * is still active (within {@code concurrent-reuse-grace-seconds}) receives {@code 401} without
 * family revocation. Later replay outside that window still revokes the family.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(RefreshTokenProperties.class)
public class RefreshTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRoleRepository userRoleRepository;
    private final RefreshTokenHasher refreshTokenHasher;
    private final RefreshTokenProperties refreshTokenProperties;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenTransactionWorker transactionWorker;
    private final Clock clock;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRoleRepository userRoleRepository,
            RefreshTokenHasher refreshTokenHasher,
            RefreshTokenProperties refreshTokenProperties,
            JwtTokenService jwtTokenService,
            RefreshTokenTransactionWorker transactionWorker,
            Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRoleRepository = userRoleRepository;
        this.refreshTokenHasher = refreshTokenHasher;
        this.refreshTokenProperties = refreshTokenProperties;
        this.jwtTokenService = jwtTokenService;
        this.transactionWorker = transactionWorker;
        this.clock = clock;
    }

    /**
     * Creates a new refresh-token family for a successful login and returns the transport value.
     */
    @Transactional
    public String issueForLogin(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("Persisted user is required");
        }
        Instant now = clock.instant();
        UUID familyId = UUID.randomUUID();
        GeneratedRefreshToken generated = refreshTokenHasher.generate();
        RefreshToken row = new RefreshToken(
                familyId,
                user,
                generated.hash(),
                now,
                now.plusSeconds(refreshTokenProperties.ttlSeconds()));
        refreshTokenRepository.saveAndFlush(row);
        return generated.transportValue();
    }

    public LoginResponse refresh(String transportToken) {
        try {
            RefreshAttempt attempt = transactionWorker.refresh(transportToken);
            if (attempt.success()) {
                return attempt.response();
            }
            throw unauthorized();
        } catch (DataIntegrityViolationException exception) {
            throw unauthorized();
        }
    }

    @Transactional
    public void logout(String transportToken) {
        byte[] digest;
        try {
            digest = refreshTokenHasher.digestTransportToken(transportToken);
        } catch (IllegalArgumentException exception) {
            // Idempotent logout: malformed/unknown tokens still succeed.
            return;
        }

        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(digest).orElse(null);
        if (token == null) {
            return;
        }
        revokeFamily(token.getFamilyId(), clock.instant());
    }

    private void revokeFamily(UUID familyId, Instant now) {
        List<RefreshToken> active = refreshTokenRepository.findActiveByFamilyIdForUpdate(familyId);
        for (RefreshToken token : active) {
            token.revoke(now);
        }
        if (!active.isEmpty()) {
            refreshTokenRepository.saveAll(active);
            refreshTokenRepository.flush();
        }
    }

    private static BadCredentialsException unauthorized() {
        return new BadCredentialsException("Invalid credentials.");
    }

    /**
     * Transactional refresh worker. Returns failure outcomes instead of throwing so reuse
     * revocation can commit before the API layer converts the outcome to HTTP 401.
     */
    @Service
    @ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
    public static class RefreshTokenTransactionWorker {

        private final RefreshTokenRepository refreshTokenRepository;
        private final UserRoleRepository userRoleRepository;
        private final RefreshTokenHasher refreshTokenHasher;
        private final RefreshTokenProperties refreshTokenProperties;
        private final JwtTokenService jwtTokenService;
        private final Clock clock;

        public RefreshTokenTransactionWorker(
                RefreshTokenRepository refreshTokenRepository,
                UserRoleRepository userRoleRepository,
                RefreshTokenHasher refreshTokenHasher,
                RefreshTokenProperties refreshTokenProperties,
                JwtTokenService jwtTokenService,
                Clock clock) {
            this.refreshTokenRepository = refreshTokenRepository;
            this.userRoleRepository = userRoleRepository;
            this.refreshTokenHasher = refreshTokenHasher;
            this.refreshTokenProperties = refreshTokenProperties;
            this.jwtTokenService = jwtTokenService;
            this.clock = clock;
        }

        @Transactional
        public RefreshAttempt refresh(String transportToken) {
            byte[] digest = refreshTokenHasher.digestTransportToken(transportToken);
            Instant now = clock.instant();

            RefreshToken current = refreshTokenRepository.findByTokenHashForUpdate(digest).orElse(null);
            if (current == null) {
                return RefreshAttempt.failure();
            }

            if (current.getRevokedAt() != null) {
                if (isConcurrentRotationCollision(current, now)) {
                    LOGGER.info(
                            "Concurrent refresh collision for familyId={} userId={}; not treating as reuse",
                            current.getFamilyId(),
                            current.getUser() == null ? null : current.getUser().getId());
                    return RefreshAttempt.failure();
                }
                handleReuse(current, now);
                return RefreshAttempt.failure();
            }

            if (!current.getExpiresAt().isAfter(now)) {
                return RefreshAttempt.failure();
            }

            User user = current.getUser();
            if (user == null || user.getStatus() != UserStatus.ACTIVE) {
                return RefreshAttempt.failure();
            }

            try {
                GeneratedRefreshToken generated = refreshTokenHasher.generate();
                // Revoke first so the partial unique active-family index allows the replacement.
                current.revoke(now);
                refreshTokenRepository.saveAndFlush(current);

                RefreshToken replacement = new RefreshToken(
                        current.getFamilyId(),
                        user,
                        generated.hash(),
                        now,
                        now.plusSeconds(refreshTokenProperties.ttlSeconds()));
                replacement = refreshTokenRepository.saveAndFlush(replacement);
                current.markReplacedBy(replacement.getId(), now);
                refreshTokenRepository.saveAndFlush(current);

                return RefreshAttempt.success(issueAccessResponse(user, generated.transportValue()));
            } catch (DataIntegrityViolationException exception) {
                LOGGER.warn(
                        "Refresh rotation conflict for familyId={} userId={}",
                        current.getFamilyId(),
                        user.getId());
                // Propagate so the transaction rolls back and does not leave a revoked-without-replacement row.
                throw exception;
            }
        }

        private void handleReuse(RefreshToken reused, Instant now) {
            LOGGER.warn(
                    "Refresh token reuse detected; revoking familyId={} userId={}",
                    reused.getFamilyId(),
                    reused.getUser() == null ? null : reused.getUser().getId());
            List<RefreshToken> active =
                    refreshTokenRepository.findActiveByFamilyIdForUpdate(reused.getFamilyId());
            for (RefreshToken token : active) {
                token.revoke(now);
            }
            if (!active.isEmpty()) {
                refreshTokenRepository.saveAll(active);
                refreshTokenRepository.flush();
            }
        }

        /**
         * Distinguishes a concurrent refresh loser from later replay.
         *
         * <p>When request A rotates RT-1 → RT-2 and request B then presents RT-1, B sees a revoked
         * row whose {@code replaced_by_id} still points at the active successor and whose rotation
         * timestamp ({@code last_used_at}) is within the configured grace window. That is expected
         * under {@code SELECT … FOR UPDATE} serialization — not theft — so the family must not be
         * revoked.
         *
         * <p>Genuine replay fails this check when: grace has elapsed; the successor was itself
         * rotated/revoked; or the row was revoked without a replacement (e.g. logout).
         */
        private boolean isConcurrentRotationCollision(RefreshToken presented, Instant now) {
            UUID replacementId = presented.getReplacedById();
            Instant rotatedAt = presented.getLastUsedAt() != null
                    ? presented.getLastUsedAt()
                    : presented.getRevokedAt();
            if (replacementId == null || rotatedAt == null) {
                return false;
            }
            Instant graceDeadline =
                    rotatedAt.plusSeconds(refreshTokenProperties.concurrentReuseGraceSeconds());
            if (now.isAfter(graceDeadline)) {
                return false;
            }
            RefreshToken replacement = refreshTokenRepository.findById(replacementId).orElse(null);
            return replacement != null
                    && replacement.getRevokedAt() == null
                    && replacement.getFamilyId().equals(presented.getFamilyId())
                    && replacement.getExpiresAt().isAfter(now);
        }

        private LoginResponse issueAccessResponse(User user, String refreshTransport) {
            List<UserRole> memberships = userRoleRepository.findByUserIdWithRole(user.getId());
            List<String> roles = memberships.stream()
                    .map(membership -> membership.getRole().getCode().name())
                    .toList();
            IssuedAccessToken access =
                    jwtTokenService.issueAccessToken(user.getId(), user.getEmail(), roles);
            return LoginResponse.bearer(access.tokenValue(), access.expiresInSeconds(), refreshTransport);
        }
    }

    record RefreshAttempt(LoginResponse response) {
        static RefreshAttempt success(LoginResponse response) {
            return new RefreshAttempt(response);
        }

        static RefreshAttempt failure() {
            return new RefreshAttempt(null);
        }

        boolean success() {
            return response != null;
        }
    }
}
