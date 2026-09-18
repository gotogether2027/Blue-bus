package in.bluebustickets.bluebus.booking.api;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import in.bluebustickets.bluebus.booking.application.BookingLifecycleService;
import in.bluebustickets.bluebus.booking.domain.Booking;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
import in.bluebustickets.bluebus.payments.domain.PaymentDisposition;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentProviderEventRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import in.bluebustickets.bluebus.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class CustomerBookingReadsPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "journey-a@example.test";
    private static final String ALLOWED_ORIGIN = "http://localhost:4200";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
        registry.add("blue-bus.bookings.expiry.enabled", () -> "false");
        registry.add("blue-bus.payments.default-provider", () -> "UNCONFIGURED");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingCancellationRepository cancellationRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private PaymentProviderEventRepository paymentProviderEventRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private BookingLifecycleService bookingLifecycleService;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;

    private String customerToken;
    private String adminToken;

    @BeforeEach
    void seed() throws Exception {
        refundRepository.deleteAll();
        paymentProviderEventRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        ticketRepository.deleteAll();
        outboxEventRepository.deleteAll();
        cancellationRepository.deleteAll();
        bookingRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User user = new User(CUSTOMER_EMAIL, "+919944481001", "Journey", "Customer");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        customerToken = loginToken(CUSTOMER_EMAIL);
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void unpaidBookingExposesPaymentSummaryWithoutTicketAndEmptyRefunds() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        PaymentAttempt attempt = saveAttempt(booking, "unpaid", Instant.now());

        mockMvc.perform(get("/api/v1/bookings/{id}", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAttemptId").value(attempt.getId().toString()))
                .andExpect(jsonPath("$.paymentStatus").value("INITIATING"))
                .andExpect(jsonPath("$.ticketId").value(nullValue()))
                .andExpect(jsonPath("$.ticketNumber").value(nullValue()))
                .andExpect(jsonPath("$.ticketStatus").value(nullValue()))
                .andExpect(jsonPath("$.latestRefundStatus").value(nullValue()))
                .andExpect(jsonPath("$.latestRefundAmount").value(nullValue()));

        mockMvc.perform(get("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].paymentAttemptId").value(attempt.getId().toString()))
                .andExpect(jsonPath("$[0].ticketId").value(nullValue()));

        mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].paymentAttemptId").value(attempt.getId().toString()))
                .andExpect(jsonPath("$[0].status").value("INITIATING"))
                .andExpect(jsonPath("$[0].failureCode").doesNotExist())
                .andExpect(jsonPath("$[0].nextRetryAt").doesNotExist());

        mockMvc.perform(get("/api/v1/bookings/{id}/ticket", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/bookings/{id}/refunds", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void confirmedBookingExposesPaymentTicketAndCancelledTicketReadHasNoSideEffects() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        PaymentAttempt attempt = saveAttempt(booking, "confirmed", Instant.now());
        attempt.markSucceeded(
                "order-" + attempt.getId(),
                "pay-" + attempt.getId(),
                "captured",
                bookingAmount(booking),
                Instant.now(),
                Instant.now(),
                PaymentDisposition.APPLIED_TO_BOOKING,
                null);
        paymentAttemptRepository.saveAndFlush(attempt);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());

        JsonNode ticket = read(mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated())
                .andReturn());
        long outboxBefore = outboxEventRepository.count();
        long ticketsBefore = ticketRepository.count();

        mockMvc.perform(get("/api/v1/bookings/{id}", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAttemptId").value(attempt.getId().toString()))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.ticketId").value(ticket.get("ticketId").asText()))
                .andExpect(jsonPath("$.ticketNumber").value(ticket.get("ticketNumber").asText()))
                .andExpect(jsonPath("$.ticketStatus").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/bookings/{id}/ticket", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(ticket.get("ticketId").asText()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.bookingId").value(booking.bookingId().toString()));

        assertThat(outboxEventRepository.count()).isEqualTo(outboxBefore);
        assertThat(ticketRepository.count()).isEqualTo(ticketsBefore);

        jdbcTemplate.update("UPDATE tickets SET status = 'CANCELLED' WHERE id = ?",
                UUID.fromString(ticket.get("ticketId").asText()));

        mockMvc.perform(get("/api/v1/bookings/{id}/ticket", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/v1/bookings/{id}", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketStatus").value("CANCELLED"));
    }

    @Test
    void multiplePaymentsAndRefundsUseNewestCreatedAtThenId() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-01-02T00:00:00Z");
        PaymentAttempt failed = saveAttempt(booking, "older", older);
        failed.markFailed("failed", "CARD_DECLINED", older, older);
        paymentAttemptRepository.saveAndFlush(failed);
        backdate(failed.getId(), older);

        PaymentAttempt pending = saveAttempt(booking, "newer", newer);
        pending.markInitiated("order-newer", "created", "checkout");
        paymentAttemptRepository.saveAndFlush(pending);
        backdate(pending.getId(), newer);

        mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].paymentAttemptId").value(pending.getId().toString()))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].paymentAttemptId").value(failed.getId().toString()))
                .andExpect(jsonPath("$[1].status").value("FAILED"));

        mockMvc.perform(get("/api/v1/bookings/{id}", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentAttemptId").value(pending.getId().toString()))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));

        Refund first = saveRefund(pending, booking, "r1", new BigDecimal("100.00"), older);
        Refund second = saveRefund(pending, booking, "r2", new BigDecimal("250.00"), newer);
        backdateRefund(first.getId(), older);
        backdateRefund(second.getId(), newer);

        mockMvc.perform(get("/api/v1/bookings/{id}/refunds", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].refundId").value(second.getId().toString()))
                .andExpect(jsonPath("$[0].amount").value(250.00))
                .andExpect(jsonPath("$[1].refundId").value(first.getId().toString()))
                .andExpect(jsonPath("$[0].attemptCount").doesNotExist())
                .andExpect(jsonPath("$[0].nextRetryAt").doesNotExist());

        mockMvc.perform(get("/api/v1/bookings/{id}", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRefundStatus").value("REQUESTED"))
                .andExpect(jsonPath("$.latestRefundAmount").value(250.00));
    }

    @Test
    void nestedReadsAreOwnerScopedAndMissingBookingIs404() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        saveAttempt(booking, "owned", Instant.now());
        String otherToken = createOtherCustomer();
        UUID missing = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}/ticket", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}/refunds", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/bookings/{id}/payments", missing)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}/ticket", missing)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bookings/{id}/refunds", missing)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.bookingId()).with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bookings/{id}/ticket", booking.bookingId()).with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bookings/{id}/refunds", booking.bookingId()).with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherCustomerCannotAccessCancelOrRefundAnotherCustomersResources() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        PaymentAttempt attempt = saveAttempt(booking, "idor", Instant.now());
        attempt.markSucceeded(
                "order-" + attempt.getId(),
                "pay-" + attempt.getId(),
                "captured",
                bookingAmount(booking),
                Instant.now(),
                Instant.now(),
                PaymentDisposition.APPLIED_TO_BOOKING,
                null);
        paymentAttemptRepository.saveAndFlush(attempt);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        Refund refund = saveRefund(
                attempt, booking, "idor", bookingAmount(booking), Instant.now());

        Booking persisted = bookingRepository.findById(booking.bookingId()).orElseThrow();
        String otherToken = testAccessTokenFactory.issueCustomer().accessToken();
        String[] secrets = {
                persisted.getUserId().toString(),
                CUSTOMER_EMAIL,
                refund.getId().toString(),
                "pay-" + attempt.getId()
        };
        long refundsBefore = refundRepository.count();
        long cancellationsBefore = cancellationRepository.count();
        long paymentsBefore = paymentAttemptRepository.count();

        expectHiddenNotFound(
                mockMvc.perform(get("/api/v1/bookings/{id}", booking.bookingId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                        .andReturn(),
                "Booking was not found.",
                secrets);
        expectHiddenNotFound(
                mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"IDOR\"}"))
                        .andReturn(),
                "Booking was not found.",
                secrets);
        expectHiddenNotFound(
                mockMvc.perform(get("/api/v1/bookings/{id}/payments", booking.bookingId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                        .andReturn(),
                "Booking was not found.",
                secrets);
        expectHiddenNotFound(
                mockMvc.perform(get("/api/v1/payments/{id}", attempt.getId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                        .andReturn(),
                "Payment attempt was not found.",
                secrets);
        expectHiddenNotFound(
                mockMvc.perform(post("/api/v1/payments/{id}/checkout", attempt.getId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "razorpayOrderId":"order_idor",
                                          "razorpayPaymentId":"pay_idor",
                                          "razorpaySignature":"sig_idor"
                                        }
                                        """))
                        .andReturn(),
                "Payment attempt was not found.",
                secrets);
        expectHiddenNotFound(
                mockMvc.perform(post("/api/v1/payments/{id}/refunds", attempt.getId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                                .header("Idempotency-Key", "idor-refund")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"IDOR\"}"))
                        .andReturn(),
                "Payment attempt was not found.",
                secrets);
        expectHiddenNotFound(
                mockMvc.perform(get("/api/v1/bookings/{id}/refunds", booking.bookingId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                        .andReturn(),
                "Booking was not found.",
                secrets);

        assertThat(refundRepository.count()).isEqualTo(refundsBefore);
        assertThat(cancellationRepository.count()).isEqualTo(cancellationsBefore);
        assertThat(paymentAttemptRepository.count()).isEqualTo(paymentsBefore);
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getUserId())
                .isEqualTo(persisted.getUserId());
        mockMvc.perform(get("/api/v1/bookings/{id}/refunds", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].refundId").value(refund.getId().toString()));
    }

    private void expectHiddenNotFound(MvcResult result, String message, String... secrets) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("message").asText()).isEqualTo(message);
        String raw = result.getResponse().getContentAsString();
        for (String secret : secrets) {
            assertThat(raw).doesNotContain(secret);
        }
    }

    private PaymentAttempt saveAttempt(CreatedBooking booking, String suffix, Instant createdAt) {
        Booking persisted = bookingRepository.findById(booking.bookingId()).orElseThrow();
        PaymentAttempt attempt = new PaymentAttempt(
                persisted.getId(),
                persisted.getUserId(),
                "RAZORPAY",
                "mr-" + suffix + "-" + persisted.getId(),
                "idem-" + suffix + "-" + persisted.getId(),
                "fp-" + suffix + "-" + persisted.getId(),
                persisted.getTotalAmount(),
                persisted.getCurrency(),
                persisted.getPaymentExpiresAt());
        attempt = paymentAttemptRepository.saveAndFlush(attempt);
        backdate(attempt.getId(), createdAt);
        return paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
    }

    private Refund saveRefund(
            PaymentAttempt attempt, CreatedBooking booking, String suffix, BigDecimal amount, Instant createdAt) {
        Refund refund = new Refund(
                attempt.getId(),
                booking.bookingId(),
                "RAZORPAY",
                "refund-" + suffix + "-" + booking.bookingId(),
                "rfp-" + suffix + "-" + booking.bookingId(),
                amount,
                "INR",
                "CUSTOMER_REQUEST",
                createdAt);
        refund = refundRepository.saveAndFlush(refund);
        backdateRefund(refund.getId(), createdAt);
        return refundRepository.findById(refund.getId()).orElseThrow();
    }

    private void backdate(UUID paymentAttemptId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE payment_attempts SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                paymentAttemptId);
    }

    private void backdateRefund(UUID refundId, Instant createdAt) {
        jdbcTemplate.update(
                "UPDATE refunds SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                refundId);
    }

    private BigDecimal bookingAmount(CreatedBooking booking) {
        return bookingRepository.findById(booking.bookingId()).orElseThrow().getTotalAmount();
    }

    private CreatedBooking createPendingBooking(int seatCount) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        TripFixture trip = createTrip("JNBUS-" + n, "JNRT-" + n, Math.max(seatCount, 2));
        List<UUID> seats = trip.availableSeatIds().subList(0, seatCount);
        JsonNode hold = createHold(trip, seats);
        UUID holdId = UUID.fromString(hold.get("holdId").asText());

        StringBuilder passengers = new StringBuilder();
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                passengers.append(',');
            }
            passengers.append("""
                    {"seatInventoryId":"%s","fullName":"Rider %d","age":%d,"gender":"F"}
                    """.formatted(seats.get(i), i + 1, 25 + i));
        }

        MvcResult created = mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holdId":"%s",
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "idempotencyKey":"book-jn-%d",
                                  "passengers":[%s]
                                }
                                """.formatted(holdId, trip.stopId(1), trip.stopId(3), n, passengers)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        return new CreatedBooking(UUID.fromString(body.get("bookingId").asText()), trip.tripId(), holdId);
    }

    private JsonNode createHold(TripFixture trip, List<UUID> seats) throws Exception {
        StringBuilder seatJson = new StringBuilder("[");
        for (int i = 0; i < seats.size(); i++) {
            if (i > 0) {
                seatJson.append(',');
            }
            seatJson.append('"').append(seats.get(i)).append('"');
        }
        seatJson.append(']');
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originStopId":"%s","destinationStopId":"%s","seatInventoryIds":%s}
                                """.formatted(trip.stopId(1), trip.stopId(3), seatJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private TripFixture createTrip(String registration, String routeCode, int seatCount) throws Exception {
        UUID operatorId = createMaster("operators",
                "{\"legalName\":\"Op %s\",\"displayName\":\"Co %s\"}".formatted(registration, registration));
        UUID busTypeId = createMaster("bus-types",
                "{\"code\":\"J%s\",\"displayName\":\"Type %s\"}".formatted(registration, registration));
        UUID layoutId = createSeatLayout(operatorId, registration, seatCount);
        UUID busId = createMaster("buses", """
                {"operatorId":"%s","busTypeId":"%s","seatLayoutId":"%s","registrationNumber":"%s"}
                """.formatted(operatorId, busTypeId, layoutId, registration));
        UUID hyderabadId = createMaster("locations", locationJson("Telangana", "Hyderabad-" + registration));
        UUID suryapetId = createMaster("locations", locationJson("Telangana", "Suryapet-" + registration));
        UUID vijayawadaId = createMaster("locations", locationJson("Andhra Pradesh", "Vijayawada-" + registration));
        UUID gunturId = createMaster("locations", locationJson("Andhra Pradesh", "Guntur-" + registration));
        UUID routeId = createRoute(operatorId, routeCode, hyderabadId, suryapetId, vijayawadaId, gunturId);

        Instant departure = Instant.parse("2026-12-01T10:00:00Z");
        MvcResult created = mockMvc.perform(post("/api/v1/admin/trips")
                        .with(TestAccessTokenFactory.bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s","routeId":"%s",
                                  "scheduledDepartureAt":"%s","scheduledArrivalAt":"%s",
                                  "baseFare":900.00,"bookingOpensAt":"%s","bookingClosesAt":"%s",
                                  "timeZone":"Asia/Kolkata"
                                }
                                """.formatted(
                                busId, routeId, departure, departure.plusSeconds(6 * 3600),
                                Instant.parse("2020-01-01T00:00:00Z"), departure.minusSeconds(3600))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID tripId = UUID.fromString(body.get("id").asText());
        mockMvc.perform(post("/api/v1/admin/trips/{id}/activate", tripId)
                        .with(TestAccessTokenFactory.bearer(adminToken)))
                .andExpect(status().isOk());
        List<UUID> stopIdsBySequence = new ArrayList<>();
        stopIdsBySequence.add(null);
        for (JsonNode stop : body.get("stops")) {
            int sequence = stop.get("sequenceNumber").asInt();
            while (stopIdsBySequence.size() <= sequence) {
                stopIdsBySequence.add(null);
            }
            stopIdsBySequence.set(sequence, UUID.fromString(stop.get("id").asText()));
        }
        List<UUID> available = new ArrayList<>();
        for (JsonNode seat : body.get("seatInventory")) {
            if ("AVAILABLE".equals(seat.get("physicalStatus").asText())) {
                available.add(UUID.fromString(seat.get("id").asText()));
            }
        }
        return new TripFixture(tripId, stopIdsBySequence, available);
    }

    private UUID createSeatLayout(UUID operatorId, String registration, int seatCount) throws Exception {
        StringBuilder seats = new StringBuilder("[");
        for (int i = 0; i < seatCount; i++) {
            if (i > 0) {
                seats.append(',');
            }
            seats.append("""
                    {"seatNumber":"S%d","deckNumber":1,"rowNumber":1,"columnNumber":%d,"seatType":"SEATER","sellable":true}
                    """.formatted(i + 1, i + 1));
        }
        seats.append(']');
        return createMaster("seat-layouts", """
                {
                  "operatorId":"%s","name":"Layout %s","version":1,"deckCount":1,"rowCount":1,"columnCount":%d,
                  "seats":%s
                }
                """.formatted(operatorId, registration, seatCount, seats));
    }

    private UUID createRoute(UUID operatorId, String code, UUID hyd, UUID sur, UUID vij, UUID gun) throws Exception {
        return createMaster("routes", """
                {
                  "operatorId":"%s","code":"%s","name":"Hyderabad to Guntur",
                  "sourceLocationId":"%s","destinationLocationId":"%s",
                  "stops":[
                    {"locationId":"%s","sequenceNumber":1,"stopKind":"SOURCE","departureOffsetMinutes":0,"distanceKm":0,
                     "points":[{"name":"Miyapur Boarding","pointType":"BOARDING"}]},
                    {"locationId":"%s","sequenceNumber":2,"stopKind":"INTERMEDIATE","arrivalOffsetMinutes":90,"departureOffsetMinutes":100,"distanceKm":140.5,
                     "points":[{"name":"Suryapet Stand","pointType":"BOTH"}]},
                    {"locationId":"%s","sequenceNumber":3,"stopKind":"INTERMEDIATE","arrivalOffsetMinutes":180,"departureOffsetMinutes":190,"distanceKm":260},
                    {"locationId":"%s","sequenceNumber":4,"stopKind":"DESTINATION","arrivalOffsetMinutes":270,"distanceKm":340,
                     "points":[{"name":"Guntur RTC","pointType":"DROPPING"}]}
                  ]
                }
                """.formatted(operatorId, code, hyd, gun, hyd, sur, vij, gun));
    }

    private UUID createMaster(String resource, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/{resource}", resource)
                        .with(TestAccessTokenFactory.bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private static String locationJson(String state, String city) {
        return """
                {"countryCode":"IN","state":"%s","city":"%s","timeZone":"Asia/Kolkata"}
                """.formatted(state, city);
    }

    private String createOtherCustomer() throws Exception {
        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User other = new User("journey-b@example.test", "+919944481002", "Other", "Customer");
        other.setPasswordHash(passwordEncoder.encode(PASSWORD));
        other = userRepository.saveAndFlush(other);
        userRoleRepository.saveAndFlush(new UserRole(other, customerRole));
        return loginToken(other.getEmail());
    }

    private String loginToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record CreatedBooking(UUID bookingId, UUID tripId, UUID holdId) {
    }

    private record TripFixture(UUID tripId, List<UUID> stopIdsBySequence, List<UUID> availableSeatIds) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }
}
