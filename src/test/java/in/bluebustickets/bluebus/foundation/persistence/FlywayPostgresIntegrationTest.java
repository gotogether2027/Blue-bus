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
        assertThat(columnExists("trip_seat_inventory", "booking_id")).isFalse();
        assertThat(columnExists("trip_seat_inventory", "locked_until")).isFalse();
        assertThat(columnExists("trip_seat_inventory", "fare")).isFalse();
        assertThat(columnExists("trips", "service_date")).isTrue();
        assertThat(columnExists("trips", "time_zone")).isTrue();
        assertThat(columnExists("trips", "base_fare")).isTrue();
    }

    @Test
    void appliesTripSeatAllocationMigrationWithRangeExclusion() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '6'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("6");
            assertThat(migration.get("description")).hasToString("trip seat allocations");
            assertThat(migration.get("script")).hasToString("V6__trip_seat_allocations.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(tableExists("trip_seat_allocations")).isTrue();

        Integer btreeGist = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_extension
                WHERE extname = 'btree_gist'
                """, Integer.class);
        assertThat(btreeGist).isEqualTo(1);

        Integer exclusionConstraints = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'ex_trip_seat_allocations_no_overlap'
                  AND contype = 'x'
                """, Integer.class);
        assertThat(exclusionConstraints).isEqualTo(1);

        assertThat(columnExists("trip_seat_allocations", "segment_range")).isTrue();
        assertThat(columnExists("trip_seat_allocations", "origin_sequence")).isTrue();
        assertThat(columnExists("trip_seat_allocations", "destination_sequence")).isTrue();
        assertThat(columnExists("trip_seat_allocations", "state")).isTrue();
    }

    @Test
    void appliesSeatHoldsMigrationAndLinksAllocations() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '7'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("7");
            assertThat(migration.get("description")).hasToString("seat holds");
            assertThat(migration.get("script")).hasToString("V7__seat_holds.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(tableExists("seat_holds")).isTrue();

        Integer holdFk = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'fk_trip_seat_allocations_hold'
                  AND contype = 'f'
                """, Integer.class);
        assertThat(holdFk).isEqualTo(1);

        assertThat(columnExists("seat_holds", "origin_sequence")).isTrue();
        assertThat(columnExists("seat_holds", "destination_sequence")).isTrue();
        assertThat(columnExists("seat_holds", "expires_at")).isTrue();
        assertThat(columnExists("seat_holds", "idempotency_key")).isTrue();
    }

    @Test
    void appliesRefreshTokensMigrationWithHashAndActiveFamilyGuards() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '8'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("8");
            assertThat(migration.get("description")).hasToString("refresh tokens");
            assertThat(migration.get("script")).hasToString("V8__refresh_tokens.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(tableExists("refresh_tokens")).isTrue();

        Integer hashLengthCheck = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'ck_refresh_tokens_hash_length'
                """, Integer.class);
        assertThat(hashLengthCheck).isEqualTo(1);

        Integer tokenHashUnique = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'uq_refresh_tokens_token_hash'
                  AND contype = 'u'
                """, Integer.class);
        assertThat(tokenHashUnique).isEqualTo(1);

        Integer activeFamilyIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'uq_refresh_tokens_active_family'
                """, Integer.class);
        assertThat(activeFamilyIndex).isEqualTo(1);

        String indexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'uq_refresh_tokens_active_family'
                """, String.class);
        assertThat(indexDef).containsIgnoringCase("UNIQUE");
        assertThat(indexDef).containsIgnoringCase("family_id");
        assertThat(indexDef).containsIgnoringCase("revoked_at IS NULL");

        assertThat(columnExists("refresh_tokens", "token_hash")).isTrue();
        assertThat(columnExists("refresh_tokens", "family_id")).isTrue();
        assertThat(columnExists("refresh_tokens", "replaced_by_id")).isTrue();
        assertThat(columnExists("refresh_tokens", "last_used_at")).isTrue();
    }

    @Test
    void appliesBookingsMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '9'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("9");
            assertThat(migration.get("description")).hasToString("bookings");
            assertThat(migration.get("script")).hasToString("V9__bookings.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(tableExists("bookings")).isTrue();
        assertThat(tableExists("booking_items")).isTrue();
        assertThat(tableExists("booking_passengers")).isTrue();

        Integer userIdempotency = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'uq_bookings_user_idempotency'
                """, Integer.class);
        assertThat(userIdempotency).isEqualTo(1);

        Integer holdUnique = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'uq_bookings_hold'
                  AND contype = 'u'
                """, Integer.class);
        assertThat(holdUnique).isEqualTo(1);
    }

    @Test
    void appliesUnpaidBookingExpiryMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '10'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("10");
            assertThat(migration.get("description")).hasToString("unpaid booking expiry");
            assertThat(migration.get("script")).hasToString("V10__unpaid_booking_expiry.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(columnExists("bookings", "payment_expires_at")).isTrue();

        Integer notNull = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'bookings'
                  AND column_name = 'payment_expires_at'
                  AND is_nullable = 'NO'
                """, Integer.class);
        assertThat(notNull).isEqualTo(1);

        Integer pendingIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'ix_bookings_pending_payment_expires'
                """, Integer.class);
        assertThat(pendingIndex).isEqualTo(1);

        String itemStatusCheck = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_booking_items_status'
                """, String.class);
        assertThat(itemStatusCheck).contains("EXPIRED");
    }

    @Test
    void appliesPaymentFoundationMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '11'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("11");
            assertThat(migration.get("description")).hasToString("payment foundation");
            assertThat(migration.get("script")).hasToString("V11__payment_foundation.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(tableExists("payment_attempts")).isTrue();
        assertThat(tableExists("payment_provider_events")).isTrue();
        assertThat(tableExists("refunds")).isTrue();
        assertThat(tableExists("outbox_events")).isTrue();

        assertThat(indexExists("uq_payment_attempts_user_idempotency")).isTrue();
        assertThat(indexExists("uq_payment_attempts_active_booking")).isTrue();
        assertThat(indexExists("uq_payment_attempts_applied_booking")).isTrue();
        assertThat(indexExists("ix_payment_provider_events_processing")).isTrue();
        assertThat(indexExists("ix_outbox_events_unpublished")).isTrue();
    }

    @Test
    void appliesCustomerBookingViewsAndCancellationMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '12'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("12");
            assertThat(migration.get("description")).hasToString("customer booking views and cancellation");
            assertThat(migration.get("script")).hasToString("V12__customer_booking_views_and_cancellation.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(tableExists("booking_cancellations")).isTrue();
        assertThat(indexExists("uq_booking_cancellations_booking")).isTrue();
        assertThat(indexExists("ix_booking_cancellations_user_created")).isTrue();
        assertThat(indexExists("ix_trip_stops_location_trip_sequence")).isTrue();

        String previousStatusCheck = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_booking_cancellations_previous_status'
                """, String.class);
        assertThat(previousStatusCheck).contains("PENDING_PAYMENT");

        String refundCheck = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_booking_cancellations_refundable_amount'
                """, String.class);
        assertThat(refundCheck).contains("0");
    }

    @Test
    void appliesTicketFoundationMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '13'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("13");
            assertThat(migration.get("description")).hasToString("tickets");
            assertThat(migration.get("script")).hasToString("V13__tickets.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(tableExists("tickets")).isTrue();
        assertThat(tableExists("ticket_passengers")).isTrue();
        assertThat(indexExists("uq_tickets_booking")).isTrue();
        assertThat(indexExists("uq_tickets_ticket_number")).isTrue();
        assertThat(indexExists("ix_tickets_user_issued")).isTrue();
        assertThat(indexExists("ix_ticket_passengers_ticket")).isTrue();

        String statusCheck = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_tickets_status'
                """, String.class);
        assertThat(statusCheck).contains("ACTIVE").contains("CANCELLED");
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

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = ?
                """, Integer.class, indexName);
        return count != null && count == 1;
    }
}
