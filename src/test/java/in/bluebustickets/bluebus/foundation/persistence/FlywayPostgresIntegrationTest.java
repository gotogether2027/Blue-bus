package in.bluebustickets.bluebus.foundation.persistence;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the real PostgreSQL/Flyway startup path without a developer-installed database.
 * Testcontainers disables this test when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class FlywayPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesAndRecordsInitialFoundationMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '1'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("1");
            assertThat(migration.get("description")).hasToString("initial database foundation");
            assertThat(migration.get("script")).hasToString("V1__initial_database_foundation.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });
    }

    @Test
    void appliesRoleIntegrityMigrationAndSeedsApprovedRoles() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '3'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("3");
            assertThat(migration.get("description")).hasToString("role integrity and approved roles");
            assertThat(migration.get("script")).hasToString("V3__role_integrity_and_approved_roles.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        List<Map<String, Object>> roles = jdbcTemplate.queryForList("""
                SELECT code, scope
                FROM roles
                ORDER BY code
                """);

        assertThat(roles).extracting(role -> role.get("code").toString() + ":" + role.get("scope"))
                .containsExactly(
                        "ADMIN:PLATFORM",
                        "CUSTOMER:PLATFORM",
                        "OPERATOR_ADMIN:OPERATOR",
                        "OPERATOR_STAFF:OPERATOR",
                        "SUPER_ADMIN:PLATFORM");
    }

    @Test
    void appliesSegmentAwareInventoryMigrationAndDropsWholeTripSeatSaleState() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '5'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("5");
            assertThat(migration.get("description")).hasToString("segment aware trip inventory");
            assertThat(migration.get("script")).hasToString("V5__segment_aware_trip_inventory.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(tableExists("trip_stops")).isTrue();
        assertThat(tableExists("route_points")).isTrue();
        assertThat(tableExists("trip_points")).isTrue();
        assertThat(tableExists("trip_seat_inventory")).isTrue();
        assertThat(tableExists("trip_seats")).isFalse();
        assertThat(tableExists("trip_seat_allocations")).isFalse();
        assertThat(tableExists("seat_holds")).isFalse();
        assertThat(tableExists("bookings")).isFalse();

        assertThat(columnExists("trip_seat_inventory", "booking_id")).isFalse();
        assertThat(columnExists("trip_seat_inventory", "locked_until")).isFalse();
        assertThat(columnExists("trip_seat_inventory", "fare")).isFalse();
        assertThat(columnExists("trips", "service_date")).isTrue();
        assertThat(columnExists("trips", "time_zone")).isTrue();
        assertThat(columnExists("trips", "base_fare")).isTrue();
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count == 1;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count == 1;
    }
}
