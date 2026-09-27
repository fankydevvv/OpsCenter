package com.opscenter.identity.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import com.opscenter.identity.application.UserDetail;
import com.opscenter.identity.application.UserService;
import com.opscenter.identity.application.UserSummary;
import com.opscenter.identity.domain.UserStatus;
import com.opscenter.shared.api.PageResponse;
import com.opscenter.shared.application.IdempotentResult;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/users} (04-API §4 plus the additive endpoints of D-08 and D-26).
 * <p>
 * Each method names the permission code it requires in {@code @PreAuthorize}; the code is the
 * same string that is seeded in {@code permissions} and carried in the JWT (D-06), which is how
 * 01-SRS FR-IAM-04 "permission enforced at the backend" is met regardless of what the UI hides.
 * Pagination parameters ({@code page}, {@code size}, {@code sort}) bind to {@link Pageable}
 * automatically (04-API §2.4); the service restricts {@code sort} to a whitelist of properties.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    /** Optional header that makes {@code POST} safe to retry (04-API §2.5, D-13). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user.read')")
    public PageResponse<UserSummary> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) UserStatus status,
                                          Pageable pageable) {
        return PageResponse.from(users.list(q, status, pageable));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user.create')")
    public ResponseEntity<UserDetail> create(@Valid @RequestBody CreateUserRequest request,
                                             @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                                             @Size(max = 200) String idempotencyKey) {
        IdempotentResult<UserDetail> result = users.create(request.toCommand(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.value());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user.read')")
    public UserDetail get(@PathVariable UUID id) {
        return users.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('user.update')")
    public UserDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest request) {
        return users.update(id, request.toCommand());
    }

    /**
     * Soft delete (D-26): the account becomes {@code DISABLED} with {@code deleted_at} set and all
     * its sessions die. Requires the dedicated {@code user.delete} permission (ADMIN by default).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('user.delete')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        users.delete(id);
    }

    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAuthority('user.lock')")
    public UserDetail lock(@PathVariable UUID id, @Valid @RequestBody(required = false) LockUserRequest request) {
        return users.lock(id, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('user.lock')")
    public UserDetail unlock(@PathVariable UUID id) {
        return users.unlock(id);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user.role.assign')")
    public UserDetail assignRoles(@PathVariable UUID id, @Valid @RequestBody AssignRolesRequest request) {
        return users.assignRoles(id, request.roleCodes());
    }
}
