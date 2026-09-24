package in.bluebustickets.bluebus.ticket.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import in.bluebustickets.bluebus.booking.application.BookingLifecycleService;
import in.bluebustickets.bluebus.booking.domain.BookingStatus;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxProcessingResult;
import in.bluebustickets.bluebus.foundation.outbox.OutboxProcessorService;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.User;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentProviderEventRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
import in.bluebustickets.bluebus.ticket.domain.Ticket;
import in.bluebustickets.bluebus.ticket.domain.TicketStatus;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
class AutomaticTicketIssuancePostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "auto-ticket@example.test";
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
        registry.add("blue-bus.outbox.processor.enabled", () -> "false");
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
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private PaymentProviderEventRepository paymentProviderEventRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private BookingLifecycleService bookingLifecycleService;
    @Autowired private OutboxProcessorService outboxProcessorService;
    @Autowired private TicketApplicationService ticketApplicationService;
    @Autowired private PlatformTransactionManager transactionManager;
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
        User user = new User(CUSTOMER_EMAIL, "+919944480011", "Auto", "Ticket");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        customerToken = loginToken(CUSTOMER_EMAIL);
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void confirmationWritesBookingConfirmedAndProcessorIssuesTicketWithSnapshot() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());

        assertThat(bookingRepository.findById(booking.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                OutboxProcessorService.BOOKING_CONFIRMED, booking.bookingId())).isEqualTo(1);

        OutboxEvent confirmed = outboxEventRepository.findAll().stream()
                .filter(e -> OutboxProcessorService.BOOKING_CONFIRMED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(confirmed.getPublishedAt()).isNull();
        assertThat(confirmed.getPayloadJson()).contains(booking.bookingId().toString());

        BigDecimal total = bookingRepository.findById(booking.bookingId()).orElseThrow().getTotalAmount();
        OutboxProcessingResult result = outboxProcessorService.processPendingBookingConfirmed();
        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.failures()).isZero();
        assertThat(ticketRepository.count()).isEqualTo(1);

        OutboxEvent published = outboxEventRepository.findById(confirmed.getId()).orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();
        assertThat(published.getAttemptCount()).isGreaterThanOrEqualTo(1);

        var ticket = ticketRepository.findDetailedByBookingId(booking.bookingId()).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ACTIVE);
        assertThat(ticket.getTotalAmount()).isEqualByComparingTo(total);
        assertThat(ticket.getCurrency()).isEqualTo("INR");
        assertThat(ticket.getPassengers()).hasSize(2);
        assertThat(ticket.getDestinationStopName()).contains("Vijayawada");
        assertThat(ticket.getDestinationStopName()).doesNotContain("Guntur");

        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                TicketApplicationService.TICKET_ISSUED, ticket.getId())).isEqualTo(1);

        mockMvc.perform(get("/api/v1/tickets/{id}", ticket.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketNumber").value(ticket.getTicketNumber()))
                .andExpect(jsonPath("$.passengers.length()").value(2));
    }

    @Test
    void duplicateAndConcurrentProcessingCreateExactlyOneTicketAndOneTicketIssued() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());

        outboxProcessorService.processPendingBookingConfirmed();
        outboxProcessorService.processPendingBookingConfirmed();
        assertThat(ticketRepository.count()).isEqualTo(1);
        UUID ticketId = ticketRepository.findByBookingId(booking.bookingId()).orElseThrow().getId();
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                TicketApplicationService.TICKET_ISSUED, ticketId)).isEqualTo(1);

        CreatedBooking concurrent = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(concurrent.bookingId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<OutboxProcessingResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return outboxProcessorService.processPendingBookingConfirmed();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            futures.get(0).get(20, TimeUnit.SECONDS);
            futures.get(1).get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(ticketRepository.findByBookingId(concurrent.bookingId())).isPresent();
        assertThat(ticketRepository.count()).isEqualTo(2);
        UUID concurrentTicketId = ticketRepository.findByBookingId(concurrent.bookingId()).orElseThrow().getId();
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                TicketApplicationService.TICKET_ISSUED, concurrentTicketId)).isEqualTo(1);
    }

    @Test
    void existingTicketMakesProcessorIdempotentAndFailureLeavesEventRetryable() throws Exception {
        CreatedBooking existing = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(existing.bookingId());
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", existing.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated());
        assertThat(ticketRepository.count()).isEqualTo(1);
        UUID ticketId = ticketRepository.findByBookingId(existing.bookingId()).orElseThrow().getId();

        OutboxProcessingResult afterManual = outboxProcessorService.processPendingBookingConfirmed();
        assertThat(afterManual.processed()).isEqualTo(1);
        assertThat(ticketRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                TicketApplicationService.TICKET_ISSUED, ticketId)).isEqualTo(1);

        CreatedBooking pending = createPendingBooking(1);
        Instant now = Instant.now();
        OutboxEvent premature = outboxEventRepository.saveAndFlush(new OutboxEvent(
                OutboxProcessorService.BOOKING_CONFIRMED,
                "BOOKING",
                pending.bookingId(),
                "{\"bookingId\":\"" + pending.bookingId() + "\"}",
                now,
                null,
                null));

        OutboxProcessingResult failed = outboxProcessorService.processPendingBookingConfirmed();
        assertThat(failed.failures()).isGreaterThanOrEqualTo(1);
        OutboxEvent stillPending = outboxEventRepository.findById(premature.getId()).orElseThrow();
        assertThat(stillPending.getPublishedAt()).isNull();
        assertThat(stillPending.getAttemptCount()).isGreaterThanOrEqualTo(1);
        assertThat(ticketRepository.findByBookingId(pending.bookingId())).isEmpty();

        bookingLifecycleService.confirmPendingPayment(pending.bookingId());
        // Confirmation writes a second BOOKING_CONFIRMED; process both.
        OutboxProcessingResult recovered = outboxProcessorService.processPendingBookingConfirmed();
        assertThat(recovered.processed()).isGreaterThanOrEqualTo(1);
        assertThat(ticketRepository.findByBookingId(pending.bookingId())).isPresent();
        assertThat(outboxEventRepository.findById(premature.getId()).orElseThrow().getPublishedAt())
                .isNotNull();
    }

    @Test
    void processorSkipsWhenBookingIsNoLongerConfirmedAndDoesNotRetryForever() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                OutboxProcessorService.BOOKING_CONFIRMED, booking.bookingId())).isEqualTo(1);
        OutboxEvent confirmed = outboxEventRepository.findAll().stream()
                .filter(e -> OutboxProcessorService.BOOKING_CONFIRMED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow();

        jdbcTemplate.update("UPDATE bookings SET status = 'REFUND_PENDING' WHERE id = ?", booking.bookingId());

        OutboxProcessingResult result = outboxProcessorService.processPendingBookingConfirmed();
        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.failures()).isZero();
        assertThat(ticketRepository.findByBookingId(booking.bookingId())).isEmpty();
        assertThat(outboxEventRepository.findById(confirmed.getId()).orElseThrow().getPublishedAt())
                .isNotNull();

        OutboxProcessingResult second = outboxProcessorService.processPendingBookingConfirmed();
        assertThat(second.processed()).isZero();
        assertThat(second.failures()).isZero();
        assertThat(ticketRepository.count()).isZero();
    }

    @Test
    void ticketAndTicketIssuedCommitAndRollBackTogether() throws Exception {
        CreatedBooking commitBooking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(commitBooking.bookingId());

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID committedTicketId = tx.execute(status -> {
            Ticket ticket = ticketApplicationService.issueForConfirmedBookingInCurrentTransaction(
                    commitBooking.bookingId());
            assertThat(ticketRepository.findById(ticket.getId())).isPresent();
            assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                    TicketApplicationService.TICKET_ISSUED, ticket.getId())).isEqualTo(1);
            return ticket.getId();
        });

        assertThat(ticketRepository.findById(committedTicketId)).isPresent();
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                TicketApplicationService.TICKET_ISSUED, committedTicketId)).isEqualTo(1);

        CreatedBooking rollbackBooking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(rollbackBooking.bookingId());
        tx.executeWithoutResult(status -> {
            Ticket ticket = ticketApplicationService.issueForConfirmedBookingInCurrentTransaction(
                    rollbackBooking.bookingId());
            assertThat(ticketRepository.findById(ticket.getId())).isPresent();
            assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                    TicketApplicationService.TICKET_ISSUED, ticket.getId())).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(ticketRepository.findByBookingId(rollbackBooking.bookingId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM outbox_events
                WHERE event_type = ? AND payload_json LIKE ?
                """,
                Integer.class,
                TicketApplicationService.TICKET_ISSUED,
                "%" + rollbackBooking.bookingId() + "%")).isZero();
    }

    @Test
    void concurrentTicketIssuedInsertsProduceExactlyOneOutboxEvent() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated());

        var ticket = ticketRepository.findDetailedByBookingId(booking.bookingId()).orElseThrow();
        // Remove the TICKET_ISSUED row written at creation so concurrent ensure paths race.
        jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                TicketApplicationService.TICKET_ISSUED,
                ticket.getId());
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                TicketApplicationService.TICKET_ISSUED, ticket.getId())).isZero();

        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    // Manual + automatic paths both converge on ensureTicketIssued.
                    mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                            .andExpect(status().isCreated());
                    outboxProcessorService.processPendingBookingConfirmed();
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(ticketRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                TicketApplicationService.TICKET_ISSUED, ticket.getId())).isEqualTo(1);
    }

    @Test
    void processorDoesNotTouchPaymentOrNotificationTables() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        long paymentsBefore = paymentAttemptRepository.count();
        long providerEventsBefore = paymentProviderEventRepository.count();
        long refundsBefore = refundRepository.count();

        long notificationsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications", Long.class);

        outboxProcessorService.processPendingBookingConfirmed();

        assertThat(paymentAttemptRepository.count()).isEqualTo(paymentsBefore);
        assertThat(paymentProviderEventRepository.count()).isEqualTo(providerEventsBefore);
        assertThat(refundRepository.count()).isEqualTo(refundsBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications", Long.class)).isEqualTo(notificationsBefore);
    }

    @Test
    void repeatedConfirmationDoesNotDuplicateBookingConfirmed() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                OutboxProcessorService.BOOKING_CONFIRMED, booking.bookingId())).isEqualTo(1);
    }

    private CreatedBooking createPendingBooking(int seatCount) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        TripFixture trip = createTrip("ATBUS-" + n, "ATRT-" + n, Math.max(seatCount, 2));
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
                                  "idempotencyKey":"book-at-%d",
                                  "passengers":[%s]
                                }
                                """.formatted(holdId, trip.stopId(1), trip.stopId(3), n, passengers)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        return new CreatedBooking(UUID.fromString(body.get("bookingId").asText()));
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
