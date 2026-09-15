package in.bluebustickets.bluebus.scheduling.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import in.bluebustickets.bluebus.scheduling.api.dto.CreateSeatHoldRequest;
import in.bluebustickets.bluebus.scheduling.api.dto.SeatHoldResponse;
import in.bluebustickets.bluebus.scheduling.application.SeatHoldService.SeatHoldResult;
import in.bluebustickets.bluebus.scheduling.application.TripStopResolver.ResolvedSegment;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer seat-hold application facade.
 * Anonymous holds use {@code userId = null}; authenticated ownership is deferred.
 * <p>
 * Idempotency keys are accepted and stored, but V7 does not uniquely enforce
 * {@code (NULL user_id, idempotency_key)} — anonymous replay is not DB-guaranteed.
 */
@Service
@EnableConfigurationProperties(SeatHoldCreateProperties.class)
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
public class CustomerSeatHoldService {

    private final TripStopResolver tripStopResolver;
    private final TripStopRepository tripStopRepository;
    private final SeatHoldService seatHoldService;
    private final SeatHoldCreateProperties createProperties;
    private final Clock clock;

    public CustomerSeatHoldService(
            TripStopResolver tripStopResolver,
            TripStopRepository tripStopRepository,
            SeatHoldService seatHoldService,
            SeatHoldCreateProperties createProperties,
            Clock clock) {
        this.tripStopResolver = tripStopResolver;
        this.tripStopRepository = tripStopRepository;
        this.seatHoldService = seatHoldService;
        this.createProperties = createProperties;
        this.clock = clock;
    }

    @Transactional
    public SeatHoldResponse create(UUID tripId, CreateSeatHoldRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Hold request is required");
        }
        if (request.seatInventoryIds() == null || request.seatInventoryIds().isEmpty()) {
            throw new IllegalArgumentException("Seat hold requires at least one inventory id");
        }

        ResolvedSegment segment = tripStopResolver.resolve(
                tripId, request.originStopId(), request.destinationStopId());

        Instant expiresAt = clock.instant().plusSeconds(createProperties.getTtlSeconds());
        String fingerprint = buildFingerprint(
                tripId,
                segment.originSequence(),
                segment.destinationSequence(),
                request.seatInventoryIds());

        // Anonymous phase: userId is always null. Do not accept client-supplied identity.
        SeatHoldResult result = seatHoldService.createHold(
                tripId,
                segment.originSequence(),
                segment.destinationSequence(),
                expiresAt,
                request.seatInventoryIds(),
                null,
                request.idempotencyKey(),
                fingerprint);

        return toResponse(
                result,
                segment.originStopId(),
                segment.destinationStopId());
    }

    @Transactional(readOnly = true)
    public SeatHoldResponse get(UUID holdId) {
        SeatHoldResult result = seatHoldService.getHold(holdId);
        return toResponseFromPersisted(result);
    }

    @Transactional
    public void cancel(UUID holdId) {
        seatHoldService.cancel(holdId);
    }

    private SeatHoldResponse toResponseFromPersisted(SeatHoldResult result) {
        UUID tripId = result.hold().getTripId();
        int originSequence = result.hold().getOriginSequence();
        int destinationSequence = result.hold().getDestinationSequence();
        UUID originStopId = tripStopRepository
                .findByTripIdAndSequenceNumber(tripId, originSequence)
                .map(TripStop::getId)
                .orElse(null);
        UUID destinationStopId = tripStopRepository
                .findByTripIdAndSequenceNumber(tripId, destinationSequence)
                .map(TripStop::getId)
                .orElse(null);
        return toResponse(result, originStopId, destinationStopId);
    }

    private static SeatHoldResponse toResponse(
            SeatHoldResult result,
            UUID originStopId,
            UUID destinationStopId) {
        List<UUID> seatIds = result.allocations().stream()
                .map(allocation -> allocation.getInventory().getId())
                .toList();
        return new SeatHoldResponse(
                result.hold().getId(),
                result.hold().getTripId(),
                originStopId,
                destinationStopId,
                result.hold().getOriginSequence(),
                result.hold().getDestinationSequence(),
                result.hold().getStatus(),
                result.hold().getExpiresAt(),
                seatIds);
    }

    static String buildFingerprint(
            UUID tripId,
            int originSequence,
            int destinationSequence,
            List<UUID> seatInventoryIds) {
        List<UUID> sorted = new ArrayList<>(seatInventoryIds);
        sorted.sort(Comparator.naturalOrder());
        return tripId
                + "|"
                + originSequence
                + "|"
                + destinationSequence
                + "|"
                + sorted.stream().map(UUID::toString).collect(Collectors.joining(","));
    }
}
