package in.bluebustickets.bluebus.ticket.api;

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
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentAttemptRepository;
import in.bluebustickets.bluebus.payments.repository.PaymentProviderEventRepository;
import in.bluebustickets.bluebus.payments.repository.RefundRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class TicketPostgresIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBatteryStaple!";
    private static final String CUSTOMER_EMAIL = "ticket-a@example.test";
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
    @Autowired private OperatorRepository operatorRepository;
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
        User user = new User(CUSTOMER_EMAIL, "+919944480001", "Ticket", "Customer");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.saveAndFlush(user);
        userRoleRepository.saveAndFlush(new UserRole(user, customerRole));
        customerToken = loginToken(CUSTOMER_EMAIL);
        adminToken = testAccessTokenFactory.issuePlatformAdmin().accessToken();
    }

    @Test
    void confirmedBookingIssuesImmutableSnapshotTicket() throws Exception {
        CreatedBooking booking = createPendingBooking(2);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        BigDecimal total = bookingRepository.findById(booking.bookingId()).orElseThrow().getTotalAmount();
        String operatorNameBefore = operatorRepository.findById(
                        bookingRepository.findById(booking.bookingId()).orElseThrow().getOperatorId())
                .orElseThrow()
                .getDisplayName();

        JsonNode first = read(mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ticketNumber").value(org.hamcrest.Matchers.matchesPattern("^BB[A-Z0-9]{8}$")))
                .andExpect(jsonPath("$.amount").value(total.doubleValue()))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.operator.name").value(operatorNameBefore))
                .andExpect(jsonPath("$.journey.origin").value(org.hamcrest.Matchers.containsString("Hyderabad")))
                .andExpect(jsonPath("$.journey.destination").value(org.hamcrest.Matchers.containsString("Vijayawada")))
                .andExpect(jsonPath("$.journey.destination").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Guntur"))))
                .andExpect(jsonPath("$.passengers.length()").value(2))
                .andExpect(jsonPath("$.passengers[0].seat").exists())
                .andExpect(jsonPath("$.passengers[1].seat").exists())
                .andReturn());

        UUID ticketId = UUID.fromString(first.get("ticketId").asText());
        String ticketNumber = first.get("ticketNumber").asText();
        assertThat(ticketRepository.count()).isEqualTo(1);

        jdbcTemplate.update(
                "UPDATE operators SET display_name = ? WHERE id = ?",
                "Mutated Operator Name",
                bookingRepository.findById(booking.bookingId()).orElseThrow().getOperatorId());

        mockMvc.perform(get("/api/v1/tickets/{id}", ticketId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketNumber").value(ticketNumber))
                .andExpect(jsonPath("$.status").value(TicketStatus.ACTIVE.name()))
                .andExpect(jsonPath("$.operator.name").value(operatorNameBefore))
                .andExpect(jsonPath("$.journey.origin").value(first.get("journey").get("origin").asText()))
                .andExpect(jsonPath("$.passengers.length()").value(2));
    }

    @Test
    void invalidBookingStatusesCannotIssueTickets() throws Exception {
        CreatedBooking pending = createPendingBooking(1);
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", pending.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isConflict());

        CreatedBooking expired = createPendingBooking(1);
        jdbcTemplate.update(
                "UPDATE bookings SET status = 'EXPIRED' WHERE id = ?",
                expired.bookingId());
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", expired.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isConflict());

        CreatedBooking cancelled = createPendingBooking(1);
        bookingLifecycleService.cancelUnpaidBooking(cancelled.bookingId());
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", cancelled.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void issuanceIsIdempotentAndConcurrentSafe() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());

        JsonNode first = read(mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated())
                .andReturn());
        JsonNode second = read(mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(second.get("ticketId").asText()).isEqualTo(first.get("ticketId").asText());
        assertThat(second.get("ticketNumber").asText()).isEqualTo(first.get("ticketNumber").asText());
        assertThat(ticketRepository.count()).isEqualTo(1);

        CreatedBooking concurrent = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(concurrent.bookingId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<JsonNode>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    MvcResult result = mockMvc.perform(post("/api/v1/bookings/{id}/tickets", concurrent.bookingId())
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                            .andExpect(status().isCreated())
                            .andReturn();
                    return objectMapper.readTree(result.getResponse().getContentAsString());
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            JsonNode a = futures.get(0).get(20, TimeUnit.SECONDS);
            JsonNode b = futures.get(1).get(20, TimeUnit.SECONDS);
            assertThat(a.get("ticketId").asText()).isEqualTo(b.get("ticketId").asText());
            assertThat(a.get("ticketNumber").asText()).isEqualTo(b.get("ticketNumber").asText());
        } finally {
            executor.shutdownNow();
        }
        assertThat(ticketRepository.findByBookingId(concurrent.bookingId())).isPresent();
        assertThat(ticketRepository.count()).isEqualTo(2);
    }

    @Test
    void ownershipAndAuthenticationAreEnforced() throws Exception {
        CreatedBooking booking = createPendingBooking(1);
        bookingLifecycleService.confirmPendingPayment(booking.bookingId());
        JsonNode ticket = read(mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isCreated())
                .andReturn());
        UUID ticketId = UUID.fromString(ticket.get("ticketId").asText());

        String otherToken = createOtherCustomer();
        mockMvc.perform(get("/api/v1/tickets/{id}", ticketId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/tickets/{id}", ticketId).with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/bookings/{id}/tickets", booking.bookingId()).with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    private CreatedBooking createPendingBooking(int seatCount) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        TripFixture trip = createTrip("TKBUS-" + n, "TKRT-" + n, Math.max(seatCount, 2));
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
                                  "idempotencyKey":"book-tk-%d",
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
        User other = new User("ticket-b@example.test", "+919944480002", "Other", "Customer");
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
