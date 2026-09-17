package in.bluebustickets.bluebus.foundation.demodata;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import in.bluebustickets.bluebus.fleet.domain.Bus;
import in.bluebustickets.bluebus.fleet.domain.BusType;
import in.bluebustickets.bluebus.fleet.domain.Seat;
import in.bluebustickets.bluebus.fleet.domain.SeatLayout;
import in.bluebustickets.bluebus.fleet.repository.BusRepository;
import in.bluebustickets.bluebus.fleet.repository.BusTypeRepository;
import in.bluebustickets.bluebus.fleet.repository.SeatLayoutRepository;
import in.bluebustickets.bluebus.fleet.repository.SeatRepository;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.scheduling.application.TripSaleability;
import in.bluebustickets.bluebus.scheduling.domain.Location;
import in.bluebustickets.bluebus.scheduling.domain.PointType;
import in.bluebustickets.bluebus.scheduling.domain.Route;
import in.bluebustickets.bluebus.scheduling.domain.RoutePoint;
import in.bluebustickets.bluebus.scheduling.domain.RouteStop;
import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import in.bluebustickets.bluebus.scheduling.domain.Trip;
import in.bluebustickets.bluebus.scheduling.domain.TripPoint;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatInventory;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent local demo catalog. Creates only demo-named records and never deletes
 * bookings, payments, tickets, or unrelated users.
 */
