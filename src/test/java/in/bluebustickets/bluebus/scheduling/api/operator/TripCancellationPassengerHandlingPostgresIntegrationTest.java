package in.bluebustickets.bluebus.scheduling.api.operator;

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

import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxProcessingResult;
import in.bluebustickets.bluebus.foundation.outbox.OutboxProcessorService;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedOperatorMember;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.payments.application.RefundRetryProperties;
import in.bluebustickets.bluebus.payments.application.RefundRetryService;
import in.bluebustickets.bluebus.payments.domain.PaymentDisposition;
import in.bluebustickets.bluebus.payments.domain.PaymentStatus;
import in.bluebustickets.bluebus.payments.domain.Refund;
import in.bluebustickets.bluebus.payments.domain.RefundStatus;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentProviderEventRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import in.bluebustickets.bluebus.scheduling.application.OperatorTripAdminService;
import in.bluebustickets.bluebus.scheduling.domain.TripSeatAllocationState;
import in.bluebustickets.bluebus.scheduling.repository.TripRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import static in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TripCancellationPassengerHandlingPostgresIntegrationTest {

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
        registry.add("blue-bus.outbox.processor.enabled", () -> "false");
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
    @Autowired private TestAccessTokenFactory tokens;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingCancellationRepository cancellationRepository;
    @Autowired private TripSeatAllocationRepository allocationRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private PaymentProviderEventRepository paymentProviderEventRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private TripRepository tripRepository;
    @Autowired private OutboxProcessorService outboxProcessorService;
    @Autowired private RefundRetryService refundRetryService;
    @Autowired private RefundRetryProperties refundRetryProperties;
    @Autowired private OperatorTripAdminService operatorTripAdminService;

    private IssuedUser platformAdmin;
    private IssuedUser customer;

    @BeforeEach
    void seed() {
        GATEWAY.reset();
        refundRetryProperties.setAfterCommitEnabled(true);
        ticketRepository.deleteAll();
        refundRepository.deleteAll();
        paymentProviderEventRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        outboxEventRepository.deleteAll();
        cancellationRepository.deleteAll();
        bookingRepository.deleteAll();
        platformAdmin = tokens.issuePlatformAdmin();
        customer = tokens.issueCustomer();
    }

    @Test
    void singleConfirmedBookingMovesToRefundPendingWithFullCapturedRefund() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        pay(booking, "single-pay");
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken()))
                .andExpect(status().isCreated());

        cancelTrip(booking.tripId());

        assertThat(tripRepository.findById(booking.tripId()).orElseThrow().getStatus().name())
                .isEqualTo("CANCELLED");
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED);
        assertThat(cancellationRepository.findByBookingId(booking.bookingId()).orElseThrow().getPolicyCode())
                .isEqualTo("TRIP_CANCELLED_FULL_REFUND_V1");
        assertThat(cancellationRepository.findByBookingId(booking.bookingId()).orElseThrow().getRequestedByUserId())
                .isEqualTo(platformAdmin.user().getId());
        assertThat(ticketRepository.findByBookingId(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(TicketStatus.CANCELLED);
        assertThat(allocationRepository.findByHoldIdOrderByCreatedAtAsc(booking.holdId()))
                .allMatch(a -> a.getState() == TripSeatAllocationState.CANCELLED);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(booking.bookingId())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE trip_id = ? AND status = 'CONFIRMED'",
                Integer.class,
                booking.tripId())).isZero();

        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        assertThat(cancellationRepository.count()).isEqualTo(1);
    }

    @Test
    void multipleConfirmedBookingsAreCancelledTogether() throws Exception {
        TripFixture trip = createTrip(4);
        CreatedBooking first = bookSeat(trip, trip.availableSeatIds().get(0), "m1");
        CreatedBooking second = bookSeat(trip, trip.availableSeatIds().get(1), "m2");
        pay(first, "m1-pay");
        pay(second, "m2-pay");

        cancelTrip(trip.tripId());

        assertThat(bookingRepository.findById(first.bookingId()).orElseThrow().getStatus())
                .isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED);
        assertThat(bookingRepository.findById(second.bookingId()).orElseThrow().getStatus())
                .isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE trip_id = ? AND status = 'CONFIRMED'",
                Integer.class,
                trip.tripId())).isZero();
        assertThat(refundRepository.count()).isEqualTo(2);
        assertThat(cancellationRepository.count()).isEqualTo(2);
    }

    @Test
    void mixOfConfirmedPendingExpiredAndRefundedIsHandledInOneCancel() throws Exception {
        TripFixture trip = createTrip(4);
        CreatedBooking confirmed = bookSeat(trip, trip.availableSeatIds().get(0), "mix-c");
        CreatedBooking pending = bookSeat(trip, trip.availableSeatIds().get(1), "mix-p");
        CreatedBooking expired = bookSeat(trip, trip.availableSeatIds().get(2), "mix-e");
        CreatedBooking refunded = bookSeat(trip, trip.availableSeatIds().get(3), "mix-r");
        pay(confirmed, "mix-pay");
        jdbcTemplate.update("UPDATE bookings SET status = 'EXPIRED' WHERE id = ?", expired.bookingId());
        jdbcTemplate.update("UPDATE bookings SET status = 'REFUNDED' WHERE id = ?", refunded.bookingId());

        cancelTrip(trip.tripId());

        assertThat(bookingRepository.findById(confirmed.bookingId()).orElseThrow().getStatus())
                .isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED);
        assertThat(bookingRepository.findById(pending.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(bookingRepository.findById(expired.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookingRepository.findById(refunded.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);
        assertThat(cancellationRepository.findByBookingId(pending.bookingId()).orElseThrow().getPolicyCode())
                .isEqualTo("TRIP_CANCELLED_UNPAID_V1");
        assertThat(cancellationRepository.findByBookingId(expired.bookingId())).isEmpty();
        assertThat(cancellationRepository.findByBookingId(refunded.bookingId())).isEmpty();
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(pending.bookingId())).isEmpty();
    }

    @Test
    void missingTicketIsValidAndDoesNotCreateOne() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        pay(booking, "no-ticket-pay");
        cancelTrip(booking.tripId());
        assertThat(ticketRepository.findByBookingId(booking.bookingId())).isEmpty();
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED);
    }

    @Test
    void repeatedTripCancellationIsIdempotent() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        pay(booking, "repeat-pay");
        cancelTrip(booking.tripId());
        mockMvc.perform(post("/api/v1/admin/trips/{id}/deactivate", booking.tripId())
                        .with(bearer(platformAdmin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(cancellationRepository.count()).isEqualTo(1);
        assertThat(refundRepository.count()).isEqualTo(1);
    }

    @Test
    void refundProviderFailureIsRecoveredByExistingRetry() throws Exception {
        refundRetryProperties.setAfterCommitEnabled(false);
        CreatedBooking booking = createPendingBooking(2);
        pay(booking, "retry-pay");
        cancelTrip(booking.tripId());
        Refund requested = refundRepository.findByBookingIdOrderByCreatedAtDesc(booking.bookingId()).get(0);
        assertThat(requested.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUND_PENDING);

        GATEWAY.failRefunds = true;
        refundRetryService.processDueRefunds();
        GATEWAY.failRefunds = false;
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUND_PENDING);

        jdbcTemplate.update("UPDATE refunds SET next_retry_at = NULL WHERE id = ?", requested.getId());
        RefundRetryService.RefundRetryResult recovered = refundRetryService.processDueRefunds();
        assertThat(recovered.completed()).isGreaterThanOrEqualTo(1);
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.REFUNDED);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtDesc(booking.bookingId())).hasSize(1);
        assertThat(GATEWAY.lastRefundIdempotencyKey()).isEqualTo(requested.getId().toString());
    }

    @Test
    void concurrentCustomerCancellationDoesNotCreateASecondRefund() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        pay(booking, "cust-race-pay");
        runConcurrent(
                () -> mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn(),
                () -> mockMvc.perform(post("/api/v1/admin/trips/{id}/deactivate", booking.tripId())
                                .with(bearer(platformAdmin.accessToken())))
                        .andReturn());
        assertThat(tripRepository.findById(booking.tripId()).orElseThrow().getStatus().name())
                .isEqualTo("CANCELLED");
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED);
        assertThat(cancellationRepository.count()).isEqualTo(1);
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE trip_id = ? AND status = 'CONFIRMED'",
                Integer.class,
                booking.tripId())).isZero();
    }

    @Test
    void concurrentPaymentConfirmationEitherRefundsOrRequiresResolution() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        JsonNode attempt = initiate(booking, "pay-race");
        runConcurrent(
                () -> sendWebhook(capturedBody(attempt, "evt_pay_race", "pay_race", epochNow()), "evt_pay_race")
                        .andReturn(),
                () -> mockMvc.perform(post("/api/v1/admin/trips/{id}/deactivate", booking.tripId())
                                .with(bearer(platformAdmin.accessToken())))
                        .andReturn());
        assertThat(tripRepository.findById(booking.tripId()).orElseThrow().getStatus().name())
                .isEqualTo("CANCELLED");
        BookingStatus status = bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus();
        assertThat(status).isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED, BookingStatus.CANCELLED);
        assertThat(status).isNotEqualTo(BookingStatus.CONFIRMED);
        if (status == BookingStatus.CANCELLED) {
            assertThat(paymentAttemptRepository.findByBookingIdOrderByCreatedAtDesc(booking.bookingId())
                    .get(0)
                    .getDisposition()).isEqualTo(PaymentDisposition.REQUIRES_RESOLUTION);
            assertThat(refundRepository.count()).isEqualTo(1);
            Refund compensation = refundRepository.findAll().get(0);
            assertThat(compensation.getIdempotencyKey()).startsWith("late-payment-");
        } else {
            assertThat(refundRepository.count()).isEqualTo(1);
            assertThat(refundRepository.findAll().get(0).getIdempotencyKey()).startsWith("booking-cancel-");
        }
    }

    @Test
    void concurrentRefundRetryDoesNotDuplicateRefunds() throws Exception {
        refundRetryProperties.setAfterCommitEnabled(false);
        CreatedBooking booking = createPendingBooking(2);
        pay(booking, "retry-race-pay");
        mockMvc.perform(post("/api/v1/bookings/{id}/cancel", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        runConcurrent(
                () -> refundRetryService.processDueRefunds(),
                () -> mockMvc.perform(post("/api/v1/admin/trips/{id}/deactivate", booking.tripId())
                                .with(bearer(platformAdmin.accessToken())))
                        .andReturn());
        assertThat(tripRepository.findById(booking.tripId()).orElseThrow().getStatus().name())
                .isEqualTo("CANCELLED");
        assertThat(refundRepository.count()).isEqualTo(1);
        assertThat(cancellationRepository.count()).isEqualTo(1);
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isIn(BookingStatus.REFUND_PENDING, BookingStatus.REFUNDED);
    }

    @Test
    void automaticTicketIssuanceRaceDoesNotLeaveConfirmedBookingOrRetryForever() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        pay(booking, "ticket-race-pay");
        OutboxEvent confirmed = outboxEventRepository.findAll().stream()
                .filter(e -> OutboxProcessorService.BOOKING_CONFIRMED.equals(e.getEventType()))
                .filter(e -> e.getAggregateId().equals(booking.bookingId()))
                .findFirst()
                .orElseThrow();
        runConcurrent(
                () -> outboxProcessorService.processPendingBookingConfirmed(),
                () -> mockMvc.perform(post("/api/v1/admin/trips/{id}/deactivate", booking.tripId())
                                .with(bearer(platformAdmin.accessToken())))
                        .andReturn());
        outboxProcessorService.processPendingBookingConfirmed();
        assertThat(tripRepository.findById(booking.tripId()).orElseThrow().getStatus().name())
                .isEqualTo("CANCELLED");
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isNotEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxEventRepository.findById(confirmed.getId()).orElseThrow().getPublishedAt()).isNotNull();
        ticketRepository.findByBookingId(booking.bookingId()).ifPresent(ticket ->
                assertThat(ticket.getStatus()).isEqualTo(TicketStatus.CANCELLED));
        OutboxProcessingResult leftover = outboxProcessorService.processPendingBookingConfirmed();
        assertThat(leftover.failures()).isZero();
    }

    @Test
    void latePaymentAfterTripCancellationRequiresResolution() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        JsonNode attempt = initiate(booking, "late-pay");
        cancelTrip(booking.tripId());
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        sendWebhook(capturedBody(attempt, "evt_late", "pay_late", epochNow()), "evt_late")
                .andExpect(status().isOk());
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(paymentAttemptRepository.findById(UUID.fromString(attempt.get("paymentAttemptId").asText()))
                .orElseThrow()
                .getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(paymentAttemptRepository.findById(UUID.fromString(attempt.get("paymentAttemptId").asText()))
                .orElseThrow()
                .getDisposition()).isEqualTo(PaymentDisposition.REQUIRES_RESOLUTION);
        UUID attemptId = UUID.fromString(attempt.get("paymentAttemptId").asText());
        assertThat(refundRepository.count()).isEqualTo(1);
        Refund compensation = refundRepository.findByPaymentAttemptIdOrderByCreatedAtDesc(attemptId).get(0);
        assertThat(compensation.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(compensation.getIdempotencyKey()).isEqualTo("late-payment-" + attemptId);
        assertThat(compensation.getAmount()).isEqualByComparingTo(
                paymentAttemptRepository.findById(attemptId).orElseThrow().getCapturedAmount());
        assertThat(outboxEventRepository.findAll().stream()
                .noneMatch(event -> "BOOKING_CONFIRMED".equals(event.getEventType())
                        && booking.bookingId().equals(event.getAggregateId()))).isTrue();
        refundRetryProperties.setAfterCommitEnabled(false);
        refundRetryService.processDueRefunds();
        assertThat(refundRepository.findById(compensation.getId()).orElseThrow().getStatus())
                .isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(ticketRepository.findByBookingId(booking.bookingId())).isEmpty();
    }

    @Test
    void holdConversionRacingTripCancellationLeavesNoConfirmedBooking() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        TripFixture trip = createOperatorTrip(admin);
        JsonNode hold = createHold(trip, List.of(trip.availableSeatIds().get(0)));
        UUID holdId = UUID.fromString(hold.get("holdId").asText());
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch converted = new CountDownLatch(1);
        AtomicInteger barrierHits = new AtomicInteger();
        ReflectionTestUtils.setField(
                operatorTripAdminService,
                "afterAuthorizeBeforeLockForTests",
                (Runnable) () -> {
                    if (barrierHits.getAndIncrement() != 0) {
                        return;
                    }
                    authorized.countDown();
                    try {
                        assertThat(converted.await(20, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> cancelFuture = executor.submit(() -> mockMvc.perform(
                            post("/api/v1/operator/{operatorId}/trips/{tripId}/cancel",
                                    admin.operator().getId(), trip.tripId())
                                    .with(bearer(admin.accessToken())))
                    .andReturn());
            assertThat(authorized.await(20, TimeUnit.SECONDS)).isTrue();
            mockMvc.perform(post("/api/v1/bookings")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "holdId":"%s",
                                      "originStopId":"%s",
                                      "destinationStopId":"%s",
                                      "idempotencyKey":"hold-race-%s",
                                      "passengers":[{"seatInventoryId":"%s","fullName":"Race Rider","age":30}]
                                    }
                                    """.formatted(
                                    holdId, trip.stopId(1), trip.stopId(3), shortId(),
                                    trip.availableSeatIds().get(0))))
                    .andReturn();
            converted.countDown();
            MvcResult cancelResult = cancelFuture.get(30, TimeUnit.SECONDS);
            assertThat(cancelResult.getResponse().getStatus()).isEqualTo(200);
        } finally {
            ReflectionTestUtils.setField(operatorTripAdminService, "afterAuthorizeBeforeLockForTests", null);
            executor.shutdownNow();
        }
        assertThat(tripRepository.findById(trip.tripId()).orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE trip_id = ? AND status = 'CONFIRMED'",
                Integer.class,
                trip.tripId())).isZero();
        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE trip_id = ? AND status = 'PENDING_PAYMENT'",
                Integer.class,
                trip.tripId());
        assertThat(pending).isZero();
    }

    @Test
    void adminDeactivateUsesTheSameConfirmedCascade() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        pay(booking, "admin-pay");
        mockMvc.perform(post("/api/v1/admin/trips/{id}/deactivate", booking.tripId())
                        .with(bearer(platformAdmin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(cancellationRepository.findByBookingId(booking.bookingId()).orElseThrow().getPolicyCode())
                .isEqualTo("TRIP_CANCELLED_FULL_REFUND_V1");
        assertThat(cancellationRepository.findByBookingId(booking.bookingId()).orElseThrow().getRequestedByUserId())
                .isEqualTo(platformAdmin.user().getId());
    }

    private void cancelTrip(UUID tripId) throws Exception {
        mockMvc.perform(post("/api/v1/admin/trips/{id}/deactivate", tripId)
                        .with(bearer(platformAdmin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    private void pay(CreatedBooking booking, String key) throws Exception {
        JsonNode attempt = initiate(booking, key);
        sendWebhook(capturedBody(attempt, "evt-" + key, "pay-" + key, epochNow()), "evt-" + key)
                .andExpect(status().isOk());
        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    private JsonNode initiate(CreatedBooking booking, String key) throws Exception {
        return read(mockMvc.perform(post("/api/v1/bookings/{id}/payments", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .header("Idempotency-Key", key + "-" + shortId()))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private CreatedBooking createPendingBooking(int seatCount) throws Exception {
        TripFixture trip = createTrip(Math.max(seatCount, 2));
        return bookSeat(trip, trip.availableSeatIds().get(0), "p-" + SEQUENCE.incrementAndGet());
    }

    private CreatedBooking bookSeat(TripFixture trip, UUID seatId, String key) throws Exception {
        JsonNode hold = createHold(trip, List.of(seatId));
        UUID holdId = UUID.fromString(hold.get("holdId").asText());
        MvcResult created = mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holdId":"%s",
                                  "originStopId":"%s",
                                  "destinationStopId":"%s",
                                  "idempotencyKey":"book-%s",
                                  "passengers":[{"seatInventoryId":"%s","fullName":"Rider %s","age":30}]
                                }
                                """.formatted(holdId, trip.stopId(1), trip.stopId(3), key + shortId(), seatId, key)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        return new CreatedBooking(
                UUID.fromString(body.get("bookingId").asText()),
                trip.tripId(),
                holdId,
                List.of(seatId));
    }

    private JsonNode createHold(TripFixture trip, List<UUID> seats) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originStopId":"%s","destinationStopId":"%s","seatInventoryIds":["%s"]}
                                """.formatted(trip.stopId(1), trip.stopId(3), seats.get(0))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private TripFixture createOperatorTrip(IssuedOperatorMember admin) throws Exception {
        UUID operatorId = admin.operator().getId();
        int n = SEQUENCE.incrementAndGet();
        UUID busTypeId = createMaster("bus-types",
                "{\"code\":\"OT%s\",\"displayName\":\"Type %s\"}".formatted(n, n));
        UUID layoutId = createSeatLayout(operatorId, "OL" + n, 2);
        UUID busId = createMaster("buses", """
                {"operatorId":"%s","busTypeId":"%s","seatLayoutId":"%s","registrationNumber":"OTBUS-%s"}
                """.formatted(operatorId, busTypeId, layoutId, n));
        UUID hyd = createMaster("locations", locationJson("Telangana", "Hyd-O" + n));
        UUID sur = createMaster("locations", locationJson("Telangana", "Sur-O" + n));
        UUID vij = createMaster("locations", locationJson("Andhra Pradesh", "Vja-O" + n));
        UUID gun = createMaster("locations", locationJson("Andhra Pradesh", "Gun-O" + n));
        UUID routeId = createRoute(operatorId, "OTR" + n, hyd, sur, vij, gun);
        Instant departure = Instant.parse("2027-11-01T10:00:00Z");
        MvcResult created = mockMvc.perform(post("/api/v1/operator/{operatorId}/trips", operatorId)
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s","routeId":"%s",
                                  "scheduledDepartureAt":"%s","scheduledArrivalAt":"%s",
                                  "baseFare":500.00,"bookingOpensAt":"%s","bookingClosesAt":"%s"
                                }
                                """.formatted(
                                busId, routeId, departure, departure.plusSeconds(6 * 3600),
                                Instant.parse("2020-01-01T00:00:00Z"), departure.minusSeconds(3600))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID tripId = UUID.fromString(body.get("id").asText());
        mockMvc.perform(post("/api/v1/operator/{operatorId}/trips/{tripId}/schedule", operatorId, tripId)
                        .with(bearer(admin.accessToken())))
                .andExpect(status().isOk());
        return toFixture(tripId, body);
    }

    private TripFixture createTrip(int seatCount) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        UUID operatorId = createMaster("operators",
                "{\"legalName\":\"Op %s\",\"displayName\":\"Co %s\"}".formatted(n, n));
        UUID busTypeId = createMaster("bus-types",
                "{\"code\":\"T%s\",\"displayName\":\"Type %s\"}".formatted(n, n));
        UUID layoutId = createSeatLayout(operatorId, "L" + n, seatCount);
        UUID busId = createMaster("buses", """
                {"operatorId":"%s","busTypeId":"%s","seatLayoutId":"%s","registrationNumber":"TCBUS-%s"}
                """.formatted(operatorId, busTypeId, layoutId, n));
        UUID hyd = createMaster("locations", locationJson("Telangana", "Hyd-" + n));
        UUID sur = createMaster("locations", locationJson("Telangana", "Sur-" + n));
        UUID vij = createMaster("locations", locationJson("Andhra Pradesh", "Vja-" + n));
        UUID gun = createMaster("locations", locationJson("Andhra Pradesh", "Gun-" + n));
        UUID routeId = createRoute(operatorId, "TCR" + n, hyd, sur, vij, gun);
        Instant departure = Instant.parse("2026-12-01T10:00:00Z");
        MvcResult created = mockMvc.perform(post("/api/v1/admin/trips")
                        .with(bearer(platformAdmin.accessToken()))
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
                        .with(bearer(platformAdmin.accessToken())))
                .andExpect(status().isOk());
        return toFixture(tripId, body);
    }

    private TripFixture toFixture(UUID tripId, JsonNode body) {
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

    private UUID createSeatLayout(UUID operatorId, String name, int seatCount) throws Exception {
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
        UUID layoutId = createMaster("seat-layouts", """
                {
                  "operatorId":"%s","name":"Layout %s","version":1,"deckCount":1,"rowCount":1,"columnCount":%d,
                  "seats":%s
                }
                """.formatted(operatorId, name, seatCount, seats));
        mockMvc.perform(post("/api/v1/admin/seat-layouts/{id}/activate", layoutId)
                        .with(bearer(platformAdmin.accessToken())))
                .andExpect(status().isOk());
        return layoutId;
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
                        .with(bearer(platformAdmin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
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

    private void runConcurrent(CheckedAction first, CheckedAction second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = submit(executor, ready, start, first);
            Future<?> b = submit(executor, ready, start, second);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
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

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String locationJson(String state, String city) {
        return """
                {"countryCode":"IN","state":"%s","city":"%s","timeZone":"Asia/Kolkata"}
                """.formatted(state, city);
    }

    private static long paise(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static long epochNow() {
        return Instant.now().getEpochSecond();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String hmac(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
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
        private final AtomicLong lastRefundAmountPaise = new AtomicLong();
        private final AtomicReference<String> lastRefundIdempotencyKey = new AtomicReference<>();
        volatile boolean failRefunds;

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
            lastRefundAmountPaise.set(0);
            lastRefundIdempotencyKey.set(null);
            failRefunds = false;
        }

        String lastRefundIdempotencyKey() {
            return lastRefundIdempotencyKey.get();
        }

        private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
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
        }

        private void handleOrder(com.sun.net.httpserver.HttpExchange exchange, byte[] request) throws IOException {
            orderHttpCalls.incrementAndGet();
            String idempotency = header(exchange, "X-Razorpay-Idempotency-Key");
            long amount = readAmount(new String(request, StandardCharsets.UTF_8));
            String orderId = orders.computeIfAbsent(
                    idempotency, key -> "order_" + Integer.toHexString(key.hashCode()));
            write(exchange, 200, """
                    {"id":"%s","entity":"order","amount":%d,"currency":"INR","status":"created"}
                    """.formatted(orderId, amount));
        }

        private void handleRefund(com.sun.net.httpserver.HttpExchange exchange, String path, byte[] request)
                throws IOException {
            refundHttpCalls.incrementAndGet();
            String idempotency = header(exchange, "X-Razorpay-Idempotency-Key");
            lastRefundIdempotencyKey.set(idempotency);
            lastRefundAmountPaise.set(readAmount(new String(request, StandardCharsets.UTF_8)));
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
