package in.bluebustickets.bluebus.identity.api;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.Role;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.UserRole;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.bearer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminRbacPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private TestAccessTokenFactory tokens;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;

    @Test
    void superAdminCanCallExistingAdminApis() throws Exception {
        IssuedUser superAdmin = tokens.issueSuperAdmin();
        mockMvc.perform(get("/api/v1/admin/locations").with(bearer(superAdmin.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/operators").with(bearer(superAdmin.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(bearer(superAdmin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(operatorBody("Super Admin Co")))
                .andExpect(status().isCreated());
    }

    @Test
    void adminCanCallExistingAdminApis() throws Exception {
        IssuedUser admin = tokens.issuePlatformAdmin();
        mockMvc.perform(get("/api/v1/admin/locations").with(bearer(admin.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/bus-types").with(bearer(admin.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/bus-types")
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"RBAC_ADMIN_BT","displayName":"RBAC Admin Bus Type"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void customerJwtIsForbiddenOnExistingAdminApis() throws Exception {
        IssuedUser customer = tokens.issueCustomer();
        assertAdminMutationsForbidden(customer.accessToken());
        mockMvc.perform(get("/api/v1/admin/locations").with(bearer(customer.accessToken())))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorAdminJwtIsForbiddenOnExistingAdminApis() throws Exception {
        IssuedUser operatorAdmin = tokens.issueOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        mockMvc.perform(get("/api/v1/admin/operators").with(bearer(operatorAdmin.accessToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(bearer(operatorAdmin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(operatorBody("Operator Admin Co")))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorStaffJwtIsForbiddenOnExistingAdminApis() throws Exception {
        IssuedUser staff = tokens.issueOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));
        mockMvc.perform(get("/api/v1/admin/trips").with(bearer(staff.accessToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/locations")
                        .with(bearer(staff.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"Telangana","city":"Hyderabad"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestReceivesUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/locations").with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(operatorBody("Anonymous Co")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void suspendedUserWithValidJwtReceivesUnauthorizedOnProtectedApis() throws Exception {
        IssuedUser suspended = tokens.issuePlatformUser(
                RoleCode.ADMIN, List.of("ADMIN"), UserStatus.SUSPENDED);
        mockMvc.perform(get("/api/v1/admin/locations").with(bearer(suspended.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").with(bearer(suspended.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveUserWithValidJwtReceivesUnauthorizedOnProtectedApis() throws Exception {
        IssuedUser inactive = tokens.issuePlatformUser(
                RoleCode.CUSTOMER, List.of("CUSTOMER"), UserStatus.INACTIVE);
        mockMvc.perform(get("/api/v1/auth/me").with(bearer(inactive.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bookings").with(bearer(inactive.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleAdminJwtIsForbiddenAfterRoleRemovedFromDatabase() throws Exception {
        IssuedUser admin = tokens.issuePlatformUser(
                RoleCode.ADMIN, List.of("ADMIN"), UserStatus.ACTIVE);
        mockMvc.perform(get("/api/v1/admin/locations").with(bearer(admin.accessToken())))
                .andExpect(status().isOk());

        userRoleRepository.deleteAll(userRoleRepository.findByUserIdWithRole(admin.user().getId()));
        Role customerRole = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        userRoleRepository.saveAndFlush(new UserRole(admin.user(), customerRole));

        mockMvc.perform(get("/api/v1/admin/locations").with(bearer(admin.accessToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(operatorBody("Stale Admin Co")))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerJwtCannotMutateExistingAdminResources() throws Exception {
        IssuedUser customer = tokens.issueCustomer();
        assertAdminMutationsForbidden(customer.accessToken());
    }

    private void assertAdminMutationsForbidden(String accessToken) throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(operatorBody("Customer Privilege Co")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/bus-types")
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"RBAC_CUST_BT","displayName":"Should Fail"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/buses")
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "busTypeId":"%s",
                                  "seatLayoutId":"%s",
                                  "registrationNumber":"RBAC-CUST-01"
                                }
                                """.formatted(missing, missing, missing)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/buses/{id}", missing)
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"Hijacked","busTypeId":"%s","seatLayoutId":"%s"}
                                """.formatted(missing, missing)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/routes/{id}", missing)
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Hijacked","sourceLocationId":"%s","destinationLocationId":"%s"}
                                """.formatted(missing, missing)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/trips")
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "busId":"%s",
                                  "routeId":"%s",
                                  "scheduledDepartureAt":"2026-12-01T10:00:00Z",
                                  "scheduledArrivalAt":"2026-12-01T16:00:00Z",
                                  "baseFare":1000,
                                  "bookingOpensAt":"2026-11-01T10:00:00Z",
                                  "bookingClosesAt":"2026-12-01T09:00:00Z",
                                  "timeZone":"Asia/Kolkata"
                                }
                                """.formatted(missing, missing)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/trips/{id}", missing)
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseFare":1,"bookingOpensAt":"2026-11-01T10:00:00Z","bookingClosesAt":"2026-12-01T09:00:00Z"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/locations")
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"Telangana","city":"Hijack"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/seat-layouts")
                        .with(bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operatorId":"%s",
                                  "name":"Hijack Layout",
                                  "version":1,
                                  "deckCount":1,
                                  "rowCount":1,
                                  "columnCount":1
                                }
                                """.formatted(missing)))
                .andExpect(status().isForbidden());
    }

    private static String operatorBody(String displayName) {
        return """
                {"legalName":"%s Pvt Ltd","displayName":"%s"}
                """.formatted(displayName, displayName);
    }
}
