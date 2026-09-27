package com.opscenter.identity.api;

import java.util.List;
import java.util.UUID;

import com.opscenter.identity.application.AuthenticatedUser;
import com.opscenter.identity.application.AuthenticationService;
import com.opscenter.identity.application.CurrentUserProfile;
import com.opscenter.identity.application.LoginCommand;
import com.opscenter.identity.application.LoginResult;
import com.opscenter.identity.domain.AuthenticationFailedException;
import com.opscenter.identity.domain.TooManyLoginAttemptsException;
import com.opscenter.identity.domain.UserStatus;
import com.opscenter.shared.infrastructure.web.ApiExceptionHandler;
import com.opscenter.support.LogCapture;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code /api/v1/auth} with the service mocked and the real security chain:
 * validation -> 400 with {@code fieldErrors}, business failures -> 401/429 contract bodies,
 * public vs. authenticated endpoints (07-TC §22 for the auth surface).
 */
@WebMvcTest(controllers = AuthController.class)
@Import(SecuritySliceConfig.class)
@ActiveProfiles("test")
class AuthControllerWebMvcTest {

    @Autowired MockMvc mvc;
    @Autowired JwtEncoder jwtEncoder;
    @MockitoBean AuthenticationService authentication;

    private static LoginResult sampleResult() {
        return new LoginResult("access.jwt", "refresh-opaque", 1800,
                new AuthenticatedUser(UUID.randomUUID(), "admin", "Admin A", List.of("ADMIN"),
                        List.of("user.read", "user.create")));
    }

    @Test
    void login_withBlankFields_returns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "auth-val-1")
                        .content("{\"login\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.requestId").value("auth-val-1"))
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder("login", "password")));
        verify(authentication, never()).login(any());
    }

    /** 04-API §3.1 "password không bao giờ log": an over-long password fails validation without being logged. */
    @Test
    void login_withAnOversizedPassword_isRejected_andThePasswordNeverReachesTheLog() throws Exception {
        String password = "Very-Secret-Passphrase-" + "x".repeat(240);
        try (LogCapture logs = LogCapture.of(ApiExceptionHandler.class)) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"login\":\"admin\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
            assertThat(String.join("\n", logs.messages())).doesNotContain("Very-Secret-Passphrase");
        }
        verify(authentication, never()).login(any());
    }

    @Test
    void login_returnsContractBody_andPassesUserAgentToTheService() throws Exception {
        when(authentication.login(new LoginCommand("admin", "Admin@123", "JUnit/1.0"))).thenReturn(sampleResult());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "JUnit/1.0")
                        .content("{\"login\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-opaque"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.user.permissions", containsInAnyOrder("user.read", "user.create")));
    }

    @Test
    void login_invalidCredentials_isRenderedAs401ContractBody() throws Exception {
        when(authentication.login(any())).thenThrow(AuthenticationFailedException.invalidCredentials());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "auth-401")
                        .content("{\"login\":\"admin\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").value("auth-401"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
    }

    @Test
    void login_rateLimited_isRenderedAs429() throws Exception {
        when(authentication.login(any())).thenThrow(new TooManyLoginAttemptsException(5, 15));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"admin\",\"password\":\"nope\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_TOO_MANY_ATTEMPTS"));
    }

    @Test
    void refresh_isPublic_andInvalidTokenIs401() throws Exception {
        when(authentication.refresh("dead")).thenThrow(AuthenticationFailedException.refreshTokenInvalid());

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"dead\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
    }

    @Test
    void logoutAndMe_requireAToken() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
        verify(authentication, never()).logout();
    }

    @Test
    void logout_withToken_returns204_andMe_returnsProfile() throws Exception {
        String token = TestJwts.engineer(jwtEncoder, UUID.randomUUID(), UUID.randomUUID());
        when(authentication.me()).thenReturn(new CurrentUserProfile(UUID.randomUUID(), "engineer.a",
                "engineer.a@opscenter.local", "Engineer A", UserStatus.ACTIVE, List.of("ENGINEER"),
                List.of("team.read"), List.of()));

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        verify(authentication).logout();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("engineer.a"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.teams").isArray());
    }
}
