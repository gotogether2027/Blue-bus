package in.bluebustickets.bluebus.foundation.outbox.rabbit;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.bluebustickets.bluebus.booking.application.BookingLifecycleService;
import in.bluebustickets.bluebus.booking.repository.BookingCancellationRepository;
import in.bluebustickets.bluebus.booking.repository.BookingRepository;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEvent;
import in.bluebustickets.bluebus.foundation.outbox.OutboxEventRepository;
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
import in.bluebustickets.bluebus.ticket.domain.TicketStatus;
import in.bluebustickets.bluebus.ticket.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class RabbitMqOutboxPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "rabbit-outbox@example.test";
    private static final String CAPTURE_QUEUE = "blue-bus.booking-confirmed.capture-test";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> String.valueOf(RABBIT.getAmqpPort()));
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("blue-bus.rabbitmq.enabled", () -> "true");
        registry.add("blue-bus.rabbitmq.consumer-enabled", () -> "true");
        registry.add("blue-bus.rabbitmq.publisher.enabled", () -> "false");
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
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private PaymentProviderEventRepository paymentProviderEventRepository;
    @Autowired private RefundRepository refundRepository;
    @Autowired private BookingLifecycleService bookingLifecycleService;
    @Autowired private OutboxProcessorService outboxProcessorService;
    @Autowired private OutboxRabbitPublisherService publisherService;
    @Autowired private RabbitMqProperties rabbitMqProperties;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private TopicExchange blueBusEventsExchange;
    @Autowired private TestAccessTokenFactory testAccessTokenFactory;

    private String customerToken;
    private String adminToken;

    @BeforeEach
    void seed() throws Exception {
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

        rabbitAdmin.declareQueue(QueueBuilder.durable(CAPTURE_QUEUE).build());
        rabbitAdmin.declareBinding(BindingBuilder.bind(new Queue(CAPTURE_QUEUE, true))
                .to(blueBusEventsExchange)
                .with(rabbitMqProperties.getRoutingKey()));
        rabbitAdmin.purgeQueue(rabbitMqProperties.getQueue(), false);
        rabbitAdmin.purgeQueue(CAPTURE_QUEUE, false);

        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User user = new User(CUSTOMER_EMAIL, "+919944480033", "Rabbit", "Outbox");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        customerToken = loginToken(CUSTOMER_EMAIL);
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void declaresDurableTopicTopology() {
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclarePassive(rabbitMqProperties.getExchange());
            var declared = channel.queueDeclarePassive(rabbitMqProperties.getQueue());
            assertThat(declared.getQueue()).isEqualTo(rabbitMqProperties.getQueue());
            return true;
        });
        assertThat(rabbitMqProperties.getExchange()).isEqualTo("blue-bus.events");
        assertThat(rabbitMqProperties.getQueue()).isEqualTo("blue-bus.booking-confirmed");
        assertThat(rabbitMqProperties.getRoutingKey()).isEqualTo("booking.confirmed");
        assertThat(rabbitMqProperties.isBookingConfirmedConsumerAuthoritative()).isTrue();
    }

    @Test
    void publishesBookingConfirmedEnvelopeAndConsumerIssuesTicket() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getRabbitPublishedAt()).isNull();

        var result = publisherService.publishPendingBookingConfirmed();
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        OutboxEvent published = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(published.getRabbitPublishedAt()).isNotNull();
        assertThat(published.getPublishedAt()).isNull();
        assertThat(published.getRabbitAttemptCount()).isGreaterThanOrEqualTo(1);

        Message captured = rabbitTemplate.receive(CAPTURE_QUEUE, 5_000);
        assertThat(captured).isNotNull();
        assertThat(captured.getMessageProperties().getMessageId()).isEqualTo(event.getId().toString());
        assertThat(captured.getMessageProperties().getType()).isEqualTo(OutboxProcessorService.BOOKING_CONFIRMED);
        assertThat(captured.getMessageProperties().getContentType()).contains("json");
        assertThat(captured.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        JsonNode body = objectMapper.readTree(captured.getBody());
        assertThat(body.get("eventId").asText()).isEqualTo(event.getId().toString());
        assertThat(body.get("eventType").asText()).isEqualTo(OutboxProcessorService.BOOKING_CONFIRMED);
        assertThat(body.get("aggregateType").asText()).isEqualTo(event.getAggregateType());
        assertThat(body.get("aggregateId").asText()).isEqualTo(booking.bookingId().toString());
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(event.getSchemaVersion());
        assertThat(body.get("occurredAt").asText()).isNotBlank();
        assertThat(body.has("payload")).isTrue();

        awaitUntil(() -> ticketRepository.count() == 1, Duration.ofSeconds(15));
        var ticket = ticketRepository.findDetailedByBookingId(booking.bookingId()).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ACTIVE);
        assertThat(processedEventRepository.existsByEventIdAndConsumerName(
                event.getId(), RabbitMqProperties.BOOKING_CONFIRMED_CONSUMER)).isTrue();
        assertThat(outboxProcessorService.processPendingBookingConfirmed().processed()).isZero();
        assertThat(ticketRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getPublishedAt()).isNull();
    }

    @Test
    void unavailableBrokerLeavesEventUnpublishedAndLaterRetrySucceeds() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());

        execRabbit("rabbitmqctl", "stop_app");
        try {
            var failed = publisherService.publishPendingBookingConfirmed();
            assertThat(failed.published()).isZero();
            assertThat(failed.failed()).isEqualTo(1);
            OutboxEvent unpublished = outboxEventRepository.findById(event.getId()).orElseThrow();
            assertThat(unpublished.getRabbitPublishedAt()).isNull();
            assertThat(unpublished.getRabbitAttemptCount()).isGreaterThanOrEqualTo(1);
            assertThat(unpublished.getRabbitNextRetryAt()).isNotNull();
            assertThat(ticketRepository.count()).isZero();
        } finally {
            execRabbit("rabbitmqctl", "start_app");
            awaitRabbitReady();
        }

        jdbcTemplate.update(
                "UPDATE outbox_events SET rabbit_next_retry_at = NULL WHERE id = ?",
                event.getId());
        var recovered = publisherService.publishPendingBookingConfirmed();
        assertThat(recovered.published()).isEqualTo(1);
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getRabbitPublishedAt()).isNotNull();
        awaitUntil(() -> ticketRepository.count() == 1, Duration.ofSeconds(15));
    }

    @Test
    void republishAfterMissingDbMarkIsIdempotent() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());

        assertThat(publisherService.publishPendingBookingConfirmed().published()).isEqualTo(1);
        awaitUntil(() -> ticketRepository.count() == 1, Duration.ofSeconds(15));

        jdbcTemplate.update(
                "UPDATE outbox_events SET rabbit_published_at = NULL, rabbit_next_retry_at = NULL WHERE id = ?",
                event.getId());
        assertThat(publisherService.publishPendingBookingConfirmed().published()).isEqualTo(1);
        awaitUntil(
                () -> processedEventRepository.existsByEventIdAndConsumerName(
                        event.getId(), RabbitMqProperties.BOOKING_CONFIRMED_CONSUMER),
                Duration.ofSeconds(15));

        assertThat(ticketRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.existsByEventIdAndConsumerName(
                event.getId(), RabbitMqProperties.BOOKING_CONFIRMED_CONSUMER)).isTrue();
    }

    @Test
    void duplicateConsumerDeliveryIssuesOneTicket() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());
        assertThat(publisherService.publishPendingBookingConfirmed().published()).isEqualTo(1);
        awaitUntil(() -> ticketRepository.count() == 1, Duration.ofSeconds(15));

        Message duplicate = rabbitTemplate.receive(CAPTURE_QUEUE, 5_000);
        assertThat(duplicate).isNotNull();
        rabbitTemplate.send(rabbitMqProperties.getExchange(), rabbitMqProperties.getRoutingKey(), duplicate);

        awaitUntil(
                () -> processedEventRepository.existsByEventIdAndConsumerName(
                        event.getId(), RabbitMqProperties.BOOKING_CONFIRMED_CONSUMER),
                Duration.ofSeconds(15));
        Thread.sleep(500);
        assertThat(ticketRepository.count()).isEqualTo(1);
    }

    @Test
    void malformedEnvelopeDoesNotIssueTicket() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());

        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(event.getId().toString());
        rabbitTemplate.send(
                rabbitMqProperties.getExchange(),
                rabbitMqProperties.getRoutingKey(),
                new Message("{not-json".getBytes(), properties));

        Thread.sleep(1_000);
        assertThat(ticketRepository.count()).isZero();
        assertThat(processedEventRepository.existsByEventIdAndConsumerName(
                event.getId(), RabbitMqProperties.BOOKING_CONFIRMED_CONSUMER)).isFalse();
        assertThat(event.getRabbitPublishedAt()).isNull();
    }

    @Test
    void unknownEventTypeDoesNotIssueTicket() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());
        OutboxMessageEnvelope unknown = new OutboxMessageEnvelope(
                event.getId(),
                "NOT_A_REAL_EVENT",
                event.getAggregateType(),
                event.getAggregateId(),
                event.getSchemaVersion(),
                event.getCorrelationId(),
                event.getCausationId(),
                event.getOccurredAt(),
                objectMapper.readTree(event.getPayloadJson()));

        rabbitTemplate.send(
                rabbitMqProperties.getExchange(),
                rabbitMqProperties.getRoutingKey(),
                new Message(objectMapper.writeValueAsBytes(unknown), jsonProperties(event.getId())));

        Thread.sleep(1_000);
        assertThat(ticketRepository.count()).isZero();
        assertThat(processedEventRepository.existsByEventIdAndConsumerName(
                event.getId(), RabbitMqProperties.BOOKING_CONFIRMED_CONSUMER)).isFalse();
    }

    @Test
    void consumerFailureIsRedeliveredAfterBookingBecomesConfirmed() throws Exception {
        CreatedBooking pending = createPendingBooking(1);
        OutboxEvent premature = outboxEventRepository.saveAndFlush(new OutboxEvent(
                OutboxProcessorService.BOOKING_CONFIRMED,
                "BOOKING",
                pending.bookingId(),
                """
                {"bookingId":"%s"}
                """.formatted(pending.bookingId()),
                Instant.now(),
                pending.bookingId().toString(),
                pending.bookingId().toString()));

        assertThat(publisherService.publishPendingBookingConfirmed().published()).isEqualTo(1);
        Thread.sleep(1_000);
        assertThat(ticketRepository.count()).isZero();

        bookingLifecycleService.confirmPendingPayment(pending.bookingId());
        awaitUntil(() -> ticketRepository.count() == 1, Duration.ofSeconds(20));
        assertThat(ticketRepository.findDetailedByBookingId(pending.bookingId()).orElseThrow().getStatus())
                .isEqualTo(TicketStatus.ACTIVE);
        assertThat(outboxEventRepository.countByEventTypeAndAggregateId(
                OutboxProcessorService.BOOKING_CONFIRMED, pending.bookingId())).isEqualTo(2);
        assertThat(processedEventRepository.existsByEventIdAndConsumerName(
                premature.getId(), RabbitMqProperties.BOOKING_CONFIRMED_CONSUMER)).isTrue();
    }

    @Test
    void unpublishedEventSurvivesRestartedPublisherPass() throws Exception {
        CreatedBooking booking = confirmBooking();
        OutboxEvent event = requireBookingConfirmed(booking.bookingId());

        jdbcTemplate.update(
                "UPDATE outbox_events SET rabbit_next_retry_at = NOW() + INTERVAL '1 hour' WHERE id = ?",
                event.getId());
        assertThat(publisherService.publishPendingBookingConfirmed().published()).isZero();
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getRabbitPublishedAt()).isNull();
        assertThat(ticketRepository.count()).isZero();

        jdbcTemplate.update(
                "UPDATE outbox_events SET rabbit_next_retry_at = NULL WHERE id = ?",
                event.getId());
        assertThat(publisherService.publishPendingBookingConfirmed().published()).isEqualTo(1);
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getRabbitPublishedAt()).isNotNull();
        awaitUntil(() -> ticketRepository.count() == 1, Duration.ofSeconds(15));
    }

    private CreatedBooking confirmBooking() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
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

    private CreatedBooking createPendingBooking(int seatCount) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        TripFixture trip = createTrip("RMBUS-" + n, "RMRT-" + n, Math.max(seatCount, 2));
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
                                  "idempotencyKey":"book-rm-%d",
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

    private static MessageProperties jsonProperties(UUID eventId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(eventId.toString());
        return properties;
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    private static void execRabbit(String... command) throws Exception {
        var result = RABBIT.execInContainer(command);
        assertThat(result.getExitCode())
                .as(result.getStdout() + result.getStderr())
                .isZero();
    }

    private void awaitRabbitReady() {
        awaitUntil(() -> {
            try {
                rabbitTemplate.execute(channel -> {
                    channel.exchangeDeclarePassive(rabbitMqProperties.getExchange());
                    return true;
                });
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }, Duration.ofSeconds(30));
    }

    private record CreatedBooking(UUID bookingId) {
    }

    private record TripFixture(UUID tripId, List<UUID> stopIdsBySequence, List<UUID> availableSeatIds) {
        UUID stopId(int sequence) {
            return stopIdsBySequence.get(sequence);
        }
    }
}
