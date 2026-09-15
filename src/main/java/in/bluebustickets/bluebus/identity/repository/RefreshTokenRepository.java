package in.bluebustickets.bluebus.identity.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import in.bluebustickets.bluebus.identity.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") byte[] tokenHash);

    Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from RefreshToken t
            where t.familyId = :familyId and t.revokedAt is null
            """)
    List<RefreshToken> findActiveByFamilyIdForUpdate(@Param("familyId") UUID familyId);

    long countByFamilyIdAndRevokedAtIsNull(UUID familyId);
}
