package in.bluebustickets.bluebus.payments.api;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import in.bluebustickets.bluebus.booking.api.dto.CancelBookingRequest;
import in.bluebustickets.bluebus.booking.application.BookingCancellationService;
import in.bluebustickets.bluebus.booking.application.BookingExpiryService;
import in.bluebustickets.bluebus.booking.application.BookingLifecycleService;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
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
import in.bluebustickets.bluebus.payments.application.RefundRetryProcessor;
import in.bluebustickets.bluebus.payments.application.RefundRetryProperties;
import in.bluebustickets.bluebus.payments.application.RefundRetryService;
import in.bluebustickets.bluebus.payments.domain.PaymentDisposition;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.domain.RefundStatus;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentProviderEventRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import in.bluebustickets.bluebus.scheduling.application.JourneySeatAvailability;
import in.bluebustickets.bluebus.scheduling.application.SeatAvailabilityService;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.TripSeatAllocationRepository;
import in.bluebustickets.bluebus.ticket.domain.TicketStatus;
import in.bluebustickets.bluebus.ticket.repository.TicketRepository;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class RazorpayPaymentPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "razorpay-a@example.test";
    private static final String KEY_ID = "rzp_test_public_key";
    private static final String KEY_SECRET = "rzp_test_key_secret_value";
    private static final String WEBHOOK_SECRET = "rzp_test_webhook_secret";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static final RazorpayFakeGateway GATEWAY = RazorpayFakeGateway.start();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.seat-holds.expiry.enabled", () -> "false");
        registry.add("blue-bus.bookings.expiry.enabled", () -> "false");
        registry.add("blue-bus.payments.default-provider", () -> "RAZORPAY");
        registry.add("blue-bus.payments.razorpay.key-id", () -> KEY_ID);
        registry.add("blue-bus.payments.razorpay.key-secret", () -> KEY_SECRET);
        registry.add("blue-bus.payments.razorpay.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("blue-bus.payments.razorpay.base-url", GATEWAY::baseUrl);
        registry.add("blue-bus.payments.razorpay.connect-timeout", () -> "2s");
        registry.add("blue-bus.payments.razorpay.read-timeout", () -> "2s");
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.stop();
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
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private PaymentProviderEventRepository paymentProviderEventRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private SeatAvailabilityService seatAvailabilityService;
    @Autowired private BookingExpiryService bookingExpiryService;
    @Autowired private BookingLifecycleService bookingLifecycleService;
    @Autowired private BookingCancellationService bookingCancellationService;
    @Autowired private RefundRetryService refundRetryService;
    @Autowired private RefundRetryProcessor refundRetryProcessor;
    @Autowired private RefundRetryProperties refundRetryProperties;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;

    private String customerToken;
    private String adminToken;

    @BeforeEach
    void seed() throws Exception {
        GATEWAY.reset();
        refundRetryProperties.setAfterCommitEnabled(true);
        ticketRepository.deleteAll();
        refundRepository.deleteAll();
        paymentProviderEventRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        outboxEventRepository.deleteAll();
        cancellationRepository.deleteAll();
        bookingRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User user = new User(CUSTOMER_EMAIL, "+919944490001", "Razorpay", "Customer");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        customerToken = loginToken(CUSTOMER_EMAIL);
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void initiationUsesPersistedAmountAndReturnsPublicCheckoutKey() throws Exception {
        CreatedBooking booking = createPendingBooking();
        BigDecimal total = bookingRepository.findById(booking.bookingId()).orElseThrow().getTotalAmount();

        mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "init-valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1.00,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.provider").value("RAZORPAY"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.checkoutReference").value(KEY_ID))
                .andExpect(jsonPath("$.amount").value(total.doubleValue()))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.providerOrderId").exists());

        String body = mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "init-valid"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain(KEY_SECRET);
        assertThat(body).doesNotContain(WEBHOOK_SECRET);
        assertThat(GATEWAY.lastOrderAmountPaise()).isEqualTo(total.movePointRight(2).longValueExact());
        assertThat(GATEWAY.uniqueOrderCount()).isEqualTo(1);
        assertThat(GATEWAY.orderHttpCalls()).isEqualTo(1);
    }

    @Test
    void initiationRejectsWrongCustomerAndNonPayableBookings() throws Exception {
        CreatedBooking owned = createPendingBooking();
        String otherToken = createOtherCustomer();

        mockMvc.perform(post("/api/v1/bookings/{id}/payments", owned.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .header("Idempotency-Key", "other-key"))
                .andExpect(status().isNotFound());

        CreatedBooking confirmed = createPendingBooking();
        bookingLifecycleService.confirmPendingPayment(confirmed.bookingId());
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", confirmed.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "confirmed-key"))
                .andExpect(status().isConflict());

        CreatedBooking cancelled = createPendingBooking();
        bookingLifecycleService.cancelUnpaidBooking(cancelled.bookingId());
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", cancelled.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "cancelled-key"))
                .andExpect(status().isConflict());

        CreatedBooking expired = createPendingBooking();
        forcePaymentExpiresAt(expired.bookingId(), Instant.now().minusSeconds(5));
        bookingExpiryService.expireDueBookings(Instant.now());
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", expired.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "expired-key"))
                .andExpect(status().isConflict());

        CreatedBooking overdue = createPendingBooking();
        forcePaymentExpiresAt(overdue.bookingId(), Instant.now().minusSeconds(1));
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", overdue.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "overdue-key"))
                .andExpect(status().isConflict());
    }

    @Test
    void duplicateIdempotencyKeyConflictsWhenTheRequestDiffers() throws Exception {
        CreatedBooking first = createPendingBooking();
        CreatedBooking second = createPendingBooking();

        JsonNode initiated = read(mockMvc.perform(post("/api/v1/bookings/{id}/payments", first.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "same-key"))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", first.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "same-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentAttemptId").value(initiated.get("paymentAttemptId").asText()));
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", second.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "same-key"))
                .andExpect(status().isConflict());
    }

    @Test
    void razorpayOrderErrorsMapToUnavailableWithoutLeakingSecrets() throws Exception {
        CreatedBooking timeoutBooking = createPendingBooking();
        GATEWAY.delayOrders = true;
        String timeoutBody = mockMvc.perform(post("/api/v1/bookings/{id}/payments", timeoutBooking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "timeout-key"))
                .andExpect(status().isServiceUnavailable())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(timeoutBody).doesNotContain(KEY_SECRET);
        GATEWAY.delayOrders = false;

        GATEWAY.orderStatus = 400;
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", createPendingBooking().bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "fourxx-key"))
                .andExpect(status().isServiceUnavailable());

        GATEWAY.orderStatus = 500;
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", createPendingBooking().bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "fivexx-key"))
                .andExpect(status().isServiceUnavailable());
        GATEWAY.orderStatus = 200;

        GATEWAY.malformedOrders = true;
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", createPendingBooking().bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "malformed-key"))
                .andExpect(status().isServiceUnavailable());
        GATEWAY.malformedOrders = false;
    }

    @Test
    void checkoutVerificationUsesStoredOrderIdAndExistingStateMachine() throws Exception {
        JsonNode attempt = initiate(createPendingBooking(), "checkout-key");
        UUID attemptId = UUID.fromString(attempt.get("paymentAttemptId").asText());
        String orderId = attempt.get("providerOrderId").asText();
        String paymentId = "pay_checkout_1";
        String signature = hmac(orderId + "|" + paymentId, KEY_SECRET);

        mockMvc.perform(post("/api/v1/payments/{id}/checkout", attemptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(paymentId, orderId, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.disposition").value("APPLIED_TO_BOOKING"));
        assertThat(bookingRepository.findById(UUID.fromString(attempt.get("bookingId").asText()))
                .orElseThrow()
                .getStatus()).isEqualTo(BookingStatus.CONFIRMED);

        mockMvc.perform(post("/api/v1/payments/{id}/checkout", attemptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(paymentId, orderId, signature)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        JsonNode other = initiate(createPendingBooking(), "checkout-invalid");
        UUID otherId = UUID.fromString(other.get("paymentAttemptId").asText());
        String stored = other.get("providerOrderId").asText();
        mockMvc.perform(post("/api/v1/payments/{id}/checkout", otherId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("pay_wrong", stored, hmac(stored + "|pay_right", KEY_SECRET))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/payments/{id}/checkout", otherId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("pay_ok", "order_not_ours", hmac("order_not_ours|pay_ok", KEY_SECRET))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/payments/{id}/checkout", otherId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("pay_ok", stored, "deadbeef")))
                .andExpect(status().isUnauthorized());

        String otherToken = createOtherCustomer();
        mockMvc.perform(post("/api/v1/payments/{id}/checkout", attemptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody(paymentId, orderId, signature)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/payments/{id}", attemptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void webhookVerifiesRawBodyAndIsIdempotent() throws Exception {
        JsonNode attempt = initiate(createPendingBooking(), "webhook-key");
        String compact = capturedBody(attempt, "evt_raw_1", "pay_raw_1", epochNow());
        String pretty = compact.replace(":", " : ");
        mockMvc.perform(post("/api/v1/payments/webhooks/RAZORPAY")
                        .header("X-Razorpay-Signature", hmac(compact, WEBHOOK_SECRET))
                        .header("X-Razorpay-Event-Id", "evt_raw_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pretty))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/payments/webhooks/RAZORPAY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compact))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/payments/webhooks/RAZORPAY")
                        .header("X-Razorpay-Signature", hmac(compact, WEBHOOK_SECRET))
                        .header("X-Razorpay-Event-Id", "evt_raw_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compact))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/api/v1/payments/webhooks/RAZORPAY")
                        .header("X-Razorpay-Signature", hmac(compact, WEBHOOK_SECRET))
                        .header("X-Razorpay-Event-Id", "evt_raw_1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compact))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        UUID bookingId = UUID.fromString(attempt.get("bookingId").asText());
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxCount("BOOKING_CONFIRMED", bookingId)).isEqualTo(1);
    }

    @Test
    void webhookPathNeverCallsOutboundRazorpayHttpBefore2xx() throws Exception {
        JsonNode attempt = initiate(createPendingBooking(), "webhook-no-http");
        int ordersBefore = GATEWAY.orderHttpCalls();
        int refundsBefore = GATEWAY.refundHttpCalls();
        int totalBefore = GATEWAY.totalHttpCalls();

        String body = capturedBody(attempt, "evt_no_http", "pay_no_http", epochNow());
        sendWebhook(body, "evt_no_http")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));
        sendWebhook(body, "evt_no_http")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(GATEWAY.orderHttpCalls()).isEqualTo(ordersBefore);
        assertThat(GATEWAY.refundHttpCalls()).isEqualTo(refundsBefore);
        assertThat(GATEWAY.totalHttpCalls()).isEqualTo(totalBefore);
        assertThat(bookingRepository.findById(UUID.fromString(attempt.get("bookingId").asText()))
                .orElseThrow()
                .getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paymentAttemptRepository.findById(
                UUID.fromString(attempt.get("paymentAttemptId").asText())).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void authorizedDoesNotConfirmUntilCapturedAndLateAuthorizedCannotDowngrade() throws Exception {
        CreatedBooking booking = createPendingBooking();
        JsonNode attempt = initiate(booking, "auth-then-capture");
        UUID attemptId = UUID.fromString(attempt.get("paymentAttemptId").asText());
        UUID bookingId = booking.bookingId();
        String paymentId = "pay_auth_cap_1";

        sendWebhook(authorizedBody(attempt, "evt_authorized_1", paymentId, epochNow()), "evt_authorized_1")
                .andExpect(status().isOk());

        var afterAuthorized = paymentAttemptRepository.findById(attemptId).orElseThrow();
        assertThat(afterAuthorized.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(afterAuthorized.getDisposition()).isEqualTo(PaymentDisposition.UNAPPLIED);
        assertThat(afterAuthorized.getCapturedAmount()).isNull();
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(allocationsFor(booking))
                .isNotEmpty()
                .allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
        assertThat(outboxCount("BOOKING_CONFIRMED", bookingId)).isZero();
        assertThat(outboxCount("PAYMENT_SUCCEEDED", attemptId)).isZero();

        sendWebhook(capturedBody(attempt, "evt_captured_1", paymentId, epochNow()), "evt_captured_1")
                .andExpect(status().isOk());

        var afterCaptured = paymentAttemptRepository.findById(attemptId).orElseThrow();
        assertThat(afterCaptured.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(afterCaptured.getDisposition()).isEqualTo(PaymentDisposition.APPLIED_TO_BOOKING);
        assertThat(afterCaptured.getProviderPaymentId()).isEqualTo(paymentId);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(allocationsFor(booking))
                .allMatch(a -> a.getState() == TripSeatAllocationState.BOOKED);
        assertThat(outboxCount("BOOKING_CONFIRMED", bookingId)).isEqualTo(1);
        assertThat(outboxCount("PAYMENT_SUCCEEDED", attemptId)).isEqualTo(1);

        sendWebhook(authorizedBody(attempt, "evt_authorized_replay", paymentId, epochNow() - 120),
                        "evt_authorized_replay")
                .andExpect(status().isOk());
        sendWebhook(authorizedBody(attempt, "evt_authorized_1", paymentId, epochNow()), "evt_authorized_1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        var afterReplay = paymentAttemptRepository.findById(attemptId).orElseThrow();
        assertThat(afterReplay.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(afterReplay.getDisposition()).isEqualTo(PaymentDisposition.APPLIED_TO_BOOKING);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxCount("BOOKING_CONFIRMED", bookingId)).isEqualTo(1);
        assertThat(outboxCount("PAYMENT_SUCCEEDED", attemptId)).isEqualTo(1);
    }

    @Test
    void orderPaidConfirmsOnceAndAuthorizedCannotDowngrade() throws Exception {
        CreatedBooking booking = createPendingBooking();
        JsonNode attempt = initiate(booking, "order-paid-flow");
        UUID attemptId = UUID.fromString(attempt.get("paymentAttemptId").asText());
        UUID bookingId = booking.bookingId();
        String paymentId = "pay_order_paid_1";

        sendWebhook(orderPaidBody(attempt, "evt_order_paid_1", paymentId, epochNow()), "evt_order_paid_1")
                .andExpect(status().isOk());

        var afterPaid = paymentAttemptRepository.findById(attemptId).orElseThrow();
        assertThat(afterPaid.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(afterPaid.getDisposition()).isEqualTo(PaymentDisposition.APPLIED_TO_BOOKING);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxCount("BOOKING_CONFIRMED", bookingId)).isEqualTo(1);

        sendWebhook(authorizedBody(attempt, "evt_auth_after_order_paid", paymentId, epochNow() - 30),
                        "evt_auth_after_order_paid")
                .andExpect(status().isOk());
        sendWebhook(orderPaidBody(attempt, "evt_order_paid_1", paymentId, epochNow()), "evt_order_paid_1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        assertThat(paymentAttemptRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxCount("BOOKING_CONFIRMED", bookingId)).isEqualTo(1);
        assertThat(outboxCount("PAYMENT_SUCCEEDED", attemptId)).isEqualTo(1);
    }

    @Test
    void webhookSuccessFailureUnknownAndOutOfOrderEvents() throws Exception {
        JsonNode success = initiate(createPendingBooking(), "events-success");
        sendWebhook(capturedBody(success, "evt_ok", "pay_ok", epochNow()), "evt_ok")
                .andExpect(status().isOk());
        sendWebhook(authorizedBody(success, "evt_old_auth", "pay_ok", epochNow() - 60), "evt_old_auth")
                .andExpect(status().isOk());
        var succeeded = paymentAttemptRepository.findById(
                UUID.fromString(success.get("paymentAttemptId").asText())).orElseThrow();
        assertThat(succeeded.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);

        JsonNode failed = initiate(createPendingBooking(), "events-fail");
        sendWebhook(failedBody(failed, "evt_fail", "pay_fail", epochNow()), "evt_fail")
                .andExpect(status().isOk());
        assertThat(paymentAttemptRepository.findById(
                UUID.fromString(failed.get("paymentAttemptId").asText())).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(bookingRepository.findById(UUID.fromString(failed.get("bookingId").asText()))
                .orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);

        JsonNode unknown = initiate(createPendingBooking(), "events-unknown");
        String unknownBody = """
                {"event":"invoice.paid","payload":{"payment":{"entity":{"id":"pay_x","order_id":"%s","amount":90000,"currency":"INR"}}}}
                """.formatted(unknown.get("providerOrderId").asText()).trim();
        sendWebhook(unknownBody, "evt_unknown").andExpect(status().isOk());
        assertThat(paymentAttemptRepository.findById(
                UUID.fromString(unknown.get("paymentAttemptId").asText())).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void latePaymentDoesNotResurrectExpiredOrCancelledBookings() throws Exception {
        CreatedBooking expired = createPendingBooking();
        JsonNode expiredAttempt = initiate(expired, "late-expired");
        forcePaymentExpiresAt(expired.bookingId(), Instant.now().minusSeconds(5));
        bookingExpiryService.expireDueBookings(Instant.now());
        sendWebhook(capturedBody(expiredAttempt, "evt_late_exp", "pay_late_exp", epochNow()), "evt_late_exp")
                .andExpect(status().isOk());
        assertThat(bookingRepository.findById(expired.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(paymentAttemptRepository.findById(
                UUID.fromString(expiredAttempt.get("paymentAttemptId").asText())).orElseThrow().getDisposition())
                .isEqualTo(PaymentDisposition.REQUIRES_RESOLUTION);
        assertThat(allocationsFor(expired)).allMatch(a -> a.getState() == TripSeatAllocationState.RELEASED);

        CreatedBooking cancelled = createPendingBooking();
        JsonNode cancelledAttempt = initiate(cancelled, "late-cancel");
        bookingLifecycleService.cancelUnpaidBooking(cancelled.bookingId());
        sendWebhook(capturedBody(cancelledAttempt, "evt_late_can", "pay_late_can", epochNow()), "evt_late_can")
                .andExpect(status().isOk());
        assertThat(bookingRepository.findById(cancelled.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(paymentAttemptRepository.findById(
                UUID.fromString(cancelledAttempt.get("paymentAttemptId").asText())).orElseThrow().getDisposition())
                .isEqualTo(PaymentDisposition.REQUIRES_RESOLUTION);
        assertThat(allocationsFor(cancelled)).allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
    }

    @Test
    void concurrentWebhookCheckoutExpiryAndCancelRemainConsistent() throws Exception {
        CreatedBooking duplicate = createPendingBooking();
        JsonNode dupAttempt = initiate(duplicate, "conc-dup");
        String dupBody = capturedBody(dupAttempt, "evt_dup", "pay_dup", epochNow());
        runConcurrent(
                () -> sendWebhook(dupBody, "evt_dup").andReturn(),
                () -> sendWebhook(dupBody, "evt_dup").andReturn());
        assertThat(bookingRepository.findById(duplicate.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxCount("BOOKING_CONFIRMED", duplicate.bookingId())).isEqualTo(1);

        CreatedBooking mixed = createPendingBooking();
        JsonNode mixedAttempt = initiate(mixed, "conc-mix");
        String orderId = mixedAttempt.get("providerOrderId").asText();
        String paymentId = "pay_mix";
        String signature = hmac(orderId + "|" + paymentId, KEY_SECRET);
        UUID mixedAttemptId = UUID.fromString(mixedAttempt.get("paymentAttemptId").asText());
        runConcurrent(
                () -> sendWebhook(capturedBody(mixedAttempt, "evt_mix", paymentId, epochNow()), "evt_mix").andReturn(),
                () -> mockMvc.perform(post("/api/v1/payments/{id}/checkout", mixedAttemptId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(checkoutBody(paymentId, orderId, signature)))
                        .andReturn());
        assertThat(bookingRepository.findById(mixed.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paymentAttemptRepository.findById(mixedAttemptId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCEEDED);

        CreatedBooking expiryRace = createPendingBooking();
        JsonNode expiryAttempt = initiate(expiryRace, "conc-exp");
        forcePaymentExpiresAt(expiryRace.bookingId(), Instant.now().minusSeconds(2));
        runConcurrent(
                () -> sendWebhook(capturedBody(expiryAttempt, "evt_exp_race", "pay_exp_race", epochNow()), "evt_exp_race")
                        .andReturn(),
                () -> bookingExpiryService.expireDueBookings(Instant.now()));
        BookingStatus expiryFinal = bookingRepository.findById(expiryRace.bookingId()).orElseThrow().getStatus();
        assertThat(expiryFinal).isIn(BookingStatus.CONFIRMED, BookingStatus.EXPIRED);
        var expiryPayment = paymentAttemptRepository.findById(
                UUID.fromString(expiryAttempt.get("paymentAttemptId").asText())).orElseThrow();
        if (expiryFinal == BookingStatus.CONFIRMED) {
            assertThat(expiryPayment.getDisposition()).isEqualTo(PaymentDisposition.APPLIED_TO_BOOKING);
        } else {
            assertThat(expiryPayment.getStatus()).isIn(PaymentStatus.SUCCEEDED, PaymentStatus.PENDING);
            if (expiryPayment.getStatus() == PaymentStatus.SUCCEEDED) {
                assertThat(expiryPayment.getDisposition()).isEqualTo(PaymentDisposition.REQUIRES_RESOLUTION);
            }
        }

        CreatedBooking cancelRace = createPendingBooking();
        JsonNode cancelAttempt = initiate(cancelRace, "conc-can");
        runConcurrent(
                () -> sendWebhook(capturedBody(cancelAttempt, "evt_can_race", "pay_can_race", epochNow()), "evt_can_race")
                        .andReturn(),
                () -> bookingLifecycleService.cancelUnpaidBooking(cancelRace.bookingId()));
        BookingStatus cancelFinal = bookingRepository.findById(cancelRace.bookingId()).orElseThrow().getStatus();
        assertThat(cancelFinal).isIn(BookingStatus.CONFIRMED, BookingStatus.CANCELLED);
    }

    @Test
    void refundsRequireConfirmedCancellationAndRemainIdempotent() throws Exception {
        JsonNode attempt = initiate(createPendingBooking(), "refund-pay");
        sendWebhook(capturedBody(attempt, "evt_refund_pay", "pay_refund", epochNow()), "evt_refund_pay")
                .andExpect(status().isOk());
        UUID attemptId = UUID.fromString(attempt.get("paymentAttemptId").asText());
        UUID bookingId = UUID.fromString(attempt.get("bookingId").asText());
        BigDecimal captured = paymentAttemptRepository.findById(attemptId).orElseThrow().getCapturedAmount();

        mockMvc.perform(post("/api/v1/payments/{id}/refunds", attemptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "orphan-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1.00,\"reason\":\"TEST_REFUND\"}"))
                .andExpect(status().isConflict());

        JsonNode cancel = read(mockMvc.perform(post("/api/v1/bookings/{id}/cancel", bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed plans\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.policyCode").value("CONFIRMED_FULL_REFUND_CUSTOMER_CANCELLATION_V1"))
                .andExpect(jsonPath("$.refundableAmount").value(captured.doubleValue()))
                .andReturn());
        assertThat(GATEWAY.lastRefundAmountPaise()).isEqualTo(captured.movePointRight(2).longValueExact());
        assertThat(GATEWAY.uniqueRefundCount()).isEqualTo(1);

        String cancelKey = "booking-cancel-" + cancel.get("cancellationId").asText();
        mockMvc.perform(post("/api/v1/payments/{id}/refunds", attemptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", cancelKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(GATEWAY.uniqueRefundCount()).isEqualTo(1);

        String refundBody = refundProcessedBody(
                attempt,
                refundRepository.findByPaymentAttemptIdOrderByCreatedAtDesc(attemptId).get(0).getProviderRefundId(),
                "pay_refund");
        sendWebhook(refundBody, "evt_refund_dup").andExpect(status().isOk());
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);

        CreatedBooking timeoutBooking = createPendingBooking();
        JsonNode timeoutAttempt = initiate(timeoutBooking, "refund-timeout-pay");
        sendWebhook(capturedBody(timeoutAttempt, "evt_to_pay", "pay_to", epochNow()), "evt_to_pay")
                .andExpect(status().isOk());
        UUID timeoutAttemptId = UUID.fromString(timeoutAttempt.get("paymentAttemptId").asText());
        GATEWAY.delayRefunds = true;
        JsonNode timeoutCancel = read(mockMvc.perform(post("/api/v1/bookings/{id}/cancel", timeoutBooking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("REFUND_PENDING"))
                .andReturn());
        GATEWAY.delayRefunds = false;
        String timeoutKey = "booking-cancel-" + timeoutCancel.get("cancellationId").asText();
        mockMvc.perform(post("/api/v1/payments/{id}/refunds", timeoutAttemptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", timeoutKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerRefundId").isNotEmpty());
        assertThat(GATEWAY.uniqueRefundCount()).isEqualTo(2);
    }

    @Test
    void confirmedCancellationReleasesInventoryCancelsTicketAndRejectsAfterDeparture() throws Exception {
        CreatedBooking booking = createPendingBooking();
        JsonNode attempt = initiate(booking, "cancel-happy");
        sendWebhook(capturedBody(attempt, "evt_cancel_happy", "pay_cancel_happy", epochNow()), "evt_cancel_happy")
                .andExpect(status().isOk());

        JsonNode ticket = read(mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn());
        UUID seat = booking.seatIds().get(0);
        assertThat(seatAvailabilityService.getSeatAvailability(booking.tripId(), 1, 3).stream()
                .filter(result -> result.inventoryId().equals(seat))
                .findFirst()
                .orElseThrow()
                .journeyAvailability()).isEqualTo(JourneySeatAvailability.UNAVAILABLE);

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + createOtherCustomer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Plans changed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.booking.items[0].status").value("CANCELLED"));

        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(booking.holdId()))
                .allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
        assertThat(ticketRepository.findById(UUID.fromString(ticket.get("ticketId").asText()))
                .orElseThrow()
                .getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(seatAvailabilityService.getSeatAvailability(booking.tripId(), 1, 3).stream()
                .filter(result -> result.inventoryId().equals(seat))
                .findFirst()
                .orElseThrow()
                .journeyAvailability()).isEqualTo(JourneySeatAvailability.AVAILABLE);
        assertThat(cancellationRepository.count()).isEqualTo(1);
        assertThat(refundRepository.count()).isEqualTo(1);

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        assertThat(cancellationRepository.count()).isEqualTo(1);
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED);

        // Existing cancelled ticket may be returned idempotently; it must not become ACTIVE again.
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        CreatedBooking noTicket = createPendingBooking();
        JsonNode noTicketAttempt = initiate(noTicket, "cancel-no-ticket");
        sendWebhook(capturedBody(noTicketAttempt, "evt_no_ticket", "pay_no_ticket", epochNow()), "evt_no_ticket")
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", noTicket.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("REFUND_PENDING"));
        assertThat(ticketRepository.findByBookingId(noTicket.bookingId())).isEmpty();
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", noTicket.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isConflict());

        CreatedBooking departedBooking = createPendingBooking();
        JsonNode departedAttempt = initiate(departedBooking, "cancel-departed");
        sendWebhook(capturedBody(departedAttempt, "evt_departed", "pay_departed", epochNow()), "evt_departed")
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE trips SET status = 'DEPARTED' WHERE id = ?", departedBooking.tripId());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", departedBooking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        CreatedBooking completedBooking = createPendingBooking();
        JsonNode completedAttempt = initiate(completedBooking, "cancel-completed");
        sendWebhook(capturedBody(completedAttempt, "evt_completed", "pay_completed", epochNow()), "evt_completed")
                .andExpect(status().isOk());
        jdbcTemplate.update("UPDATE trips SET status = 'COMPLETED' WHERE id = ?", completedBooking.tripId());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", completedBooking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        CreatedBooking pastDeparture = createPendingBooking();
        JsonNode pastAttempt = initiate(pastDeparture, "cancel-past");
        sendWebhook(capturedBody(pastAttempt, "evt_past", "pay_past", epochNow()), "evt_past")
                .andExpect(status().isOk());
        Instant past = Instant.now().minusSeconds(60);
        jdbcTemplate.update(
                """
                        UPDATE trips
                        SET scheduled_departure_at = ?,
                            scheduled_arrival_at = ?,
                            booking_opens_at = ?,
                            booking_closes_at = ?,
                            service_date = ?
                        WHERE id = ?
                        """,
                java.sql.Timestamp.from(past),
                java.sql.Timestamp.from(past.plusSeconds(3600)),
                java.sql.Timestamp.from(past.minusSeconds(7 * 24 * 3600)),
                java.sql.Timestamp.from(past.minusSeconds(60)),
                java.sql.Date.valueOf(past.atZone(java.time.ZoneOffset.UTC).toLocalDate()),
                pastDeparture.tripId());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", pastDeparture.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentConfirmedCancellationsProduceOneRefundAndOutOfOrderRefundEventsAreSafe() throws Exception {
        CreatedBooking booking = createPendingBooking();
        JsonNode attempt = initiate(booking, "cancel-race");
        sendWebhook(capturedBody(attempt, "evt_cancel_race", "pay_cancel_race", epochNow()), "evt_cancel_race")
                .andExpect(status().isOk());
        UUID attemptId = UUID.fromString(attempt.get("paymentAttemptId").asText());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        try {
            Future<?> a = submit(executor, ready, start, () -> {
                mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isOk());
                success.incrementAndGet();
            });
            Future<?> b = submit(executor, ready, start, () -> {
                mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andExpect(status().isOk());
                success.incrementAndGet();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(success.get()).isEqualTo(2);
        assertThat(cancellationRepository.count()).isEqualTo(1);
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(GATEWAY.uniqueRefundCount()).isEqualTo(1);

        String providerRefundId = refundRepository.findByPaymentAttemptIdOrderByCreatedAtDesc(attemptId)
                .get(0)
                .getProviderRefundId();
        sendWebhook(refundProcessedBody(attempt, providerRefundId, "pay_cancel_race"), "evt_refund_ok")
                .andExpect(status().isOk());
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);

        sendWebhook(refundProcessedBody(attempt, providerRefundId, "pay_cancel_race"), "evt_refund_dup2")
                .andExpect(status().isOk());
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);

        String failedBody = """
                {"event":"refund.failed","payload":{"refund":{"entity":{"id":"%s","payment_id":"%s","amount":%d,"currency":"INR","status":"failed"}}}}
                """.formatted(providerRefundId, "pay_cancel_race", paise(attempt.get("amount").decimalValue())).trim();
        sendWebhook(failedBody, "evt_refund_ooo_fail").andExpect(status().isOk());
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);
        assertThat(refundRepository.findByPaymentAttemptIdOrderByCreatedAtDesc(attemptId).get(0).getStatus())
                .isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(booking.holdId()))
                .allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);

        CreatedBooking failBooking = createPendingBooking();
        JsonNode failAttempt = initiate(failBooking, "cancel-fail-refund");
        sendWebhook(capturedBody(failAttempt, "evt_fail_refund", "pay_fail_refund", epochNow()), "evt_fail_refund")
                .andExpect(status().isOk());
        GATEWAY.failRefunds = true;
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", failBooking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("REFUND_PENDING"));
        GATEWAY.failRefunds = false;
        assertThat(bookingRepository.findById(failBooking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUND_PENDING);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(failBooking.holdId()))
                .allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
        assertThat(refundRepository.findByPaymentAttemptIdOrderByCreatedAtDesc(
                        UUID.fromString(failAttempt.get("paymentAttemptId").asText()))
                .get(0)
                .getStatus()).isEqualTo(RefundStatus.FAILED);
    }

    @Test
    void refundRetryWorkerIsDurableAndDoesNotDuplicateProviderRefunds() throws Exception {
        refundRetryProperties.setAfterCommitEnabled(false);
        UUID userId = userRepository.findByEmailIgnoreCase(CUSTOMER_EMAIL).orElseThrow().getId();

        CreatedBooking unpaid = createPendingBooking();
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", unpaid.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(unpaid.bookingId())).isEmpty();

        CreatedBooking rollbackBooking = createPendingBooking();
        JsonNode rollbackAttempt = initiate(rollbackBooking, "retry-rollback");
        sendWebhook(capturedBody(rollbackAttempt, "evt_retry_rollback", "pay_retry_rollback", epochNow()),
                "evt_retry_rollback").andExpect(status().isOk());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            bookingCancellationService.cancelOwned(
                    userId, rollbackBooking.bookingId(), new CancelBookingRequest(null));
            status.setRollbackOnly();
        });
        assertThat(bookingRepository.findById(rollbackBooking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(rollbackBooking.bookingId())).isEmpty();

        CreatedBooking booking = createPendingBooking();
        JsonNode attempt = initiate(booking, "retry-worker");
        sendWebhook(capturedBody(attempt, "evt_retry_worker", "pay_retry_worker", epochNow()), "evt_retry_worker")
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        Refund requested = refundRepository.findByBookingIdOrderByCreatedAtDesc(booking.bookingId()).get(0);
        assertThat(requested.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(requested.getProviderRefundId()).isNull();
        assertThat(requested.getAttemptCount()).isZero();
        assertThat(requested.getIdempotencyKey()).isEqualTo(
                "booking-cancel-" + cancellationRepository.findByBookingId(booking.bookingId()).orElseThrow().getId());
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUND_PENDING);
        assertThat(GATEWAY.refundHttpCalls()).isZero();

        Instant now = Instant.now();
        assertThat(refundRetryProcessor.tryClaimDueRefund(requested.getId(), now)).contains(requested.getId());
        assertThat(refundRetryProcessor.tryClaimDueRefund(requested.getId(), now)).isEmpty();
        Refund leased = refundRepository.findById(requested.getId()).orElseThrow();
        assertThat(leased.getAttemptCount()).isEqualTo(1);
        assertThat(leased.getNextRetryAt()).isAfter(now);

        jdbcTemplate.update("UPDATE refunds SET next_retry_at = NULL WHERE id = ?", requested.getId());
        RefundRetryService.RefundRetryResult processed = refundRetryService.processDueRefunds();
        assertThat(processed.completed()).isGreaterThanOrEqualTo(1);
        Refund succeeded = refundRepository.findById(requested.getId()).orElseThrow();
        assertThat(succeeded.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(GATEWAY.lastRefundIdempotencyKey()).isEqualTo(requested.getId().toString());
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(booking.bookingId())).hasSize(1);

        refundRetryService.processDueRefunds();
        assertThat(GATEWAY.uniqueRefundCount()).isEqualTo(1);
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);

        CreatedBooking failed = createPendingBooking();
        JsonNode failedAttempt = initiate(failed, "retry-failed");
        sendWebhook(capturedBody(failedAttempt, "evt_retry_failed", "pay_retry_failed", epochNow()), "evt_retry_failed")
                .andExpect(status().isOk());
        GATEWAY.failRefunds = true;
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", failed.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        int callsBefore = GATEWAY.refundHttpCalls();
        refundRetryService.processDueRefunds();
        Refund failedRefund = refundRepository.findByBookingIdOrderByCreatedAtDesc(failed.bookingId()).get(0);
        assertThat(failedRefund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(failedRefund.getNextRetryAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(bookingRepository.findById(failed.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUND_PENDING);
        refundRetryService.processDueRefunds();
        assertThat(GATEWAY.refundHttpCalls()).isEqualTo(callsBefore + 1);
        GATEWAY.failRefunds = false;
        jdbcTemplate.update("UPDATE refunds SET next_retry_at = NULL WHERE id = ?", failedRefund.getId());
        refundRetryService.processDueRefunds();
        assertThat(refundRepository.findById(failedRefund.getId()).orElseThrow().getStatus())
                .isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(bookingRepository.findById(failed.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);

        CreatedBooking race = createPendingBooking();
        JsonNode raceAttempt = initiate(race, "retry-race");
        sendWebhook(capturedBody(raceAttempt, "evt_retry_race", "pay_retry_race", epochNow()), "evt_retry_race")
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", race.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        jdbcTemplate.update(
                "UPDATE refunds SET next_retry_at = NULL WHERE booking_id = ?", race.bookingId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = submit(executor, ready, start, () -> refundRetryService.processDueRefunds());
            Future<?> b = submit(executor, ready, start, () -> refundRetryService.processDueRefunds());
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(race.bookingId())).hasSize(1);
        assertThat(bookingRepository.findById(race.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);

        CreatedBooking webhook = createPendingBooking();
        JsonNode webhookAttempt = initiate(webhook, "retry-webhook");
        sendWebhook(capturedBody(webhookAttempt, "evt_retry_wh_pay", "pay_retry_wh", epochNow()),
                "evt_retry_wh_pay").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", webhook.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        Refund openWebhookRefund = refundRepository.findByBookingIdOrderByCreatedAtDesc(webhook.bookingId()).get(0);
        assertThat(openWebhookRefund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        String refundWebhook = refundProcessedBody(webhookAttempt, "rfnd_crash_recovery", "pay_retry_wh");
        sendWebhook(refundWebhook, "evt_retry_wh_processed").andExpect(status().isOk());
        assertThat(refundRepository.findById(openWebhookRefund.getId()).orElseThrow().getStatus())
                .isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(bookingRepository.findById(webhook.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);
        sendWebhook(refundWebhook, "evt_retry_wh_processed_dup").andExpect(status().isOk());
        assertThat(bookingRepository.findById(webhook.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(webhook.bookingId())).hasSize(1);
        refundRetryService.processDueRefunds();
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(webhook.bookingId())).hasSize(1);

        refundRetryProperties.setAfterCommitEnabled(true);
        CreatedBooking fast = createPendingBooking();
        JsonNode fastAttempt = initiate(fast, "retry-after-commit");
        sendWebhook(capturedBody(fastAttempt, "evt_retry_ac", "pay_retry_ac", epochNow()), "evt_retry_ac")
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", fast.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(fast.bookingId())).hasSize(1);
        refundRetryService.processDueRefunds();
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(fast.bookingId())).hasSize(1);
        assertThat(bookingRepository.findById(fast.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);
    }

    @Test
    void initiatingRecoveryReusesProviderIdempotencyAndDoesNotDuplicateOrders() throws Exception {
        CreatedBooking booking = createPendingBooking();
        GATEWAY.delayOrders = true;
        mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "recover-key"))
                .andExpect(status().isServiceUnavailable());
        GATEWAY.delayOrders = false;

        JsonNode recovered = read(mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "recover-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.providerOrderId").isNotEmpty())
                .andReturn());

        UUID attemptId = UUID.fromString(recovered.get("paymentAttemptId").asText());
        jdbcTemplate.update("""
                UPDATE payment_attempts
                   SET status = 'INITIATING',
                       provider_order_id = NULL,
                       checkout_reference = NULL,
                       provider_status = NULL
                 WHERE id = ?
                """, attemptId);

        mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", "recover-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentAttemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.providerOrderId").value(recovered.get("providerOrderId").asText()));
        assertThat(GATEWAY.uniqueOrderCount()).isEqualTo(1);
    }

    private JsonNode initiate(CreatedBooking booking, String key) throws Exception {
        return read(mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions sendWebhook(String body, String eventId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/payments/webhooks/RAZORPAY")
                .header("X-Razorpay-Signature", hmac(body, WEBHOOK_SECRET))
                .header("X-Razorpay-Event-Id", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String capturedBody(JsonNode attempt, String eventId, String paymentId, long createdAt) {
        return """
                {"id":"%s","event":"payment.captured","created_at":%d,"payload":{"payment":{"entity":{"id":"%s","order_id":"%s","amount":%d,"currency":"INR","status":"captured","notes":{"merchant_reference":"%s"}}}}}
                """.formatted(
                eventId,
                createdAt,
                paymentId,
                attempt.get("providerOrderId").asText(),
                paise(attempt.get("amount").decimalValue()),
                attempt.get("merchantReference").asText()).trim();
    }

    private String authorizedBody(JsonNode attempt, String eventId, String paymentId, long createdAt) {
        return """
                {"id":"%s","event":"payment.authorized","created_at":%d,"payload":{"payment":{"entity":{"id":"%s","order_id":"%s","amount":%d,"currency":"INR","status":"authorized","notes":{"merchant_reference":"%s"}}}}}
                """.formatted(
                eventId,
                createdAt,
                paymentId,
                attempt.get("providerOrderId").asText(),
                paise(attempt.get("amount").decimalValue()),
                attempt.get("merchantReference").asText()).trim();
    }

    private String orderPaidBody(JsonNode attempt, String eventId, String paymentId, long createdAt) {
        long amountPaise = paise(attempt.get("amount").decimalValue());
        String orderId = attempt.get("providerOrderId").asText();
        String merchantReference = attempt.get("merchantReference").asText();
        return """
                {"id":"%s","event":"order.paid","created_at":%d,"payload":{"order":{"entity":{"id":"%s","amount":%d,"currency":"INR","status":"paid","notes":{"merchant_reference":"%s"}}},"payment":{"entity":{"id":"%s","order_id":"%s","amount":%d,"currency":"INR","status":"captured","notes":{"merchant_reference":"%s"}}}}}
                """.formatted(
                eventId,
                createdAt,
                orderId,
                amountPaise,
                merchantReference,
                paymentId,
                orderId,
                amountPaise,
                merchantReference).trim();
    }

    private String failedBody(JsonNode attempt, String eventId, String paymentId, long createdAt) {
        return """
                {"id":"%s","event":"payment.failed","created_at":%d,"payload":{"payment":{"entity":{"id":"%s","order_id":"%s","amount":%d,"currency":"INR","status":"failed","error_code":"PAYMENT_FAILED","notes":{"merchant_reference":"%s"}}}}}
                """.formatted(
                eventId,
                createdAt,
                paymentId,
                attempt.get("providerOrderId").asText(),
                paise(attempt.get("amount").decimalValue()),
                attempt.get("merchantReference").asText()).trim();
    }

    private String refundProcessedBody(JsonNode attempt, String refundId, String paymentId) {
        return """
                {"event":"refund.processed","payload":{"refund":{"entity":{"id":"%s","payment_id":"%s","amount":%d,"currency":"INR","status":"processed"}}}}
                """.formatted(refundId, paymentId, paise(attempt.get("amount").decimalValue())).trim();
    }

    private static String checkoutBody(String paymentId, String orderId, String signature) {
        return """
                {"razorpay_payment_id":"%s","razorpay_order_id":"%s","razorpay_signature":"%s"}
                """.formatted(paymentId, orderId, signature);
    }

    private long outboxCount(String eventType, UUID aggregateId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Integer.class,
                eventType,
                aggregateId);
        return count == null ? 0 : count;
    }

    private List<in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocation> allocationsFor(
            CreatedBooking created) {
        return allocationRepository.findByHoldIdOrderByCreatedAtAsc(created.holdId());
    }

    private void forcePaymentExpiresAt(UUID bookingId, Instant expiresAt) {
        jdbcTemplate.update(
                "UPDATE bookings SET payment_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(expiresAt),
                bookingId);
    }

    private String createOtherCustomer() throws Exception {
        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User other = new User("razorpay-b@example.test", "+919944490002", "Other", "Customer");
        other.setPasswordHash(passwordEncoder.encode(PASSWORD));
        other = userRepository.saveAndFlush(other);
        userRoleRepository.saveAndFlush(new UserRole(other, customerRole));
        return loginToken(other.getEmail());
    }

    private void runConcurrent(CheckedAction first, CheckedAction second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = submit(executor, ready, start, first);
            Future<?> b = submit(executor, ready, start, second);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Future<?> submit(
            ExecutorService executor, CountDownLatch ready, CountDownLatch start, CheckedAction action) {
        return executor.submit(() -> {
            ready.countDown();
            start.await();
            action.run();
            return null;
        });
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private CreatedBooking createPendingBooking() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        return createPendingBooking("RZBUS-" + n, "RZRT-" + n);
    }

    private CreatedBooking createPendingBooking(String registration, String routeCode) throws Exception {
        TripFixture trip = createTrip(registration, routeCode);
        List<UUID> seats = List.of(trip.availableSeatIds().get(0));
        JsonNode hold = createHold(trip, seats);
        UUID holdId = UUID.fromString(hold.get("holdId").asText());
        MvcResult created = mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holdId":"%s",
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "idempotencyKey":"book-%s",
                                  "passengers":[{"seatInventoryId":"%s","fullName":"Rider One","age":30}]
                                }
                                """.formatted(
                                holdId, trip.stopId(1), trip.stopId(3), registration, seats.get(0))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        return new CreatedBooking(
                UUID.fromString(body.get("bookingId").asText()),
                trip.tripId(),
                holdId,
                seats);
    }

    private JsonNode createHold(TripFixture trip, List<UUID> seats) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originStopId":"%s","destinationStopId":"%s","seatInventoryIds":["%s"]}
                                """.formatted(trip.stopId(1), trip.stopId(3), seats.get(0))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private TripFixture createTrip(String registration, String routeCode) throws Exception {
        UUID operatorId = createMaster("operators", "{\"legalName\":\"Op %s\",\"displayName\":\"Co %s\"}"
                .formatted(registration, registration));
        UUID busTypeId = createMaster("bus-types", "{\"code\":\"T%s\",\"displayName\":\"Type %s\"}"
                .formatted(registration, registration));
        UUID layoutId = createSeatLayout(operatorId, registration);
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
                                departure.minusSeconds(7 * 24 * 3600), departure.minusSeconds(3600))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
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
        return new TripFixture(UUID.fromString(body.get("id").asText()), stopIdsBySequence, available);
    }

    private UUID createSeatLayout(UUID operatorId, String registration) throws Exception {
        return createMaster("seat-layouts", """
                {
                  "operatorId":"%s","name":"Layout %s","version":1,"deckCount":1,"rowCount":1,"columnCount":2,
                  "seats":[
                    {"seatNumber":"S1","deckNumber":1,"rowNumber":1,"columnNumber":1,"seatType":"SEATER","sellable":true},
                    {"seatNumber":"S2","deckNumber":1,"rowNumber":1,"columnNumber":2,"seatType":"SEATER","sellable":true}
                  ]
                }
                """.formatted(operatorId, registration));
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

    private static long paise(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static long epochNow() {
        return Instant.now().getEpochSecond();
    }

    private static String hmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private record CreatedBooking(UUID bookingId, UUID tripId, UUID holdId, List<UUID> seatIds) {
    }

    private record TripFixture(UUID tripId, List<UUID> stopIdsBySequence, List<UUID> availableSeatIds) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }

    static final class RazorpayFakeGateway {
        private final HttpServer server;
        private final Map<String, String> orders = new ConcurrentHashMap<>();
        private final Map<String, String> refunds = new ConcurrentHashMap<>();
        private final AtomicInteger orderHttpCalls = new AtomicInteger();
        private final AtomicInteger refundHttpCalls = new AtomicInteger();
        private final AtomicInteger totalHttpCalls = new AtomicInteger();
        private final AtomicLong lastOrderAmountPaise = new AtomicLong();
        private final AtomicLong lastRefundAmountPaise = new AtomicLong();
        private final AtomicReference<String> lastRefundIdempotencyKey = new AtomicReference<>();
        private final AtomicReference<String> lastAuth = new AtomicReference<>();
        volatile boolean delayOrders;
        volatile boolean delayRefunds;
        volatile boolean failRefunds;
        volatile boolean malformedOrders;
        volatile int orderStatus = 200;

        private RazorpayFakeGateway(HttpServer server) {
            this.server = server;
        }

        static RazorpayFakeGateway start() {
            try {
                HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                RazorpayFakeGateway gateway = new RazorpayFakeGateway(httpServer);
                httpServer.createContext("/", gateway::handle);
                httpServer.start();
                return gateway;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start Razorpay fake gateway", exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        void reset() {
            orders.clear();
            refunds.clear();
            orderHttpCalls.set(0);
            refundHttpCalls.set(0);
            totalHttpCalls.set(0);
            lastOrderAmountPaise.set(0);
            lastRefundAmountPaise.set(0);
            lastRefundIdempotencyKey.set(null);
            delayOrders = false;
            delayRefunds = false;
            failRefunds = false;
            malformedOrders = false;
            orderStatus = 200;
        }

        int uniqueOrderCount() {
            return orders.size();
        }

        int uniqueRefundCount() {
            return refunds.size();
        }

        int orderHttpCalls() {
            return orderHttpCalls.get();
        }

        int refundHttpCalls() {
            return refundHttpCalls.get();
        }

        int totalHttpCalls() {
            return totalHttpCalls.get();
        }

        long lastOrderAmountPaise() {
            return lastOrderAmountPaise.get();
        }

        long lastRefundAmountPaise() {
            return lastRefundAmountPaise.get();
        }

        String lastRefundIdempotencyKey() {
            return lastRefundIdempotencyKey.get();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            try {
                totalHttpCalls.incrementAndGet();
                lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                String path = exchange.getRequestURI().getPath();
                byte[] request = exchange.getRequestBody().readAllBytes();
                if (path.equals("/v1/orders")) {
                    handleOrder(exchange, request);
                    return;
                }
                if (path.contains("/refunds")) {
                    handleRefund(exchange, path, request);
                    return;
                }
                write(exchange, 404, "{\"error\":\"not_found\"}");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                write(exchange, 500, "{\"error\":\"interrupted\"}");
            }
        }

        private void handleOrder(com.sun.net.httpserver.HttpExchange exchange, byte[] request)
                throws IOException, InterruptedException {
            orderHttpCalls.incrementAndGet();
            String idempotency = header(exchange, "X-Razorpay-Idempotency-Key");
            String json = new String(request, StandardCharsets.UTF_8);
            lastOrderAmountPaise.set(readAmount(json));
            if (delayOrders) {
                orders.computeIfAbsent(idempotency, key -> "order_" + Integer.toHexString(key.hashCode()));
                Thread.sleep(3000);
            }
            if (orderStatus != 200) {
                write(exchange, orderStatus, "{\"error\":\"provider\"}");
                return;
            }
            if (malformedOrders) {
                write(exchange, 200, "{not-json");
                return;
            }
            String orderId = orders.computeIfAbsent(
                    idempotency, key -> "order_" + Integer.toHexString(key.hashCode()));
            write(exchange, 200, """
                    {"id":"%s","entity":"order","amount":%d,"currency":"INR","status":"created"}
                    """.formatted(orderId, lastOrderAmountPaise.get()));
        }

        private void handleRefund(com.sun.net.httpserver.HttpExchange exchange, String path, byte[] request)
                throws IOException, InterruptedException {
            refundHttpCalls.incrementAndGet();
            String idempotency = header(exchange, "X-Razorpay-Idempotency-Key");
            lastRefundIdempotencyKey.set(idempotency);
            lastRefundAmountPaise.set(readAmount(new String(request, StandardCharsets.UTF_8)));
            if (delayRefunds) {
                refunds.computeIfAbsent(idempotency, key -> "rfnd_" + Integer.toHexString(key.hashCode()));
                Thread.sleep(3000);
            }
            String refundId = refunds.computeIfAbsent(
                    idempotency, key -> "rfnd_" + Integer.toHexString(key.hashCode()));
            String paymentId = path.contains("/payments/")
                    ? path.substring(path.indexOf("/payments/") + 10, path.indexOf("/refunds"))
                    : "pay_unknown";
            if (failRefunds) {
                write(exchange, 200, """
                        {"id":"%s","entity":"refund","amount":%d,"payment_id":"%s","status":"failed"}
                        """.formatted(refundId, lastRefundAmountPaise.get(), paymentId));
                return;
            }
            write(exchange, 200, """
                    {"id":"%s","entity":"refund","amount":%d,"payment_id":"%s","status":"processed"}
                    """.formatted(refundId, lastRefundAmountPaise.get(), paymentId));
        }

        private static String header(com.sun.net.httpserver.HttpExchange exchange, String name) {
            String value = exchange.getRequestHeaders().getFirst(name);
            return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
        }

        private static long readAmount(String json) {
            int idx = json.indexOf("\"amount\"");
            if (idx < 0) {
                return 0;
            }
            StringBuilder digits = new StringBuilder();
            for (int i = json.indexOf(':', idx) + 1; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == ',' || ch == '}') {
                    break;
                }
                if (Character.isDigit(ch)) {
                    digits.append(ch);
                }
            }
            return digits.isEmpty() ? 0 : Long.parseLong(digits.toString());
        }

        private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
