package com.opscenter.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.identity.application.CreateUserCommand;
import com.opscenter.identity.application.UserDetail;
import com.opscenter.identity.application.UserService;
import com.opscenter.identity.application.UserSummary;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.UserStatus;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;
import com.opscenter.shared.infrastructure.web.ApiExceptionHandler;
import com.opscenter.support.LogCapture;
import com.opscenter.support.SecuritySliceConfig;
import com.opscenter.support.TestJwts;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security and validation contract of {@code /api/v1/users}: 401 without a token, 403 without the
 * permission (TC-RBAC-001/003 at the slice level), 400 with {@code fieldErrors}, 201/200 for
 * create and idempotent replay, and business errors rendered with their codes.
 */
@WebMvcTest(controllers = UserController.class)
@Import(SecuritySliceConfig.class)
@ActiveProfiles("test")
class UserControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean UserService users;

    private static UserDetail detail(UUID id, String username) {
        return new UserDetail(id, username, username + "@opscenter.local", "Test", UserStatus.ACTIVE,
                List.of("ENGINEER"), null, Instant.now(), Instant.now(), 0, List.of());
    }

    private String admin() {
        return TestJwts.admin(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());
    }

    private String engineer() {
        return TestJwts.engineer(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void withoutToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        verify(users, never()).list(any(), any(), any());
    }

    /**
     * Note for readers: {@code @PreAuthorize} is checked when the controller <em>method</em> is
     * invoked, i.e. after the request body was bound and validated. An unauthorised caller who also
     * sends an invalid body therefore receives 400, not 403 - which is why this test posts a valid
     * body. Either way no business code runs without the permission (FR-IAM-04).
     */
    @Test
    void TC_RBAC_001_engineerWithoutUserReadPermission_returns403() throws Exception {
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + engineer()).header("X-Request-Id", "rbac-u1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.requestId").value("rbac-u1"));
        mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + engineer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new.user\",\"email\":\"new.user@opscenter.local\",\"displayName\":\"New\","
                                + "\"password\":\"Str0ng-Passw0rd!\",\"roleCodes\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RBAC_PERMISSION_DENIED"));
        verify(users, never()).list(any(), any(), any());
        verify(users, never()).create(any(), any());
    }

    @Test
    void list_bindsFiltersAndPageable_andWrapsInPageResponse() throws Exception {
        when(users.list(eq("eng"), eq(UserStatus.ACTIVE), any(Pageable.class))).thenReturn(new PageImpl<>(
                List.of(new UserSummary(UUID.randomUUID(), "engineer.a", "e@x", "E", UserStatus.ACTIVE,
                        List.of("ENGINEER"), Instant.now())),
                PageRequest.of(1, 5), 11));

        mvc.perform(get("/api/v1/users?q=eng&status=ACTIVE&page=1&size=5&sort=username,desc")
                        .header("Authorization", "Bearer " + admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].username").value("engineer.a"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalItems").value(11))
                .andExpect(jsonPath("$.totalPages").value(3));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(users).list(eq("eng"), eq(UserStatus.ACTIVE), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort().getOrderFor("username").isDescending()).isTrue();
    }

    @Test
    void create_withInvalidBody_returns400WithFieldErrors_andNeverLogsThePassword() throws Exception {
        String tooShortPassword = "s3cr3t!";
        try (LogCapture logs = LogCapture.of(ApiExceptionHandler.class)) {
            mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + admin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"a b\",\"email\":\"not-an-email\",\"displayName\":\"\",\"password\":\""
                                    + tooShortPassword + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[*].field",
                            hasItems("username", "email", "displayName", "password", "roleCodes")));
            // 04-API §18: the rejected value of the password field must not reach the log
            assertThat(String.join("\n", logs.messages())).contains("password").doesNotContain(tooShortPassword);
        }
        verify(users, never()).create(any(), any());
    }

    @Test
    void create_returns201_andReplayReturns200WithTheSameBody() throws Exception {
        UUID id = UUID.randomUUID();
        when(users.create(any(CreateUserCommand.class), eq("key-1")))
                .thenReturn(IdempotentResult.created(detail(id, "new.user")))
                .thenReturn(IdempotentResult.replayed(detail(id, "new.user")));
        String body = "{\"username\":\"new.user\",\"email\":\"new.user@opscenter.local\",\"displayName\":\"New\","
                + "\"password\":\"Str0ng-Passw0rd!\",\"roleCodes\":[\"ENGINEER\"]}";

        mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + admin())
                        .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.username").value("new.user"));
        mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + admin())
                        .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        ArgumentCaptor<CreateUserCommand> command = ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(users, times(2)).create(command.capture(), eq("key-1"));
        assertThat(command.getValue().roleCodes()).containsExactly("ENGINEER");
        assertThat(command.getValue().password()).isEqualTo("Str0ng-Passw0rd!");
    }

    @Test
    void businessErrors_areRenderedWithTheirCodes() throws Exception {
        UUID id = UUID.randomUUID();
        when(users.create(any(), isNull()))
                .thenThrow(new ConflictException(IdentityErrorCodes.USER_USERNAME_TAKEN, "taken"));
        when(users.get(id)).thenThrow(new NotFoundException(IdentityErrorCodes.USER_NOT_FOUND, "missing"));
        when(users.lock(eq(id), any()))
                .thenThrow(new BusinessRuleException(IdentityErrorCodes.USER_CANNOT_LOCK_SELF, "self"));
        String body = "{\"username\":\"dup\",\"email\":\"dup@opscenter.local\",\"displayName\":\"Dup\","
                + "\"password\":\"Str0ng-Passw0rd!\",\"roleCodes\":[]}";

        mvc.perform(post("/api/v1/users").header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_USERNAME_TAKEN"));
        mvc.perform(get("/api/v1/users/" + id).header("Authorization", "Bearer " + admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mvc.perform(post("/api/v1/users/" + id + "/lock").header("Authorization", "Bearer " + admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("USER_CANNOT_LOCK_SELF"));
    }

    @Test
    void patch_withoutVersion_returns400() throws Exception {
        mvc.perform(patch("/api/v1/users/" + UUID.randomUUID()).header("Authorization", "Bearer " + admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"X\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("version"));
        verify(users, never()).update(any(), any());
    }

    @Test
    void invalidUuidInPath_returns400() throws Exception {
        mvc.perform(get("/api/v1/users/not-a-uuid").header("Authorization", "Bearer " + admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
