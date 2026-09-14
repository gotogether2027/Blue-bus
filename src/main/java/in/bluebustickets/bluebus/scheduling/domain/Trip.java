package in.bluebustickets.bluebus.scheduling.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.foundation.persistence.AuditableEntity;
import in.bluebustickets.bluebus.operator.domain.Operator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One scheduled journey for a bus and route. It retains its selected reusable layout for history. */
@Entity
@Table(name = "trips")
public class Trip extends AuditableEntity {
    @NotNull @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "operator_id")
    private Operator operator;
    @NotNull @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "bus_id")
    private Bus bus;
    @NotNull @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "route_id")
    private Route route;
    @NotNull @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "seat_layout_id")
    private SeatLayout seatLayout;
    @NotNull @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;
    @NotBlank @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone = "Asia/Kolkata";
    @NotNull @Column(name = "scheduled_departure_at", nullable = false)
    private Instant scheduledDepartureAt;
    @NotNull @Column(name = "scheduled_arrival_at", nullable = false)
    private Instant scheduledArrivalAt;
    /** Draft/default fare only. Segment prices will later live on trip_fares, not on inventory. */
    @NotNull @DecimalMin("0.0") @Column(name = "base_fare", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseFare;
    @NotNull @Column(name = "booking_opens_at", nullable = false)
    private Instant bookingOpensAt;
    @NotNull @Column(name = "booking_closes_at", nullable = false)
    private Instant bookingClosesAt;
    @NotNull @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private TripStatus status = TripStatus.DRAFT;

    protected Trip() { }

    public Trip(Bus bus, Route route, Instant scheduledDepartureAt, Instant scheduledArrivalAt,
                BigDecimal baseFare, Instant bookingOpensAt, Instant bookingClosesAt) {
        this(bus, route, scheduledDepartureAt, scheduledArrivalAt, baseFare, bookingOpensAt, bookingClosesAt, null);
    }

    public Trip(
            Bus bus,
            Route route,
            Instant scheduledDepartureAt,
            Instant scheduledArrivalAt,
            BigDecimal baseFare,
            Instant bookingOpensAt,
            Instant bookingClosesAt,
            String timeZone) {
        if (bus == null || route == null || scheduledDepartureAt == null || scheduledArrivalAt == null
                || baseFare == null || bookingOpensAt == null || bookingClosesAt == null) {
            throw new IllegalArgumentException("Trip bus, route, schedule, fare, and booking window are required");
        }
        if (!bus.isActive()) {
            throw new IllegalArgumentException("Trip bus must be active");
        }
        if (!route.isActive()) {
            throw new IllegalArgumentException("Trip route must be active");
        }
        if (!samePersistedOperator(bus.getOperator(), route.getOperator())) {
            throw new IllegalArgumentException("Trip bus and route must belong to the same operator");
        }
        if (!isScheduleValid(scheduledDepartureAt, scheduledArrivalAt)) {
            throw new IllegalArgumentException("Trip arrival must be after departure");
        }
        if (baseFare.signum() < 0) {
            throw new IllegalArgumentException("Trip base fare cannot be negative");
        }
        if (!isBookingWindowValid(bookingOpensAt, bookingClosesAt, scheduledDepartureAt)) {
            throw new IllegalArgumentException("Trip booking window must close on or before departure");
        }
        String normalizedZone = (timeZone == null || timeZone.isBlank()) ? "Asia/Kolkata" : timeZone.trim();
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(normalizedZone);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Trip time zone is invalid");
        }
        this.operator = bus.getOperator();
        this.bus = bus;
        this.route = route;
        this.seatLayout = bus.getSeatLayout();
        this.timeZone = normalizedZone;
        this.serviceDate = scheduledDepartureAt.atZone(zoneId).toLocalDate();
        this.scheduledDepartureAt = scheduledDepartureAt;
        this.scheduledArrivalAt = scheduledArrivalAt;
        this.baseFare = baseFare;
        this.bookingOpensAt = bookingOpensAt;
        this.bookingClosesAt = bookingClosesAt;
    }

    /**
     * Safe commercial-term update only. Bus, route, layout, and schedule snapshots stay immutable.
     */
    public void updateCommercialTerms(BigDecimal baseFare, Instant bookingOpensAt, Instant bookingClosesAt) {
        if (status != TripStatus.DRAFT && status != TripStatus.SCHEDULED) {
            throw new IllegalArgumentException("Trip commercial terms can only be updated while DRAFT or SCHEDULED");
        }
        if (baseFare == null || bookingOpensAt == null || bookingClosesAt == null) {
            throw new IllegalArgumentException("Trip fare and booking window are required");
        }
        if (baseFare.signum() < 0) {
            throw new IllegalArgumentException("Trip base fare cannot be negative");
        }
        if (!isBookingWindowValid(bookingOpensAt, bookingClosesAt, scheduledDepartureAt)) {
            throw new IllegalArgumentException("Trip booking window must close on or before departure");
        }
        this.baseFare = baseFare;
        this.bookingOpensAt = bookingOpensAt;
        this.bookingClosesAt = bookingClosesAt;
    }

    /** Maps admin activate to {@link TripStatus#SCHEDULED}. */
    public void schedule() {
        if (status == TripStatus.SCHEDULED) {
            return;
        }
        if (status != TripStatus.DRAFT) {
            throw new IllegalArgumentException("Trip can only be scheduled from DRAFT status");
        }
        this.status = TripStatus.SCHEDULED;
    }

    /** Maps admin deactivate to {@link TripStatus#CANCELLED}. */
    public void cancel() {
        if (status == TripStatus.CANCELLED) {
            return;
        }
        if (status == TripStatus.DEPARTED || status == TripStatus.COMPLETED) {
            throw new IllegalArgumentException("Trip cannot be cancelled from status " + status);
        }
        this.status = TripStatus.CANCELLED;
    }

    @AssertTrue(message = "arrival must be after departure")
    public boolean isScheduleValid() {
        return scheduledDepartureAt == null || scheduledArrivalAt == null
                || isScheduleValid(scheduledDepartureAt, scheduledArrivalAt);
    }

    @AssertTrue(message = "booking open must precede close, and close must not be after departure")
    public boolean isBookingWindowValid() {
        return bookingOpensAt == null || bookingClosesAt == null || scheduledDepartureAt == null
                || isBookingWindowValid(bookingOpensAt, bookingClosesAt, scheduledDepartureAt);
    }

    private static boolean isScheduleValid(Instant departure, Instant arrival) {
        return arrival.isAfter(departure);
    }

    private static boolean isBookingWindowValid(Instant opens, Instant closes, Instant departure) {
        return opens.isBefore(closes) && !closes.isAfter(departure);
    }

    private static boolean samePersistedOperator(Operator left, Operator right) {
        if (left == right) return true;
        return left != null && right != null && left.getId() != null && right.getId() != null
                && Objects.equals(left.getId(), right.getId());
    }

    public Operator getOperator() { return operator; }
    public Bus getBus() { return bus; }
    public Route getRoute() { return route; }
    public SeatLayout getSeatLayout() { return seatLayout; }
    public LocalDate getServiceDate() { return serviceDate; }
    public String getTimeZone() { return timeZone; }
    public Instant getScheduledDepartureAt() { return scheduledDepartureAt; }
    public Instant getScheduledArrivalAt() { return scheduledArrivalAt; }
    public BigDecimal getBaseFare() { return baseFare; }
    public Instant getBookingOpensAt() { return bookingOpensAt; }
    public Instant getBookingClosesAt() { return bookingClosesAt; }
    public TripStatus getStatus() { return status; }
}
