package in.bluebustickets.bluebus.scheduling;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.BusType;
import in.bluebustickets.bluebus.fleet.domain.Seat;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.fleet.repository.BusRepository;
import in.bluebustickets.bluebus.fleet.repository.BusTypeRepository;
import in.bluebustickets.bluebus.fleet.repository.SeatLayoutRepository;
import in.bluebustickets.bluebus.fleet.repository.SeatRepository;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.scheduling.domain.Location;
import in.bluebustickets.bluebus.scheduling.domain.PointType;
import in.bluebustickets.bluebus.scheduling.domain.Route;
import in.bluebustickets.bluebus.scheduling.domain.RoutePoint;
import in.bluebustickets.bluebus.scheduling.domain.RouteStop;
import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripPoint;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventoryStatus;
import in.bluebustickets.bluebus.scheduling.domain.TripStop;
import in.bluebustickets.bluebus.scheduling.domain.TripStopStatus;
import in.bluebustickets.bluebus.scheduling.repository.LocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.RoutePointRepository;
import in.bluebustickets.bluebus.scheduling.repository.RouteRepository;
import in.bluebustickets.bluebus.scheduling.repository.RouteStopRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripPointRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatInventoryRepository;
import in.bluebustickets.bluebus.scheduling.repository.TripStopRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL checks for V5 trip-stop snapshots and physical trip seat inventory.
 * Sale occupancy, holds, and bookings are not implemented here.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class SegmentAwareInventoryPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private OperatorRepository operatorRepository;
    @Autowired private BusTypeRepository busTypeRepository;
    @Autowired private SeatLayoutRepository seatLayoutRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private RoutePointRepository routePointRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private TripStopRepository tripStopRepository;
    @Autowired private TripPointRepository tripPointRepository;
    @Autowired private TripSeatInventoryRepository tripSeatInventoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @Transactional
    void hibernateBuildsPersistenceContextAndMapsTripPointToSameTripStop() {
        EntityType<TripStop> tripStopType = entityManager.getMetamodel().entity(TripStop.class);
        EntityType<TripPoint> tripPointType = entityManager.getMetamodel().entity(TripPoint.class);
        assertThat(tripStopType.getAttribute("tripId").getName()).isEqualTo("tripId");
        assertThat(tripStopType.getAttribute("trip").getName()).isEqualTo("trip");
        assertThat(tripPointType.getAttribute("tripStop").getName()).isEqualTo("tripStop");

        Fixture fixture = persistFixture("JPA");
        TripStop stop = tripStopRepository.saveAndFlush(
                new TripStop(fixture.trip, fixture.hyderabad, 1, StopKind.SOURCE));
        TripPoint point = tripPointRepository.saveAndFlush(
                new TripPoint(stop, "MGBS", PointType.BOTH));

        entityManager.flush();
        entityManager.clear();

        TripPoint reloaded = tripPointRepository.findById(point.getId()).orElseThrow();
        assertThat(reloaded.getTripStop().getId()).isEqualTo(stop.getId());
        assertThat(reloaded.getTrip().getId()).isEqualTo(fixture.trip.getId());
        assertThat(reloaded.getTripStop().getTripId()).isEqualTo(fixture.trip.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT trip_id FROM trip_points WHERE id = ?", UUID.class, reloaded.getId()))
                .isEqualTo(fixture.trip.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT trip_stop_id FROM trip_points WHERE id = ?", UUID.class, reloaded.getId()))
                .isEqualTo(stop.getId());
    }

    @Test
    @Transactional
    void persistsTripStopSequenceAndTripPointsOnTheSameTrip() {
        Fixture fixture = persistFixture("SEQ");

        RoutePoint mgbs = routePointRepository.saveAndFlush(
                new RoutePoint(fixture.hyderabadStop, "MGBS", PointType.BOTH));
        routePointRepository.saveAndFlush(new RoutePoint(fixture.hyderabadStop, "Kukatpally", PointType.BOARDING));

        TripStop hyd = tripStopRepository.saveAndFlush(new TripStop(
                fixture.trip, fixture.hyderabadStop.getId(), fixture.hyderabad, 1, StopKind.SOURCE,
                TripStopStatus.ACTIVE,
                fixture.trip.getScheduledDepartureAt(), fixture.trip.getScheduledDepartureAt(), null));
        TripStop vja = tripStopRepository.saveAndFlush(new TripStop(
                fixture.trip, fixture.vijayawadaStop.getId(), fixture.vijayawada, 3, StopKind.DESTINATION,
                TripStopStatus.ACTIVE,
                fixture.trip.getScheduledDepartureAt().plusSeconds(5 * 3600),
                fixture.trip.getScheduledDepartureAt().plusSeconds(5 * 3600), null));
        tripStopRepository.saveAndFlush(new TripStop(fixture.trip, fixture.suryapet, 2, StopKind.INTERMEDIATE));

        TripPoint mgbsPoint = tripPointRepository.saveAndFlush(
                new TripPoint(hyd, mgbs.getId(), "MGBS", PointType.BOTH));

        assertThat(hyd.getSequenceNumber()).isEqualTo(1);
        assertThat(vja.getSequenceNumber()).isEqualTo(3);
        assertThat(mgbsPoint.getTrip().getId()).isEqualTo(fixture.trip.getId());
        assertThat(mgbsPoint.getTripStop().getId()).isEqualTo(hyd.getId());
        assertThatThrownBy(() -> tripStopRepository.saveAndFlush(
                new TripStop(fixture.trip, fixture.guntur, 1, StopKind.INTERMEDIATE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void databaseRejectsTripPointThatMixesAStopFromAnotherTrip() {
        Fixture first = persistFixture("PTA");
        Fixture second = persistFixture("PTB");
        TripStop firstStop = tripStopRepository.saveAndFlush(
                new TripStop(first.trip, first.hyderabad, 1, StopKind.SOURCE));
        TripStop secondStop = tripStopRepository.saveAndFlush(
                new TripStop(second.trip, second.hyderabad, 1, StopKind.SOURCE));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO trip_points (
                    id, trip_id, trip_stop_id, name, point_type, active, created_at, updated_at)
                VALUES (?, ?, ?, 'MGBS', 'BOTH', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), first.trip.getId(), secondStop.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(firstStop.getTrip().getId()).isNotEqualTo(secondStop.getTrip().getId());
    }

    @Test
    @Transactional
    void persistsPhysicalInventoryForReusableLayoutSeats() {
        Fixture fixture = persistFixture("INV");
        TripSeatInventory inventory = tripSeatInventoryRepository.saveAndFlush(
                new TripSeatInventory(fixture.trip, fixture.seat12A));

        assertThat(inventory.getLayoutSeat().getId()).isEqualTo(fixture.seat12A.getId());
        assertThat(inventory.getSeatNumber()).isEqualTo("12A");
        assertThat(inventory.getSeatLayoutId()).isEqualTo(fixture.layout.getId());
        assertThat(inventory.getSeatLayoutVersion()).isEqualTo(1);
        assertThat(inventory.getPhysicalStatus()).isEqualTo(TripSeatInventoryStatus.AVAILABLE);
        assertThat(inventory.getBlockReason()).isNull();
        assertThat(tripSeatInventoryRepository.saveAndFlush(
                new TripSeatInventory(fixture.trip, fixture.seat12B)).getId()).isNotNull();
    }

    @Test
    @Transactional
    void rejectsDuplicateInventoryForTheSameTripAndLayoutSeat() {
        Fixture fixture = persistFixture("DUP");
        tripSeatInventoryRepository.saveAndFlush(new TripSeatInventory(fixture.trip, fixture.seat12A));
        assertThatThrownBy(() -> tripSeatInventoryRepository.saveAndFlush(
                new TripSeatInventory(fixture.trip, fixture.seat12A)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsInventoryWhoseSeatLayoutDoesNotMatchTheTrip() {
        Fixture fixture = persistFixture("LAY");
        SeatLayout otherLayout = seatLayoutRepository.saveAndFlush(
                new SeatLayout(fixture.operator, "Other layout", 1, 1, 4, 4));
        Seat otherSeat = seatRepository.saveAndFlush(new Seat(otherLayout, "Z1", 1, 1, 1, "SEATER"));

        assertThatThrownBy(() -> new TripSeatInventory(fixture.trip, otherSeat))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Trip seat inventory must belong to the trip seat layout");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO trip_seat_inventory (
                    id, trip_id, layout_seat_id, seat_layout_id, seat_layout_version,
                    seat_number, seat_type, deck_number, row_number, column_number,
                    physical_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'Z1', 'SEATER', 1, 1, 1, 'AVAILABLE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.trip.getId(), otherSeat.getId(), otherLayout.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void physicalInventoryAllowsBlockedAndStoresNoSaleStateColumns() {
        Fixture fixture = persistFixture("PHY");
        TripSeatInventory blocked = tripSeatInventoryRepository.saveAndFlush(
                new TripSeatInventory(fixture.trip, fixture.seat12A, TripSeatInventoryStatus.BLOCKED, "Damaged"));
        assertThat(blocked.getPhysicalStatus()).isEqualTo(TripSeatInventoryStatus.BLOCKED);

        assertThatThrownBy(() -> new TripSeatInventory(
                fixture.trip, fixture.seat12B, TripSeatInventoryStatus.BLOCKED, " "))
                .isInstanceOf(IllegalArgumentException.class);

        List<Map<String, Object>> saleColumns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'trip_seat_inventory'
                  AND column_name IN ('booking_id', 'locked_until', 'fare', 'hold_id')
                """);
        assertThat(saleColumns).isEmpty();
    }

    @Test
    @Transactional
    void physicalInventoryRejectsHeldStatus() {
        Fixture fixture = persistFixture("HLD");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO trip_seat_inventory (
                    id, trip_id, layout_seat_id, seat_layout_id, seat_layout_version,
                    seat_number, seat_type, deck_number, row_number, column_number,
                    physical_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, '12B', 'SEATER', 1, 1, 2, 'HELD', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.trip.getId(), fixture.seat12B.getId(), fixture.layout.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void physicalInventoryRejectsBookedStatus() {
        Fixture fixture = persistFixture("BKD");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO trip_seat_inventory (
                    id, trip_id, layout_seat_id, seat_layout_id, seat_layout_version,
                    seat_number, seat_type, deck_number, row_number, column_number,
                    physical_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, '12B', 'SEATER', 1, 1, 2, 'BOOKED', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.trip.getId(), fixture.seat12B.getId(), fixture.layout.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void existingTripScheduleStillPersistsAfterSegmentAwareMigration() {
        Fixture fixture = persistFixture("TRP");
        assertThat(fixture.trip.getId()).isNotNull();
        assertThat(fixture.trip.getServiceDate()).isNotNull();
        assertThat(fixture.trip.getTimeZone()).isEqualTo("Asia/Kolkata");
        assertThat(fixture.trip.getBaseFare()).isEqualByComparingTo("900.00");
        assertThat(fixture.trip.getSeatLayout().getId()).isEqualTo(fixture.layout.getId());
        assertThat(fixture.seat12A.getSeatLayout().getId()).isEqualTo(fixture.layout.getId());
    }

    private Fixture persistFixture(String suffix) {
        Operator operator = operatorRepository.saveAndFlush(new Operator("V5 Operator " + suffix, "V5 " + suffix));
        BusType busType = busTypeRepository.saveAndFlush(new BusType("V5_" + suffix, "V5 Bus " + suffix));
        SeatLayout layout = seatLayoutRepository.saveAndFlush(new SeatLayout(operator, "V5 Layout " + suffix, 1, 1, 10, 4));
        Seat seat12A = seatRepository.saveAndFlush(new Seat(layout, "12A", 1, 1, 1, "SEATER"));
        Seat seat12B = seatRepository.saveAndFlush(new Seat(layout, "12B", 1, 1, 2, "SEATER"));
        Bus bus = busRepository.saveAndFlush(new Bus(operator, busType, layout, "TS09" + suffix + "1"));

        Location hyderabad = locationRepository.saveAndFlush(new Location("Telangana", "Hyderabad-" + suffix));
        Location suryapet = locationRepository.saveAndFlush(new Location("Telangana", "Suryapet-" + suffix));
        Location vijayawada = locationRepository.saveAndFlush(new Location("Andhra Pradesh", "Vijayawada-" + suffix));
        Location guntur = locationRepository.saveAndFlush(new Location("Andhra Pradesh", "Guntur-" + suffix));
        Route route = routeRepository.saveAndFlush(
                new Route(operator, "HYD-GNT-" + suffix, "Hyderabad to Guntur " + suffix, hyderabad, guntur));
        RouteStop hyderabadStop = routeStopRepository.saveAndFlush(
                new RouteStop(route, hyderabad, 1, StopKind.SOURCE));
        routeStopRepository.saveAndFlush(new RouteStop(route, suryapet, 2, StopKind.INTERMEDIATE));
        RouteStop vijayawadaStop = routeStopRepository.saveAndFlush(
                new RouteStop(route, vijayawada, 3, StopKind.INTERMEDIATE));
        routeStopRepository.saveAndFlush(new RouteStop(route, guntur, 4, StopKind.DESTINATION));

        Instant departure = Instant.parse("2026-10-01T18:30:00Z");
        Trip trip = tripRepository.saveAndFlush(new Trip(
                bus,
                route,
                departure,
                departure.plusSeconds(8 * 3600),
                new BigDecimal("900.00"),
                departure.minusSeconds(7 * 24 * 3600),
                departure.minusSeconds(3600)));

        return new Fixture(
                operator, layout, seat12A, seat12B, hyderabad, suryapet, vijayawada, guntur,
                hyderabadStop, vijayawadaStop, trip);
    }

    private record Fixture(
            Operator operator,
            SeatLayout layout,
            Seat seat12A,
            Seat seat12B,
            Location hyderabad,
            Location suryapet,
            Location vijayawada,
            Location guntur,
            RouteStop hyderabadStop,
            RouteStop vijayawadaStop,
            Trip trip) {
    }
}
