package in.bluebustickets.bluebus.operator.api;

import java.util.List;
import java.util.UUID;

import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedOperatorMember;
import in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.IssuedUser;
import in.bluebustickets.bluebus.identity.domain.RoleCode;
import in.bluebustickets.bluebus.identity.domain.UserStatus;
import in.bluebustickets.bluebus.identity.repository.RoleRepository;
import in.bluebustickets.bluebus.identity.repository.UserRepository;
import in.bluebustickets.bluebus.operator.domain.Operator;
import in.bluebustickets.bluebus.operator.domain.OperatorUser;
import in.bluebustickets.bluebus.operator.domain.OperatorUserId;
import in.bluebustickets.bluebus.operator.repository.OperatorRepository;
import in.bluebustickets.bluebus.operator.repository.OperatorUserRepository;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static in.bluebustickets.bluebus.foundation.security.TestAccessTokenFactory.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperatorPortalAuthorizationPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestAccessTokenFactory tokens;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private OperatorRepository operatorRepository;
    @Autowired private OperatorUserRepository operatorUserRepository;

    @Test
    void activeMemberSeesOwnActiveMemberships() throws Exception {
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));

        mockMvc.perform(get("/api/v1/auth/operator-memberships").with(bearer(member.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].operatorId").value(member.operator().getId().toString()))
                .andExpect(jsonPath("$[0].operatorDisplayName").value(member.operator().getDisplayName()))
                .andExpect(jsonPath("$[0].role").value("OPERATOR_ADMIN"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void inactiveMembershipsAndOperatorsAreOmittedFromMemberships() throws Exception {
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));
        Operator inactiveMembershipOperator = tokens.persistActiveOperator();
        Operator inactiveOperator = tokens.persistActiveOperator();
        OperatorUser inactiveMembership = tokens.attachMembership(
                inactiveMembershipOperator, member.user(), RoleCode.OPERATOR_STAFF);
        tokens.attachMembership(inactiveOperator, member.user(), RoleCode.OPERATOR_ADMIN);

        inactiveMembership.deactivate();
        operatorUserRepository.saveAndFlush(inactiveMembership);
        inactiveOperator.deactivate();
        operatorRepository.saveAndFlush(inactiveOperator);

        mockMvc.perform(get("/api/v1/auth/operator-memberships").with(bearer(member.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].operatorId").value(member.operator().getId().toString()));
    }

    @Test
    void userWithNoMembershipsGetsEmptyListAndClientUserIdIsIgnored() throws Exception {
        IssuedUser customer = tokens.issueCustomer();
        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));

        mockMvc.perform(get("/api/v1/auth/operator-memberships")
                        .param("userId", other.user().getId().toString())
                        .param("operatorId", other.operator().getId().toString())
                        .with(bearer(customer.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void operatorAdminAndStaffCanReadOwnOperator() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("CUSTOMER"));
        IssuedOperatorMember staff = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_ADMIN"));

        mockMvc.perform(get("/api/v1/operator/{id}", admin.operator().getId()).with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.operator().getId().toString()))
                .andExpect(jsonPath("$.displayName").value(admin.operator().getDisplayName()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mockMvc.perform(get("/api/v1/operator/{id}", staff.operator().getId()).with(bearer(staff.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(staff.operator().getId().toString()));
    }

    @Test
    void missingMembershipUnknownOperatorAndUnrelatedMemberReceiveNotFound() throws Exception {
        IssuedUser customer = tokens.issueCustomer();
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));
        UUID unknown = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/operator/{id}", member.operator().getId()).with(bearer(customer.accessToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource was not found."));
        mockMvc.perform(get("/api/v1/operator/{id}", member.operator().getId()).with(bearer(other.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operator/{id}", unknown).with(bearer(member.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operator/{id}", unknown).with(bearer(customer.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void operatorAdminCanPatchSupportFieldsAndStaffCannot() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember staff = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));

        mockMvc.perform(patch("/api/v1/operator/{id}", admin.operator().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"ops@example.test","supportPhoneE164":"+919812345678"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportEmail").value("ops@example.test"))
                .andExpect(jsonPath("$.supportPhoneE164").value("+919812345678"))
                .andExpect(jsonPath("$.legalName").value(admin.operator().getLegalName()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/v1/operator/{id}", staff.operator().getId())
                        .with(bearer(staff.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"staff@example.test","supportPhoneE164":"+919876543210"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/operator/{id}", staff.operator().getId()).with(bearer(staff.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportEmail").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void customerPatchIsDeniedAndDoesNotMutate() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedUser customer = tokens.issueCustomer();

        mockMvc.perform(patch("/api/v1/operator/{id}", admin.operator().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"keep@example.test","supportPhoneE164":"+919800000001"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/operator/{id}", admin.operator().getId())
                        .with(bearer(customer.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"hacked@example.test","supportPhoneE164":"+919800000099"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/operator/{id}", admin.operator().getId()).with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportEmail").value("keep@example.test"))
                .andExpect(jsonPath("$.supportPhoneE164").value("+919800000001"));
    }

    @Test
    void patchBodyCannotChangeOwnershipRoleStatusOrLegalIdentity() throws Exception {
        IssuedOperatorMember admin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        Operator other = tokens.persistActiveOperator();
        String originalLegalName = admin.operator().getLegalName();
        String originalDisplayName = admin.operator().getDisplayName();

        mockMvc.perform(patch("/api/v1/operator/{id}", admin.operator().getId())
                        .with(bearer(admin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "supportEmail":"ok@example.test",
                                  "operatorId":"%s",
                                  "id":"%s",
                                  "userId":"%s",
                                  "status":"INACTIVE",
                                  "legalName":"Hacked Legal",
                                  "displayName":"Hacked Display",
                                  "role":"SUPER_ADMIN"
                                }
                                """.formatted(
                                other.getId(),
                                other.getId(),
                                admin.user().getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/operator/{id}", admin.operator().getId()).with(bearer(admin.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legalName").value(originalLegalName))
                .andExpect(jsonPath("$.displayName").value(originalDisplayName))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.supportEmail").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void deletedOrInactiveMembershipIsDeniedImmediately() throws Exception {
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = member.operator().getId();

        operatorUserRepository.deleteById(new OperatorUserId(operatorId, member.user().getId()));
        operatorUserRepository.flush();

        mockMvc.perform(get("/api/v1/operator/{id}", operatorId).with(bearer(member.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/operator/{id}", operatorId)
                        .with(bearer(member.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"gone@example.test"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void inactiveMembershipIsNotFoundAndInactiveOperatorIsForbiddenForActiveMember() throws Exception {
        IssuedOperatorMember inactiveMember = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        OperatorUser membership = operatorUserRepository
                .findByOperatorIdAndUserId(inactiveMember.operator().getId(), inactiveMember.user().getId())
                .orElseThrow();
        membership.deactivate();
        operatorUserRepository.saveAndFlush(membership);

        mockMvc.perform(get("/api/v1/operator/{id}", inactiveMember.operator().getId())
                        .with(bearer(inactiveMember.accessToken())))
                .andExpect(status().isNotFound());

        IssuedOperatorMember activeMember = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        activeMember.operator().deactivate();
        operatorRepository.saveAndFlush(activeMember.operator());

        mockMvc.perform(get("/api/v1/operator/{id}", activeMember.operator().getId())
                        .with(bearer(activeMember.accessToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/operator/{id}", activeMember.operator().getId())
                        .with(bearer(activeMember.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"closed@example.test"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendedAndInactiveUsersReceiveUnauthorized() throws Exception {
        IssuedOperatorMember suspended = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        suspended.user().setStatus(UserStatus.SUSPENDED);
        userRepository.saveAndFlush(suspended.user());

        IssuedOperatorMember inactive = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));
        inactive.user().setStatus(UserStatus.INACTIVE);
        userRepository.saveAndFlush(inactive.user());

        mockMvc.perform(get("/api/v1/operator/{id}", suspended.operator().getId())
                        .with(bearer(suspended.accessToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/operator-memberships").with(bearer(inactive.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staleJwtIsDeniedAfterMembershipRemovalOrRoleDowngrade() throws Exception {
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        UUID operatorId = member.operator().getId();

        mockMvc.perform(patch("/api/v1/operator/{id}", operatorId)
                        .with(bearer(member.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"before@example.test"}
                                """))
                .andExpect(status().isOk());

        OperatorUser membership = operatorUserRepository
                .findByOperatorIdAndUserId(operatorId, member.user().getId())
                .orElseThrow();
        membership.assignRole(roleRepository.findByCode(RoleCode.OPERATOR_STAFF).orElseThrow());
        operatorUserRepository.saveAndFlush(membership);

        mockMvc.perform(get("/api/v1/operator/{id}", operatorId).with(bearer(member.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/operator/{id}", operatorId)
                        .with(bearer(member.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"after-downgrade@example.test"}
                                """))
                .andExpect(status().isForbidden());

        operatorUserRepository.delete(membership);
        operatorUserRepository.flush();

        mockMvc.perform(get("/api/v1/operator/{id}", operatorId).with(bearer(member.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/operator/{id}", operatorId)
                        .with(bearer(member.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"after-delete@example.test"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void multiOperatorMemberCanAccessOwnTenantsOnly() throws Exception {
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        Operator operatorB = tokens.persistActiveOperator();
        Operator operatorC = tokens.persistActiveOperator();
        tokens.attachMembership(operatorB, member.user(), RoleCode.OPERATOR_STAFF);

        mockMvc.perform(get("/api/v1/auth/operator-memberships").with(bearer(member.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/operator/{id}", member.operator().getId()).with(bearer(member.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/operator/{id}", operatorB.getId()).with(bearer(member.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/operator/{id}", operatorC.getId()).with(bearer(member.accessToken())))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/operator/{id}", operatorB.getId())
                        .with(bearer(member.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"supportEmail":"b-staff@example.test"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminsAreNotAutomaticallyAuthorizedOnOperatorApis() throws Exception {
        IssuedUser admin = tokens.issuePlatformAdmin();
        IssuedUser superAdmin = tokens.issueSuperAdmin();
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));

        mockMvc.perform(get("/api/v1/operator/{id}", member.operator().getId()).with(bearer(admin.accessToken())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operator/{id}", member.operator().getId()).with(bearer(superAdmin.accessToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminApisRemainPlatformAdminOnlyAndAnonymousOperatorRequestsUnauthorized() throws Exception {
        IssuedUser admin = tokens.issuePlatformAdmin();
        IssuedUser superAdmin = tokens.issueSuperAdmin();
        IssuedOperatorMember operatorAdmin = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember operatorStaff = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_STAFF, List.of("OPERATOR_STAFF"));

        mockMvc.perform(get("/api/v1/admin/operators").with(bearer(admin.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/locations").with(bearer(superAdmin.accessToken())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/operators")
                        .with(bearer(operatorAdmin.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"Should Fail","displayName":"Should Fail"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/trips").with(bearer(operatorStaff.accessToken())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/operator/{id}", operatorAdmin.operator().getId()).with(anonymous()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/operator-memberships").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void membershipsResponseDoesNotLeakOtherUsers() throws Exception {
        IssuedOperatorMember member = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));
        IssuedOperatorMember other = tokens.issueActiveOperatorMember(
                RoleCode.OPERATOR_ADMIN, List.of("OPERATOR_ADMIN"));

        String body = mockMvc.perform(get("/api/v1/auth/operator-memberships")
                        .param("userId", other.user().getId().toString())
                        .with(bearer(member.accessToken())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode list = objectMapper.readTree(body);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("operatorId").asText()).isEqualTo(member.operator().getId().toString());
        assertThat(body).doesNotContain(other.operator().getId().toString());
        assertThat(body).doesNotContain(other.user().getId().toString());
    }
}
