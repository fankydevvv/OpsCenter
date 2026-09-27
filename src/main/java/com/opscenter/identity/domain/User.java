package com.opscenter.identity.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import com.opscenter.shared.domain.AuditableEntity;
import com.opscenter.shared.domain.BusinessRuleException;

import org.hibernate.annotations.BatchSize;

/**
 * A local account ({@code users} + {@code user_roles}, 03-DB §5.1/§5.4, 01-SRS FR-IAM-01).
 * <p>
 * The entity owns the account rules so that no controller or service can put it into an
 * impossible state: only an {@code ACTIVE} user can be locked, only a {@code LOCKED} user can be
 * unlocked, and a soft delete is expressed as {@code DISABLED + deleted_at} (D-10). It stores a
 * password <em>hash</em> only, produced by the {@link PasswordHasher} port - the entity never sees
 * a raw password (03-DB §30). Username and email are normalised to lower case so uniqueness and
 * login lookups are case-insensitive.
 * <p>
 * Roles are a lazy many-to-many; {@code @BatchSize} lets a page of users load their roles with a
 * couple of queries instead of one per user (the classic N+1 problem).
 */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private UserStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @BatchSize(size = 50)
    private Set<Role> roles = new HashSet<>();

    protected User() {
    }

    private User(UUID id, String username, String email, String passwordHash, String displayName) {
        super(id);
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.status = UserStatus.ACTIVE;
    }

    /**
     * Factory for a new active account. Takes the already hashed password so that the domain
     * cannot accidentally persist a plaintext one.
     */
    public static User register(String username, String email, String passwordHash, String displayName) {
        return new User(UUID.randomUUID(), normalize(username), normalize(email),
                Objects.requireNonNull(passwordHash, "passwordHash"),
                Objects.requireNonNull(displayName, "displayName").trim());
    }

    /** Lower-cases and trims a username/email so lookups and uniqueness are case-insensitive. */
    public static String normalize(String value) {
        return Objects.requireNonNull(value, "value").trim().toLowerCase(Locale.ROOT);
    }

    // --- account lifecycle ------------------------------------------------------------------

    /** Administrator lock (01-SRS FR-IAM-03). Callers must also revoke the sessions (D-03). */
    public void lock() {
        if (status != UserStatus.ACTIVE) {
            throw new BusinessRuleException(IdentityErrorCodes.USER_NOT_ACTIVE,
                    "Only an ACTIVE user can be locked (current status: " + status + ")");
        }
        this.status = UserStatus.LOCKED;
    }

    public void unlock() {
        if (status != UserStatus.LOCKED) {
            throw new BusinessRuleException(IdentityErrorCodes.USER_NOT_LOCKED,
                    "User is not locked (current status: " + status + ")");
        }
        this.status = UserStatus.ACTIVE;
    }

    /** Soft delete (03-DB §3.3): the row stays for audit/FK integrity but the account is gone. */
    public void disable(Instant now) {
        this.status = UserStatus.DISABLED;
        this.deletedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Enforces "account disabled/locked bị chặn" (04-API §3.1). Called only after the password
     * matched so that the specific reason is never leaked to a caller who does not know it (D-09).
     */
    public void assertCanAuthenticate() {
        switch (status) {
            case LOCKED -> throw AuthenticationFailedException.accountLocked();
            case DISABLED -> throw AuthenticationFailedException.accountDisabled();
            case ACTIVE -> {
                // allowed
            }
        }
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    // --- profile ----------------------------------------------------------------------------

    public void changeEmail(String newEmail) {
        this.email = normalize(newEmail);
    }

    public void changeDisplayName(String newDisplayName) {
        this.displayName = Objects.requireNonNull(newDisplayName, "displayName").trim();
    }

    public void replaceRoles(Collection<Role> newRoles) {
        this.roles.clear();
        this.roles.addAll(newRoles);
    }

    /** Sorted role codes, e.g. {@code [ADMIN]}, for the JWT {@code roles} claim and DTOs. */
    public List<String> roleCodes() {
        return roles.stream().map(Role::getCode).sorted().toList();
    }

    /** Union of the permission codes of all roles - the JWT {@code permissions} claim (D-06). */
    public List<String> permissionCodes() {
        return roles.stream()
                .flatMap(role -> role.permissionCodes().stream())
                .distinct()
                .sorted()
                .toList();
    }

    // --- getters ----------------------------------------------------------------------------

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Set<Role> getRoles() {
        return Set.copyOf(roles);
    }
}
