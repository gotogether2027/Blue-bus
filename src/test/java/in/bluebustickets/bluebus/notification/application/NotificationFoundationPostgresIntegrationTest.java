package in.bluebustickets.bluebus.notification.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.bluebustickets.bluebus.booking.application.BookingLifecycleService;
import in.bluebustickets.bluebus.booking.domain.BookingCancellation;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxProcessorService;
import in.bluebustickets.bluebus.foundation.outbox.rabbit.ProcessedEventRepository;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.notification.domain.Notification;
import in.bluebustickets.bluebus.notification.domain.NotificationChannel;
import in.bluebustickets.bluebus.notification.domain.NotificationEventType;
import in.bluebustickets.bluebus.notification.domain.NotificationPreference;
import in.bluebustickets.bluebus.notification.domain.NotificationStatus;
import in.bluebustickets.bluebus.notification.repository.NotificationPreferenceRepository;
import in.bluebustickets.bluebus.notification.repository.NotificationRepository;
import in.bluebustickets.bluebus.payments.application.RefundOutboxWriter;
import in.bluebustickets.bluebus.payments.domain.PaymentAttempt;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationFoundationPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "notify-owner@example.test";
    private static final String OTHER_EMAIL = "notify-other@example.test";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("blue-bus.outbox.processor.enabled", () -> "false");
        registry.add("blue-bus.notifications.processor.enabled", () -> "false");
        registry.add("blue-bus.notifications.delivery.enabled", () -> "false");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingCancellationRepository cancellationRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private PaymentProviderEventRepository paymentProviderEventRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private NotificationPreferenceRepository preferenceRepository;
    @Autowired private NotificationApplicationService notificationApplicationService;
    @Autowired private NotificationOutboxProcessorService notificationOutboxProcessorService;
    @Autowired private NotificationDeliveryProcessor deliveryProcessor;
    @Autowired private BookingLifecycleService bookingLifecycleService;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;
    @Autowired private TransactionTemplate transactionTemplate;

    private String customerToken;
    private String otherToken;
    private String adminToken;
    private UUID customerId;
    private UUID otherId;

    @BeforeEach
    void seed() throws Exception {
        notificationRepository.deleteAll();
        preferenceRepository.deleteAll();
        refundRepository.deleteAll();
        paymentProviderEventRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        ticketRepository.deleteAll();
        processedEventRepository.deleteAll();
        outboxEventRepository.deleteAll();
        cancellationRepository.deleteAll();
        bookingRepository.deleteAll();
        userRoleRepository.deleteAll();
        userRepository.deleteAll();

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User owner = saveCustomer(CUSTOMER_EMAIL, "+919944480041", "Notify", "Owner");
        User other = saveCustomer(OTHER_EMAIL, "+919944480042", "Notify", "Other");
        userRoleRepository.saveAndFlush(new UserRole(owner, customerRole));
        userRoleRepository.saveAndFlush(new UserRole(other, customerRole));
        customerId = owner.getId();
        otherId = other.getId();
        customerToken = loginToken(CUSTOMER_EMAIL);
        otherToken = loginToken(OTHER_EMAIL);
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void createsEmailNotificationFromBookingConfirmedAndIsIdempotent() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());

        assertThat(notificationApplicationService.processOutboxEvent(event))
                .isEqualTo(NotificationApplicationService.Outcome.PROCESSED);
        assertThat(notificationRepository.countByUserId(customerId)).isEqualTo(1);
        Notification created = notificationRepository.findAll().getFirst();
        assertThat(created.getUserId()).isEqualTo(customerId);
        assertThat(created.getEventType()).isEqualTo(NotificationEventType.BOOKING_CONFIRMED);
        assertThat(created.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(created.getTemplateCode()).isEqualTo("BOOKING_CONFIRMED");
        assertThat(created.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(created.getSourceEventId()).isEqualTo(event.getId());

        assertThat(notificationApplicationService.processOutboxEvent(event))
                .isEqualTo(NotificationApplicationService.Outcome.DUPLICATE);
        assertThat(notificationOutboxProcessorService.processPending().processed()).isZero();
        assertThat(notificationRepository.countByUserId(customerId)).isEqualTo(1);
        assertThat(processedEventRepository.existsByEventIdAndConsumerName(
                event.getId(), NotificationApplicationService.CONSUMER_NAME)).isTrue();
    }

    @Test
    void duplicateLogicalEventDoesNotCreateSecondRow() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent first = requireBookingConfirmed(booking.bookingId());
        notificationApplicationService.processOutboxEvent(first);

        OutboxEvent duplicate = new OutboxEvent(
                "BOOKING_CONFIRMED",
                "BOOKING",
                booking.bookingId(),
                first.getPayloadJson(),
                Instant.now(),
                first.getCorrelationId(),
                first.getCausationId());
        outboxEventRepository.saveAndFlush(duplicate);
        assertThat(notificationApplicationService.processOutboxEvent(duplicate))
                .isEqualTo(NotificationApplicationService.Outcome.PROCESSED);
        assertThat(notificationRepository.countByUserId(customerId)).isEqualTo(1);
    }

    @Test
    void tripCancellationAlsoCreatesTripCancelledNotification() throws Exception {
        CreatedBooking booking = confirmBooking();
        BookingCancellation cancellation = cancellationRepository.saveAndFlush(
                BookingCancellation.tripCancelledFullRefund(
                        booking.bookingId(),
                        customerId,
                        "TRIP_CANCELLED",
                        new BigDecimal("900.00"),
                        "INR",
                        Instant.now()));
        OutboxEvent event = outboxEventRepository.saveAndFlush(new OutboxEvent(
                "BOOKING_CANCELLED",
                "BOOKING",
                booking.bookingId(),
                "{\"bookingId\":\"" + booking.bookingId()
                        + "\",\"cancellationId\":\"" + cancellation.getId() + "\"}",
                Instant.now(),
                cancellation.getId().toString(),
                null));

        assertThat(notificationApplicationService.processOutboxEvent(event))
                .isEqualTo(NotificationApplicationService.Outcome.PROCESSED);
        assertThat(notificationRepository.findAll())
                .extracting(Notification::getEventType)
                .containsExactlyInAnyOrder(
                        NotificationEventType.BOOKING_CANCELLED,
                        NotificationEventType.TRIP_CANCELLED);
    }

    @Test
    void customerReasonTripCancelledDoesNotDeriveTripNotification() throws Exception {
        CreatedBooking booking = confirmBooking();
        BookingCancellation cancellation = cancellationRepository.saveAndFlush(
                BookingCancellation.confirmedFullRefund(
                        booking.bookingId(),
                        customerId,
                        "TRIP_CANCELLED",
                        new BigDecimal("900.00"),
                        "INR",
                        Instant.now()));
        OutboxEvent event = outboxEventRepository.saveAndFlush(new OutboxEvent(
                "BOOKING_CANCELLED",
                "BOOKING",
                booking.bookingId(),
                "{\"bookingId\":\"" + booking.bookingId()
                        + "\",\"cancellationId\":\"" + cancellation.getId() + "\"}",
                Instant.now(),
                cancellation.getId().toString(),
                null));

        assertThat(notificationApplicationService.processOutboxEvent(event))
                .isEqualTo(NotificationApplicationService.Outcome.PROCESSED);
        assertThat(notificationRepository.findAll())
                .extracting(Notification::getEventType)
                .containsExactly(NotificationEventType.BOOKING_CANCELLED);
    }

    @Test
    void separateFailedPaymentAttemptsCreateSeparateNotifications() throws Exception {
        CreatedBooking booking = confirmBooking();
        var persisted = bookingRepository.findById(booking.bookingId()).orElseThrow();
        PaymentAttempt first = new PaymentAttempt(
                persisted.getId(), persisted.getUserId(), "RAZORPAY",
                "mr-a-" + persisted.getId(), "idem-a-" + persisted.getId(), "fp-a",
                persisted.getTotalAmount(), persisted.getCurrency(), persisted.getPaymentExpiresAt());
        first.markFailed("failed", "CARD_DECLINED", Instant.now(), Instant.now());
        first = paymentAttemptRepository.saveAndFlush(first);
        PaymentAttempt second = paymentAttemptRepository.saveAndFlush(new PaymentAttempt(
                persisted.getId(), persisted.getUserId(), "UNCONFIGURED",
                "mr-b-" + persisted.getId(), "idem-b-" + persisted.getId(), "fp-b",
                persisted.getTotalAmount(), persisted.getCurrency(), persisted.getPaymentExpiresAt()));
        OutboxEvent firstEvent = outboxEventRepository.saveAndFlush(new OutboxEvent(
                "PAYMENT_FAILED", "PAYMENT_ATTEMPT", first.getId(),
                "{\"paymentAttemptId\":\"" + first.getId() + "\"}",
                Instant.now(), first.getId().toString(), null));
        OutboxEvent secondEvent = outboxEventRepository.saveAndFlush(new OutboxEvent(
                "PAYMENT_FAILED", "PAYMENT_ATTEMPT", second.getId(),
                "{\"paymentAttemptId\":\"" + second.getId() + "\"}",
                Instant.now(), second.getId().toString(), null));

        notificationApplicationService.processOutboxEvent(firstEvent);
        notificationApplicationService.processOutboxEvent(secondEvent);

        assertThat(notificationRepository.findAll())
                .extracting(Notification::getLogicalKey)
                .containsExactlyInAnyOrder(
                        "PAYMENT_FAILED:" + first.getId(),
                        "PAYMENT_FAILED:" + second.getId());
    }

    @Test
    void concurrentLogicalDuplicateIsIdempotent() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent first = requireBookingConfirmed(booking.bookingId());
        OutboxEvent second = outboxEventRepository.saveAndFlush(new OutboxEvent(
                "BOOKING_CONFIRMED",
                "BOOKING",
                booking.bookingId(),
                first.getPayloadJson(),
                Instant.now(),
                first.getCorrelationId(),
                first.getCausationId()));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<NotificationApplicationService.Outcome> left = pool.submit(() -> {
                start.await();
                return notificationApplicationService.processOutboxEvent(first);
            });
            Future<NotificationApplicationService.Outcome> right = pool.submit(() -> {
                start.await();
                return notificationApplicationService.processOutboxEvent(second);
            });
            start.countDown();
            assertThat(left.get()).isNotNull();
            assertThat(right.get()).isNotNull();
        } finally {
            pool.shutdownNow();
        }
        assertThat(notificationRepository.countByUserId(customerId)).isEqualTo(1);
    }

    @Test
    void retryableRefundFailureEmitsNoRefundFailedAndLaterSuccessEmitsSucceeded() throws Exception {
        CreatedBooking booking = confirmBooking();
        Refund refund = saveRefund(booking, "retryable");
        refund.markFailed("failed", "PROVIDER_FAILED", Instant.now());
        Refund saved = refundRepository.saveAndFlush(refund);

        transactionTemplate.executeWithoutResult(status ->
                RefundOutboxWriter.writeFailed(outboxEventRepository, saved, Instant.now()));
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId("REFUND_FAILED", saved.getId())).isZero();

        saved.markSucceeded("rfnd_ok", "processed", Instant.now());
        Refund succeeded = refundRepository.saveAndFlush(saved);
        transactionTemplate.executeWithoutResult(status ->
                RefundOutboxWriter.writeSucceeded(outboxEventRepository, succeeded, Instant.now()));

        assertThat(outboxEventRepository.countByEventTypeAndAggregateId("REFUND_FAILED", succeeded.getId())).isZero();
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                "REFUND_SUCCEEDED", succeeded.getPaymentAttemptId())).isEqualTo(1);
    }

    @Test
    void terminalRefundFailureEmitsOneRefundFailed() throws Exception {
        CreatedBooking booking = confirmBooking();
        Refund refund = saveRefund(booking, "terminal");
        refund.markProcessing("rfnd_terminal", "processing");
        refund.markFailed("failed", "PROVIDER_FAILED", Instant.now());
        Refund saved = refundRepository.saveAndFlush(refund);

        transactionTemplate.executeWithoutResult(status -> {
            RefundOutboxWriter.writeFailed(outboxEventRepository, saved, Instant.now());
            RefundOutboxWriter.writeFailed(outboxEventRepository, saved, Instant.now());
            RefundOutboxWriter.writeRequested(outboxEventRepository, saved, Instant.now());
            RefundOutboxWriter.writeRequested(outboxEventRepository, saved, Instant.now());
        });

        assertThat(outboxEventRepository.countByEventTypeAndAggregateId("REFUND_FAILED", saved.getId())).isEqualTo(1);
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId("REFUND_REQUESTED", saved.getId())).isEqualTo(1);
    }

    @Test
    void unknownEventTypeIsIgnoredWithoutNotificationRows() {
        OutboxEvent event = outboxEventRepository.saveAndFlush(new OutboxEvent(
                "PAYMENT_INITIATED",
                "PAYMENT_ATTEMPT",
                UUID.randomUUID(),
                "{}",
                Instant.now(),
                null,
                null));
        assertThat(notificationApplicationService.processOutboxEvent(event))
                .isEqualTo(NotificationApplicationService.Outcome.IGNORED);
        assertThat(notificationRepository.count()).isZero();
        assertThat(processedEventRepository.existsByEventIdAndConsumerName(
                event.getId(), NotificationApplicationService.CONSUMER_NAME)).isFalse();
        assertThat(notificationApplicationService.processOutboxEvent(event))
                .isEqualTo(NotificationApplicationService.Outcome.IGNORED);
        assertThat(processedEventRepository.existsByEventIdAndConsumerName(
                event.getId(), NotificationApplicationService.CONSUMER_NAME)).isFalse();
    }

    @Test
    void disabledChannelIsSkipped() throws Exception {
        preferenceRepository.saveAndFlush(disabledEmail(customerId));
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());
        assertThat(notificationApplicationService.processOutboxEvent(event))
                .isEqualTo(NotificationApplicationService.Outcome.PROCESSED);
        assertThat(notificationRepository.countByUserId(customerId)).isZero();
    }

    @Test
    void listApiReturnsOnlyAuthenticatedCustomerNotifications() throws Exception {
        Instant now = Instant.parse("2026-09-24T10:00:00Z");
        notificationRepository.saveAndFlush(pendingNotification(
                customerId, NotificationEventType.BOOKING_CONFIRMED, "own-1", now));
        notificationRepository.saveAndFlush(pendingNotification(
                customerId, NotificationEventType.TICKET_ISSUED, "own-2", now.plusSeconds(1)));
        notificationRepository.saveAndFlush(pendingNotification(
                otherId, NotificationEventType.PAYMENT_FAILED, "other-1", now.plusSeconds(2)));

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("TICKET_ISSUED"));

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("PAYMENT_FAILED"));

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preferenceApiReturnsDefaultsThenPersistedValues() throws Exception {
        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(customerId.toString()))
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.smsEnabled").value(false))
                .andExpect(jsonPath("$.whatsappEnabled").value(false));

        NotificationPreference stored = new NotificationPreference(customerId, Instant.now());
        stored.update(false, true, true, Instant.now());
        preferenceRepository.saveAndFlush(stored);

        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(false))
                .andExpect(jsonPath("$.smsEnabled").value(true))
                .andExpect(jsonPath("$.whatsappEnabled").value(true));
    }

    @Test
    void retryableFailureStaysPendingAndNonRetryableFails() {
        Instant now = Instant.parse("2026-09-24T12:00:00Z");
        Notification retryable = notificationRepository.saveAndFlush(pendingNotification(
                customerId, NotificationEventType.BOOKING_CONFIRMED, "retry-1", now));
        deliveryProcessor.tryClaim(retryable.getId(), now);
        deliveryProcessor.recordFailure(retryable.getId(), now, true, "PROVIDER_TIMEOUT", "try again");
        Notification retried = notificationRepository.findById(retryable.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(retried.getAttemptCount()).isEqualTo(1);
        assertThat(retried.getNextRetryAt()).isAfter(now);
        assertThat(retried.getFailureCode()).isEqualTo("PROVIDER_TIMEOUT");

        Notification terminal = notificationRepository.saveAndFlush(pendingNotification(
                customerId, NotificationEventType.PAYMENT_FAILED, "fail-1", now));
        deliveryProcessor.tryClaim(terminal.getId(), now);
        deliveryProcessor.recordFailure(terminal.getId(), now, false, "INVALID_RECIPIENT", "stop");
        Notification failed = notificationRepository.findById(terminal.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getNextRetryAt()).isNull();
        assertThat(failed.getFailureCode()).isEqualTo("INVALID_RECIPIENT");
    }

    private Refund saveRefund(CreatedBooking booking, String suffix) {
        var persisted = bookingRepository.findById(booking.bookingId()).orElseThrow();
        PaymentAttempt attempt = new PaymentAttempt(
                persisted.getId(),
                persisted.getUserId(),
                "RAZORPAY",
                "mr-" + suffix + "-" + persisted.getId(),
                "idem-" + suffix + "-" + persisted.getId(),
                "fp-" + suffix,
                persisted.getTotalAmount(),
                persisted.getCurrency(),
                persisted.getPaymentExpiresAt());
        attempt.markFailed("failed", "CARD_DECLINED", Instant.now(), Instant.now());
        attempt = paymentAttemptRepository.saveAndFlush(attempt);
        return refundRepository.saveAndFlush(new Refund(
                attempt.getId(),
                booking.bookingId(),
                "RAZORPAY",
                "refund-" + suffix + "-" + booking.bookingId(),
                "rfp-" + suffix,
                new BigDecimal("10.00"),
                "INR",
                "BOOKING_CANCELLED",
                Instant.now()));
    }

    private User saveCustomer(String email, String phone, String first, String last) {
        User user = new User(email, phone, first, last);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return userRepository.saveAndFlush(user);
    }

    private NotificationPreference disabledEmail(UUID userId) {
        NotificationPreference preference = new NotificationPreference(userId, Instant.now());
        preference.update(false, false, false, Instant.now());
        return preference;
    }

    private Notification pendingNotification(
            UUID userId,
            NotificationEventType type,
            String suffix,
            Instant createdAt) {
        return new Notification(
                userId,
                UUID.randomUUID(),
                type,
                NotificationChannel.EMAIL,
                type.name(),
                type.name() + ":" + suffix,
                type.name(),
                type.name(),
                "{}",
                createdAt);
    }

    private CreatedBooking confirmBooking() throws Exception {
        CreatedBooking booking = createPendingBooking();
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        return booking;
    }

    private OutboxEvent requireBookingConfirmed(UUID bookingId) {
        return outboxEventRepository.findAll().stream()
                .filter(event -> OutboxProcessorService.BOOKING_CONFIRMED.equals(event.getEventType()))
                .filter(event -> bookingId.equals(event.getAggregateId()))
                .findFirst()
                .orElseThrow();
    }

    private CreatedBooking createPendingBooking() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        TripFixture trip = createTrip("NTBUS-" + n, "NTRT-" + n, 2);
        List<UUID> seats = trip.availableSeatIds().subList(0, 1);
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
                                  "idempotencyKey":"book-nt-%d",
                                  "passengers":[
                                    {"seatInventoryId":"%s","fullName":"Rider 1","age":28,"gender":"F"}
                                  ]
                                }
                                """.formatted(holdId, trip.stopId(1), trip.stopId(3), n, seats.getFirst())))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        return new CreatedBooking(UUID.fromString(body.get("bookingId").asText()));
    }

    private JsonNode createHold(TripFixture trip, List<UUID> seats) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/trips/{tripId}/holds", trip.tripId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"originStopId":"%s","destinationStopId":"%s","seatInventoryIds":["%s"]}
                                """.formatted(trip.stopId(1), trip.stopId(3), seats.getFirst())))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private TripFixture createTrip(String registration, String routeCode, int seatCount) throws Exception {
        UUID operatorId = createMaster("operators",
                "{\"legalName\":\"Op %s\",\"displayName\":\"Co %s\"}".formatted(registration, registration));
        UUID busTypeId = createMaster("bus-types",
                "{\"code\":\"T%s\",\"displayName\":\"Type %s\"}".formatted(registration, registration));
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

    private record CreatedBooking(UUID bookingId) {
    }

    private record TripFixture(UUID tripId, List<UUID> stopIdsBySequence, List<UUID> availableSeatIds) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }
}
