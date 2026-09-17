package in.bluebustickets.bluebus.foundation.persistence;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    }

    @Test
    void appliesConfirmedBookingCancellationPolicyMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '18'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("18");
            assertThat(migration.get("description")).hasToString("confirmed booking cancellation policy");
            assertThat(migration.get("script")).hasToString("V18__confirmed_booking_cancellation_policy.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        Integer oldPrevious = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ck_booking_cancellations_previous_status'
                """, Integer.class);
        Integer oldPolicy = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ck_booking_cancellations_policy'
                """, Integer.class);
        Integer oldRefundable = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE conname = 'ck_booking_cancellations_refundable_amount'
                """, Integer.class);
        assertThat(oldPrevious).isZero();
        assertThat(oldPolicy).isZero();
        assertThat(oldRefundable).isZero();

        String snapshotCheck = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_booking_cancellations_policy_snapshot'
                """, String.class);
        assertThat(snapshotCheck)
                .contains("UNPAID_CUSTOMER_CANCELLATION_V1")
                .contains("CONFIRMED_FULL_REFUND_CUSTOMER_CANCELLATION_V1")
                .contains("PENDING_PAYMENT")
                .contains("CONFIRMED");
    }

    @Test
    void appliesRefundRetrySupportMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '19'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("19");
            assertThat(migration.get("description")).hasToString("refund retry support");
            assertThat(migration.get("script")).hasToString("V19__refund_retry_support.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(columnExists("refunds", "attempt_count")).isTrue();
        assertThat(columnExists("refunds", "next_retry_at")).isTrue();
        assertThat(indexExists("ix_refunds_due_retry")).isTrue();
        assertThat(indexExists("uq_refunds_attempt_idempotency")).isTrue();
        assertThat(indexExists("uq_refunds_provider_reference")).isTrue();

        String attemptCheck = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_refunds_attempt_count'
                """, String.class);
        assertThat(attemptCheck).contains("attempt_count");

        String indexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'ix_refunds_due_retry'
                """, String.class);
        assertThat(indexDef).contains("next_retry_at");
        assertThat(indexDef).contains("REQUESTED");
        assertThat(indexDef).contains("PROCESSING");
        assertThat(indexDef).contains("FAILED");
        assertThat(indexDef).contains("provider_refund_id");
    }

    @Test
    void appliesTripCancellationBookingCancellationPolicyMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '20'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("20");
            assertThat(migration.get("description")).hasToString(
                    "trip cancellation booking cancellation policy");
            assertThat(migration.get("script")).hasToString(
                    "V20__trip_cancellation_booking_cancellation_policy.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        String snapshotCheck = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_booking_cancellations_policy_snapshot'
                """, String.class);
        assertThat(snapshotCheck)
                .contains("UNPAID_CUSTOMER_CANCELLATION_V1")
                .contains("CONFIRMED_FULL_REFUND_CUSTOMER_CANCELLATION_V1")
                .contains("TRIP_CANCELLED_FULL_REFUND_V1")
                .contains("TRIP_CANCELLED_UNPAID_V1")
                .contains("PENDING_PAYMENT")
                .contains("CONFIRMED");
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

    @Test
    void appliesOutboxTicketIssuedUniqueMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '14'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("14");
            assertThat(migration.get("description")).hasToString("outbox ticket issued unique");
            assertThat(migration.get("script")).hasToString("V14__outbox_ticket_issued_unique.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(indexExists("ux_outbox_ticket_issued_aggregate")).isTrue();

        String indexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'ux_outbox_ticket_issued_aggregate'
                """, String.class);
        assertThat(indexDef).containsIgnoringCase("UNIQUE");
        assertThat(indexDef).contains("aggregate_id");
        assertThat(indexDef).contains("TICKET_ISSUED");
    }

    @Test
    void appliesBusesRegistrationCiUniqueMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '15'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("15");
            assertThat(migration.get("description")).hasToString("buses registration ci unique");
            assertThat(migration.get("script")).hasToString("V15__buses_registration_ci_unique.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(indexExists("ux_buses_registration_number_lower")).isTrue();
        Integer oldConstraint = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'uq_buses_registration_number'
                """, Integer.class);
        assertThat(oldConstraint).isZero();

        String indexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'ux_buses_registration_number_lower'
                """, String.class);
        assertThat(indexDef).containsIgnoringCase("UNIQUE");
        assertThat(indexDef).containsIgnoringCase("lower");
        assertThat(indexDef).contains("registration_number");

        jdbcTemplate.execute("""
                CREATE TEMP TABLE buses_reg_probe (
                    registration_number VARCHAR(30) NOT NULL
                )
                """);
        jdbcTemplate.update("INSERT INTO buses_reg_probe VALUES ('TS09AB1234'), ('ts09ab1234')");
        Integer duplicateGroups = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT 1
                    FROM buses_reg_probe
                    GROUP BY lower(registration_number)
                    HAVING COUNT(*) > 1
                ) d
                """, Integer.class);
        assertThat(duplicateGroups).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbcTemplate.execute("""
                        CREATE UNIQUE INDEX ux_buses_reg_probe_lower
                            ON buses_reg_probe (lower(registration_number))
                        """));
    }

    @Test
    void appliesRoutesOperatorCodeCiUniqueMigration() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList("""
                SELECT version, description, script, success
                FROM flyway_schema_history
                WHERE version = '16'
                """);

        assertThat(migrations).singleElement().satisfies(migration -> {
            assertThat(migration.get("version")).hasToString("16");
            assertThat(migration.get("description")).hasToString("routes operator code ci unique");
            assertThat(migration.get("script")).hasToString("V16__routes_operator_code_ci_unique.sql");
            assertThat(migration.get("success")).isEqualTo(true);
        });

        assertThat(indexExists("ux_routes_operator_code_lower")).isTrue();
        Integer oldConstraint = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname = 'uq_routes_operator_code'
                """, Integer.class);
        assertThat(oldConstraint).isZero();

        String indexDef = jdbcTemplate.queryForObject("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'ux_routes_operator_code_lower'
                """, String.class);
        assertThat(indexDef).containsIgnoringCase("UNIQUE");
        assertThat(indexDef).containsIgnoringCase("lower");
        assertThat(indexDef).contains("operator_id");
        assertThat(indexDef).contains("code");

        jdbcTemplate.execute("""
                CREATE TEMP TABLE routes_code_probe (
                    operator_id UUID NOT NULL,
                    code VARCHAR(60) NOT NULL
                )
                """);
        UUID operatorProbe = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO routes_code_probe VALUES (?, 'HYD-VJA'), (?, 'hyd-vja')",
                operatorProbe,
                operatorProbe);
        Integer duplicateGroups = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT 1
                    FROM routes_code_probe
                    GROUP BY operator_id, lower(code)
                    HAVING COUNT(*) > 1
                ) d
                """, Integer.class);
        assertThat(duplicateGroups).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbcTemplate.execute("""
                        CREATE UNIQUE INDEX ux_routes_code_probe_lower
                            ON routes_code_probe (operator_id, lower(code))
                        """));
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
