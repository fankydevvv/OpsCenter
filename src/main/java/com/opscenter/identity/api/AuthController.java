package com.opscenter.identity.api;

import jakarta.validation.Valid;

import com.opscenter.identity.application.AuthenticationService;
import com.opscenter.identity.application.CurrentUserProfile;
import com.opscenter.identity.application.LoginCommand;
import com.opscenter.identity.application.LoginResult;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/auth} (04-API §3). {@code login} and {@code refresh} are public in the shared
 * {@code SecurityConfig}; {@code logout} and {@code me} need a valid bearer token, from which the
 * service reads the caller through the {@code CurrentUser} port. The controller only translates
 * HTTP into commands - every rule is in {@link AuthenticationService}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;

    public AuthController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/login")
    public LoginResult login(@Valid @RequestBody LoginRequest request,
                             @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return authentication.login(new LoginCommand(request.login(), request.password(), userAgent));
    }

    @PostMapping("/refresh")
    public LoginResult refresh(@Valid @RequestBody RefreshRequest request) {
        return authentication.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        authentication.logout();
    }

    @GetMapping("/me")
    public CurrentUserProfile me() {
        return authentication.me();
    }
}
