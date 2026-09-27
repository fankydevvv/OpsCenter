package com.opscenter.organization.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.organization.application.AddTeamMemberCommand;
import com.opscenter.organization.application.TeamDetail;
import com.opscenter.organization.application.TeamService;
import com.opscenter.organization.domain.MasterDataStatus;
import com.opscenter.organization.domain.MemberType;
import com.opscenter.organization.domain.OrganizationErrorCodes;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security and validation contract of {@code /api/v1/teams} (04-API §5/§21): the permission codes
 * of blueprint §7.3 are enforced, bodies are validated, and business errors carry their codes.
 */
@WebMvcTest(controllers = TeamController.class)
@Import(SecuritySliceConfig.class)
@ActiveProfiles("test")
class TeamControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean TeamService teams;

    private String coordinator() {
        return TestJwts.issue(jwtEncoder, UUID.randomUUID(), UUID.randomUUID(), "coordinator", List.of("COORDINATOR"),
                List.of("user.read", "role.read", "permission.read", "organization.read", "team.read", "team.create",
                        "team.update", "team.member.manage"), Instant.now(), Duration.ofMinutes(30));
    }

    private String engineer() {
        return TestJwts.engineer(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());
    }

    private static TeamDetail detail(UUID id) {
        return new TeamDetail(id, UUID.randomUUID(), "PAYMENT", "Team Payment", null, "DEV", true,
                MasterDataStatus.ACTIVE, List.of(), Instant.now(), Instant.now(), 0);
    }

    @Test
    void engineer_canReadTeams_butCannotCreateOrManageMembers() throws Exception {
        when(teams.get(any())).thenReturn(detail(UUID.randomUUID()));

        mvc.perform(get("/api/v1/teams/" + UUID.randomUUID()).header("Authorization", "Bearer " + engineer()))
                .andExpect(status().isOk());
        // valid body on purpose: @PreAuthorize runs after body validation, so an invalid body would be 400
        mvc.perform(post("/api/v1/teams").header("Authorization", "Bearer " + engineer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"NEWTEAM\",\"name\":\"New team\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        mvc.perform(delete("/api/v1/teams/" + UUID.randomUUID() + "/members/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + engineer()))
                .andExpect(status().isForbidden());
        verify(teams, never()).create(any(), any());
        verify(teams, never()).removeMember(any(), any());
    }

    @Test
    void create_validatesBody_andReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(teams.create(any(), eq("team-key"))).thenReturn(IdempotentResult.created(detail(id)));

        mvc.perform(post("/api/v1/teams").header("Authorization", "Bearer " + coordinator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"1bad\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("code", "name")));

        mvc.perform(post("/api/v1/teams").header("Authorization", "Bearer " + coordinator())
                        .header("Idempotency-Key", "team-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"PAYMENT\",\"name\":\"Team Payment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.code").value("PAYMENT"));
    }

    @Test
    void addMember_rejectsUnknownMemberType_andMissingUserId() throws Exception {
        UUID teamId = UUID.randomUUID();

        mvc.perform(post("/api/v1/teams/" + teamId + "/members").header("Authorization", "Bearer " + coordinator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + UUID.randomUUID() + "\",\"memberType\":\"BOSS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
        mvc.perform(post("/api/v1/teams/" + teamId + "/members").header("Authorization", "Bearer " + coordinator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"memberType\":\"PRIMARY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("userId"));
        verify(teams, never()).addMember(any(), any());
    }

    @Test
    void addMember_returns201_andConflictIsRendered() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(teams.addMember(eq(teamId), any(AddTeamMemberCommand.class)))
                .thenReturn(detail(teamId))
                .thenThrow(new ConflictException(OrganizationErrorCodes.TEAM_MEMBER_EXISTS, "dup"));
        String body = "{\"userId\":\"" + userId + "\",\"memberType\":\"ON_CALL\",\"isPrimary\":false}";

        mvc.perform(post("/api/v1/teams/" + teamId + "/members").header("Authorization", "Bearer " + coordinator())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/teams/" + teamId + "/members").header("Authorization", "Bearer " + coordinator())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_MEMBER_EXISTS"));
        verify(teams, times(2)).addMember(eq(teamId),
                eq(new AddTeamMemberCommand(userId, MemberType.ON_CALL, false, null)));
    }

    @Test
    void removeMember_returns204() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        mvc.perform(delete("/api/v1/teams/" + teamId + "/members/" + userId).header("Authorization", "Bearer " + coordinator()))
                .andExpect(status().isNoContent());
        verify(teams).removeMember(teamId, userId);
    }
}
