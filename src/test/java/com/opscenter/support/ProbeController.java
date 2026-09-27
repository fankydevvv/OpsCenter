package com.opscenter.support;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import com.opscenter.shared.api.PageResponse;
import com.opscenter.shared.application.CurrentUser;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that make the shared kernel observable through HTTP: security rules,
 * method-level permissions, every error mapping and pagination binding.
 * <p>
 * Guarded by the {@code probe} profile so it is only registered by the {@code @WebMvcTest}
 * slices that activate it, never in a full {@code @SpringBootTest} context or in production.
 */
@Profile("probe")
@RestController
public class ProbeController {

    private final CurrentUser currentUser;

    public ProbeController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    /** Mirrors the public login path so the permitAll rule can be verified without identity code. */
    @PostMapping("/api/v1/auth/login")
    public Map<String, String> login() {
        return Map.of("probe", "login");
    }

    @GetMapping("/api/v1/probe/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of(
                "name", authentication.getName(),
                "authorities", authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList(),
                "actorId", currentUser.actorId().map(Object::toString).orElse(""),
                "sessionId", currentUser.sessionId().map(Object::toString).orElse(""),
                "username", currentUser.username().orElse(""));
    }

    @GetMapping("/api/v1/probe/users")
    @PreAuthorize("hasAuthority('user.read')")
    public Map<String, Boolean> users() {
        return Map.of("ok", true);
    }

    @GetMapping("/api/v1/probe/admin-role")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Boolean> adminRole() {
        return Map.of("ok", true);
    }

    @GetMapping("/api/v1/probe/errors/{kind}")
    public Map<String, String> error(@PathVariable String kind) {
        switch (kind) {
            case "not-found" -> throw new NotFoundException("USER_NOT_FOUND", "User does not exist");
            case "conflict" -> throw new ConflictException("TEAM_CODE_TAKEN", "Team code already used");
            case "business" -> throw new BusinessRuleException("USER_CANNOT_LOCK_SELF", "You cannot lock yourself");
            case "optimistic" -> throw new ObjectOptimisticLockingFailureException(Object.class, "stale");
            case "unavailable" -> throw new CannotCreateTransactionException("Could not open JDBC Connection");
            case "boom" -> throw new IllegalStateException("something broke - must not leak");
            default -> {
                return Map.of("kind", kind);
            }
        }
    }

    public record ValidateRequest(@NotBlank String name, @Email String email) {
    }

    @PostMapping("/api/v1/probe/validate")
    public Map<String, String> validate(@Valid @RequestBody ValidateRequest request) {
        return Map.of("name", request.name());
    }

    @GetMapping("/api/v1/probe/page")
    public PageResponse<String> page(Pageable pageable) {
        return PageResponse.from(new PageImpl<>(List.of("item"), pageable, 250));
    }
}
