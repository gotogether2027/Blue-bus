package in.bluebustickets.bluebus.coredomain;

import java.util.UUID;

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
import in.bluebustickets.bluebus.identity.domain.RoleScope;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
import in.bluebustickets.bluebus.scheduling.domain.Location;
import in.bluebustickets.bluebus.scheduling.domain.Route;
import in.bluebustickets.bluebus.scheduling.domain.RouteStop;
import in.bluebustickets.bluebus.scheduling.domain.StopKind;
import in.bluebustickets.bluebus.scheduling.repository.LocationRepository;
import in.bluebustickets.bluebus.scheduling.repository.RouteRepository;
import in.bluebustickets.bluebus.scheduling.repository.RouteStopRepository;
import jakarta.persistence.EntityManager;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL-backed persistence checks for Phase 2's modular domain foundation. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class CoreDomainPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private OperatorUserRepository operatorUserRepository;
    @Autowired private BusTypeRepository busTypeRepository;
    @Autowired private SeatLayoutRepository seatLayoutRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private BusRepository busRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RouteStopRepository routeStopRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;

    @Test
    @Transactional
    void persistsIdentityOperatorAndConfigurableFleetRelationships() {
        User user = userRepository.saveAndFlush(new User("staff@example.test", "+919900000001", "Test", "Staff"));
        Role customer = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        Role operatorAdmin = roleRepository.findByCode(RoleCode.OPERATOR_ADMIN).orElseThrow();
        userRoleRepository.saveAndFlush(new UserRole(user, customer));

        Operator operator = operatorRepository.saveAndFlush(new Operator("Fictional Blue Travels Pvt Ltd", "Blue Travels"));
        operatorUserRepository.saveAndFlush(new OperatorUser(operator, user, operatorAdmin));

        BusType busType = busTypeRepository.saveAndFlush(new BusType("AC_SLEEPER", "AC Sleeper"));
        SeatLayout layout = seatLayoutRepository.saveAndFlush(new SeatLayout(operator, "2+1 Sleeper", 1, 1, 10, 3));
        seatRepository.saveAndFlush(new Seat(layout, "L1", 1, 1, 1, "SLEEPER"));
        seatRepository.saveAndFlush(new Seat(layout, "L2", 1, 1, 2, "SLEEPER"));
        Bus bus = busRepository.saveAndFlush(new Bus(operator, busType, layout, "TS09AB1234"));

        assertThat(userRoleRepository.count()).isEqualTo(1);
        assertThat(operatorUserRepository.count()).isEqualTo(1);
        assertThat(seatRepository.count()).isEqualTo(2);
        assertThat(bus.getId()).isNotNull();
    }

    @Test
    @Transactional
    void enforcesUniqueSeatPositions() {
        Operator operator = operatorRepository.saveAndFlush(new Operator("Fictional Route Operator", "Route Operator"));
        SeatLayout layout = seatLayoutRepository.saveAndFlush(new SeatLayout(operator, "Seater", 1, 1, 5, 4));
        seatRepository.saveAndFlush(new Seat(layout, "A1", 1, 1, 1, "SEATER"));
        assertThatThrownBy(() -> seatRepository.saveAndFlush(new Seat(layout, "A2", 1, 1, 1, "SEATER")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void enforcesUniqueRouteStopSequences() {
        Operator operator = operatorRepository.saveAndFlush(new Operator("Fictional Sequence Operator", "Sequence Operator"));
        Location hyderabad = locationRepository.saveAndFlush(new Location("Telangana", "Hyderabad"));
        Location vijayawada = locationRepository.saveAndFlush(new Location("Andhra Pradesh", "Vijayawada"));
        Route route = routeRepository.saveAndFlush(new Route(operator, "HYD-VJA", "Hyderabad to Vijayawada", hyderabad, vijayawada));
        routeStopRepository.saveAndFlush(new RouteStop(route, hyderabad, 1, StopKind.SOURCE));
        assertThatThrownBy(() -> routeStopRepository.saveAndFlush(new RouteStop(route, vijayawada, 1, StopKind.DESTINATION)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void databaseRejectsRouteStopWithMissingRouteForeignKey() {
        Location location = locationRepository.saveAndFlush(new Location("Telangana", "Warangal"));
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO route_stops (id, route_id, location_id, sequence_number, stop_kind, created_at, updated_at)
                VALUES (?, ?, ?, 1, 'INTERMEDIATE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), UUID.randomUUID(), location.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void databaseRejectsInvalidApprovedRoleCodeScopeCombination() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE roles SET scope = 'PLATFORM' WHERE code = 'OPERATOR_ADMIN'
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void databaseRejectsUserWithoutFirstName() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO users (id, email, first_name, status, created_at, updated_at)
                VALUES (?, ?, NULL, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), "missing-first-name@example.test"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void comparesPersistedEntityIdentifiersRatherThanJavaObjectIdentity() {
        Operator originalOperator = operatorRepository.saveAndFlush(
                new Operator("Identifier Travels", "Identifier Travels"));
        BusType busType = busTypeRepository.saveAndFlush(new BusType("ID_TEST", "Identity Test"));
        SeatLayout layout = seatLayoutRepository.saveAndFlush(
                new SeatLayout(originalOperator, "Identity layout", 1, 1, 2, 2));

        entityManager.flush();
        entityManager.detach(originalOperator);
        entityManager.detach(layout);

        Operator reloadedOperator = operatorRepository.findById(originalOperator.getId()).orElseThrow();
        SeatLayout reloadedLayout = seatLayoutRepository.findById(layout.getId()).orElseThrow();
        assertThat(originalOperator).isNotSameAs(reloadedOperator);
        assertThatCode(() -> new Bus(originalOperator, busType, reloadedLayout, "TS09ID1234"))
                .doesNotThrowAnyException();

        Location originalSource = locationRepository.saveAndFlush(new Location("Telangana", "Nizamabad"));
        Location destination = locationRepository.saveAndFlush(new Location("Andhra Pradesh", "Kurnool"));
        entityManager.flush();
        entityManager.detach(originalSource);
        Location reloadedSource = locationRepository.findById(originalSource.getId()).orElseThrow();

        assertThat(originalSource).isNotSameAs(reloadedSource);
        assertThatThrownBy(() -> new Route(reloadedOperator, "SAME-LOCATION", "Same location", originalSource, reloadedSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Route endpoints must be different");

        assertThatCode(() -> new Route(reloadedOperator, "DIFFERENT-LOCATIONS", "Different locations", originalSource, destination))
                .doesNotThrowAnyException();
    }
}