@Service
@ConditionalOnProperty(prefix = "blue-bus.admin-master-data", name = "enabled", matchIfMissing = true)
@ConditionalOnProperty(prefix = "blue-bus.demo-data", name = "enabled", havingValue = "true")
public class DemoDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoDataService.class);

    private final DemoDataProperties properties;
    private final Clock clock;
    private final PasswordEncoder passwordEncoder;
    private final LocationRepository locationRepository;
    private final OperatorRepository operatorRepository;
    private final BusTypeRepository busTypeRepository;
    private final SeatLayoutRepository seatLayoutRepository;
    private final SeatRepository seatRepository;
    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final RoutePointRepository routePointRepository;
    private final TripRepository tripRepository;
    private final TripStopRepository tripStopRepository;
    private final TripPointRepository tripPointRepository;
    private final TripSeatInventoryRepository tripSeatInventoryRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;

    public DemoDataService(
            DemoDataProperties properties,
            Clock clock,
            PasswordEncoder passwordEncoder,
            LocationRepository locationRepository,
            OperatorRepository operatorRepository,
            BusTypeRepository busTypeRepository,
            SeatLayoutRepository seatLayoutRepository,
            SeatRepository seatRepository,
            BusRepository busRepository,
            RouteRepository routeRepository,
            RouteStopRepository routeStopRepository,
            RoutePointRepository routePointRepository,
            TripRepository tripRepository,
            TripStopRepository tripStopRepository,
            TripPointRepository tripPointRepository,
            TripSeatInventoryRepository tripSeatInventoryRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository) {
        this.properties = properties;
        this.clock = clock;
        this.passwordEncoder = passwordEncoder;
        this.locationRepository = locationRepository;
        this.operatorRepository = operatorRepository;
        this.busTypeRepository = busTypeRepository;
        this.seatLayoutRepository = seatLayoutRepository;
        this.seatRepository = seatRepository;
        this.busRepository = busRepository;
        this.routeRepository = routeRepository;
        this.routeStopRepository = routeStopRepository;
        this.routePointRepository = routePointRepository;
        this.tripRepository = tripRepository;
        this.tripStopRepository = tripStopRepository;
        this.tripPointRepository = tripPointRepository;
        this.tripSeatInventoryRepository = tripSeatInventoryRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
    }

    @Transactional
    public void ensureDemoData() {
        properties.requireCustomerPassword();
        Location hyderabad = location(
                DemoDataCatalog.HYDERABAD_STATE, DemoDataCatalog.HYDERABAD_CITY);
        Location suryapet = location(
                DemoDataCatalog.SURYAPET_STATE, DemoDataCatalog.SURYAPET_CITY);
        Location vijayawada = location(
                DemoDataCatalog.VIJAYAWADA_STATE, DemoDataCatalog.VIJAYAWADA_CITY);
        Operator operator = operator();
        BusType busType = busType();
        SeatLayout layout = seatLayout(operator);
        Bus bus = bus(operator, busType, layout);
        Route route = route(operator, hyderabad, suryapet, vijayawada);
        Trip trip = trip(bus, route);
        customer();
        if (!TripSaleability.isSaleableNow(trip, clock.instant())) {
            throw new IllegalStateException("Demo trip is not saleable under existing TripSaleability rules.");
        }
        LOGGER.debug("Ensured local demo trip {}", trip.getId());
    }

    private Location location(String state, String city) {
        Location location = locationRepository
                .findByCityIgnoreCaseAndStateIgnoreCaseAndLocalityIgnoreCase(
                        city, state, DemoDataCatalog.LOCATION_LOCALITY)
                .orElseGet(() -> {
                    Location created = new Location(state, city);
                    created.updateDetails(
                            "IN",
                            state,
                            null,
                            city,
                            DemoDataCatalog.LOCATION_LOCALITY,
                            null,
                            null,
                            DemoDataCatalog.TIME_ZONE);
                    return locationRepository.save(created);
                });
        location.activate();
        return location;
    }

    private Operator operator() {
        Operator operator = operatorRepository
                .findByLegalNameIgnoreCase(DemoDataCatalog.OPERATOR_LEGAL_NAME)
                .orElseGet(() -> operatorRepository.save(new Operator(
                        DemoDataCatalog.OPERATOR_LEGAL_NAME,
                        DemoDataCatalog.OPERATOR_DISPLAY_NAME)));
        operator.activate();
        return operator;
    }

    private BusType busType() {
        BusType busType = busTypeRepository
                .findByCodeIgnoreCase(DemoDataCatalog.BUS_TYPE_CODE)
                .orElseGet(() -> busTypeRepository.save(new BusType(
                        DemoDataCatalog.BUS_TYPE_CODE,
                        DemoDataCatalog.BUS_TYPE_NAME)));
        busType.activate();
        return busType;
    }

    private SeatLayout seatLayout(Operator operator) {
        SeatLayout layout = seatLayoutRepository
                .findByOperator_IdAndNameIgnoreCaseAndVersion(
                        operator.getId(), DemoDataCatalog.LAYOUT_NAME, DemoDataCatalog.LAYOUT_VERSION)
                .orElseGet(() -> seatLayoutRepository.save(new SeatLayout(
                        operator,
                        DemoDataCatalog.LAYOUT_NAME,
                        DemoDataCatalog.LAYOUT_VERSION,
                        1,
                        2,
                        3)));
        if (seatRepository.countBySeatLayoutId(layout.getId()) == 0) {
            seatRepository.save(new Seat(layout, "L1", 1, 1, 1, "SEATER"));
            seatRepository.save(new Seat(layout, "R1", 1, 1, 3, "SEATER"));
            seatRepository.save(new Seat(layout, "L2", 1, 2, 1, "SEATER"));
            seatRepository.save(new Seat(layout, "R2", 1, 2, 3, "SEATER"));
        }
        layout.publish();
        return layout;
    }

    private Bus bus(Operator operator, BusType busType, SeatLayout layout) {
        Bus bus = busRepository
                .findByRegistrationNumberIgnoreCase(DemoDataCatalog.BUS_REGISTRATION)
                .orElseGet(() -> {
                    Bus created = new Bus(operator, busType, layout, DemoDataCatalog.BUS_REGISTRATION);
                    created.updateDisplayName(DemoDataCatalog.BUS_DISPLAY_NAME);
                    return busRepository.save(created);
                });
        bus.activate();
        return bus;
    }

    private Route route(Operator operator, Location hyderabad, Location suryapet, Location vijayawada) {
        Route route = routeRepository
                .findByOperator_IdAndCodeIgnoreCase(operator.getId(), DemoDataCatalog.ROUTE_CODE)
                .orElseGet(() -> routeRepository.save(new Route(
                        operator,
                        DemoDataCatalog.ROUTE_CODE,
                        DemoDataCatalog.ROUTE_NAME,
                        hyderabad,
                        vijayawada)));
        if (routeStopRepository.findByRoute_IdOrderBySequenceNumberAsc(route.getId()).isEmpty()) {
            RouteStop origin = routeStopRepository.save(new RouteStop(
                    route, hyderabad, 1, StopKind.SOURCE, null, 0, BigDecimal.ZERO));
            routePointRepository.save(new RoutePoint(origin, "Miyapur Boarding", PointType.BOARDING));
            RouteStop intermediate = routeStopRepository.save(new RouteStop(
                    route,
                    suryapet,
                    2,
                    StopKind.INTERMEDIATE,
                    90,
                    100,
                    new BigDecimal("140.50")));
            routePointRepository.save(new RoutePoint(intermediate, "Suryapet Stand", PointType.BOTH));
            RouteStop destination = routeStopRepository.save(new RouteStop(
                    route,
                    vijayawada,
                    3,
                    StopKind.DESTINATION,
                    270,
                    null,
                    new BigDecimal("340.00")));
            routePointRepository.save(new RoutePoint(destination, "Vijayawada RTC", PointType.DROPPING));
        }
        route.activate();
        return route;
    }

    private Trip trip(Bus bus, Route route) {
        Trip trip = tripRepository
                .findByBus_IdAndServiceDateAndScheduledDepartureAt(
                        bus.getId(), DemoDataCatalog.SERVICE_DATE, DemoDataCatalog.DEPARTURE_AT)
                .orElseGet(() -> {
                    Trip created = tripRepository.save(new Trip(
                            bus,
                            route,
                            DemoDataCatalog.DEPARTURE_AT,
                            DemoDataCatalog.ARRIVAL_AT,
                            DemoDataCatalog.BASE_FARE,
                            DemoDataCatalog.BOOKING_OPENS_AT,
                            DemoDataCatalog.BOOKING_CLOSES_AT,
                            DemoDataCatalog.TIME_ZONE));
                    snapshotRoute(created, route);
                    snapshotSeatInventory(created);
                    return created;
                });
        trip.schedule();
        return trip;
    }

    private void snapshotRoute(Trip trip, Route route) {
        List<RouteStop> routeStops = routeStopRepository.findByRoute_IdOrderBySequenceNumberAsc(route.getId());
        if (routeStops.size() < 2) {
            throw new IllegalStateException("Demo route must have at least two ordered stops.");
        }
        Instant tripDeparture = trip.getScheduledDepartureAt();
        for (RouteStop routeStop : routeStops) {
            Instant arrival = offsetFrom(tripDeparture, routeStop.getArrivalOffsetMinutes());
            Instant departure = offsetFrom(tripDeparture, routeStop.getDepartureOffsetMinutes());
            TripStop tripStop = tripStopRepository.save(new TripStop(
                    trip,
                    routeStop.getId(),
                    routeStop.getLocation(),
                    routeStop.getSequenceNumber(),
                    routeStop.getStopKind(),
                    TripStopStatus.ACTIVE,
                    arrival,
                    departure,
                    routeStop.getDistanceKm()));
            for (RoutePoint routePoint : routePointRepository.findByRouteStop_IdOrderByNameAsc(routeStop.getId())) {
                tripPointRepository.save(new TripPoint(
                        tripStop,
                        routePoint.getId(),
                        routePoint.getName(),
                        routePoint.getPointType(),
                        routePoint.getAddress(),
                        routePoint.getLatitude(),
                        routePoint.getLongitude(),
                        routePoint.isActive()));
            }
        }
    }

    private void snapshotSeatInventory(Trip trip) {
        List<Seat> seats = seatRepository.findBySeatLayoutIdOrderByDeckNumberAscRowNumberAscColumnNumberAsc(
                trip.getSeatLayout().getId());
        if (seats.isEmpty()) {
            throw new IllegalStateException("Demo seat layout must contain seats before trip snapshot.");
        }
        for (Seat seat : seats) {
            tripSeatInventoryRepository.save(new TripSeatInventory(trip, seat));
        }
    }

    private void customer() {
        String email = properties.requireCustomerEmail();
        String password = properties.requireCustomerPassword();
        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role is not seeded"));
        User user = userRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            User created = new User(
                    email,
                    null,
                    DemoDataCatalog.CUSTOMER_FIRST_NAME,
                    DemoDataCatalog.CUSTOMER_LAST_NAME);
            created.setPasswordHash(passwordEncoder.encode(password));
            created.setStatus(UserStatus.ACTIVE);
            return userRepository.saveAndFlush(created);
        });
        user.setStatus(UserStatus.ACTIVE);
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        if (!userRoleRepository.existsByUser_IdAndRole_Id(user.getId(), customerRole.getId())) {
            userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        }
    }

    private static Instant offsetFrom(Instant base, Integer offsetMinutes) {
        if (offsetMinutes == null) {
            return null;
        }
        return base.plusSeconds(offsetMinutes.longValue() * 60L);
    }
}
