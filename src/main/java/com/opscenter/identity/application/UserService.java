package com.opscenter.identity.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.IdentityAuditActions;
import com.opscenter.identity.domain.IdentityErrorCodes;
import com.opscenter.identity.domain.PasswordHasher;
import com.opscenter.identity.domain.Role;
import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserLockedEvent;
import com.opscenter.identity.domain.UserStatus;
import com.opscenter.identity.infrastructure.persistence.RoleRepository;
import com.opscenter.identity.infrastructure.persistence.UserRepository;
import com.opscenter.identity.infrastructure.persistence.UserSpecifications;
import com.opscenter.shared.application.CurrentUser;
import com.opscenter.shared.application.IdempotencyService;
import com.opscenter.shared.application.IdempotentOperation;
import com.opscenter.shared.application.IdempotentResult;
import com.opscenter.shared.application.OutboxAppender;
import com.opscenter.shared.application.Sorting;
import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.NotFoundException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration use cases (04-API §4, 01-SRS FR-IAM-02/03, D-08, D-26).
 * <p>
 * Every mutating method is one transaction that changes the aggregate <em>and</em> writes its
 * audit line (03-DB §27); {@code lock} additionally revokes all sessions and appends the
 * {@code UserLocked} outbox event in that same transaction, so a consumer can never see an event
 * for a lock that did not happen. {@code create} is idempotent through the shared
 * {@link IdempotencyService} (D-13), which owns that transaction boundary. Uniqueness of
 * username/email is checked here to give a precise {@code 409} code; the database unique
 * constraints remain the last line of defence.
 */
@Service
public class UserService {

    private static final String RESOURCE_TYPE = "User";

    /** Properties a client may sort the user list by (04-API §2.4; never {@code passwordHash}). */
    static final Set<String> SORTABLE = Set.of("username", "email", "displayName", "status", "createdAt",
            "updatedAt", "lastLoginAt");

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHasher passwordHasher;
    private final SessionRevocationService revocation;
    private final IdempotencyService idempotency;
    private final OutboxAppender outbox;
    private final AuditRecorder audit;
    private final CurrentUser currentUser;
    private final TeamMembershipQuery teamMemberships;
    private final Clock clock;

    public UserService(UserRepository users, RoleRepository roles, PasswordHasher passwordHasher,
                       SessionRevocationService revocation, IdempotencyService idempotency, OutboxAppender outbox,
                       AuditRecorder audit, CurrentUser currentUser, TeamMembershipQuery teamMemberships, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.passwordHasher = passwordHasher;
        this.revocation = revocation;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.currentUser = currentUser;
        this.teamMemberships = teamMemberships;
        this.clock = clock;
    }

    // --- queries ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<UserSummary> list(String query, UserStatus status, Pageable pageable) {
        List<Specification<User>> filters = new ArrayList<>();
        filters.add(UserSpecifications.notDeleted());
        if (status != null) {
            filters.add(UserSpecifications.hasStatus(status));
        }
        if (query != null && !query.isBlank()) {
            filters.add(UserSpecifications.matches(query));
        }
        Pageable safe = Sorting.restrict(pageable, SORTABLE, "username");
        return users.findAll(Specification.allOf(filters), safe).map(UserSummary::from);
    }

    @Transactional(readOnly = true)
    public UserDetail get(UUID id) {
        return toDetail(load(id));
    }

    // --- commands ---------------------------------------------------------------------------

    /**
     * Not {@code @Transactional} on purpose: {@link IdempotencyService#execute} claims the key in
     * its own short transaction <em>before</em> opening the business transaction in which
     * {@link #doCreate} (and the {@code reload} for a replay) run. Opening the business
     * transaction here first would make every idempotent create hold two pooled connections.
     *
     * @param idempotencyKey optional {@code Idempotency-Key}; a replay with the same key and body
     *                       returns the user created the first time ({@code replayed = true})
     */
    public IdempotentResult<UserDetail> create(CreateUserCommand command, String idempotencyKey) {
        String requestHash = idempotency.hashOf(command.fingerprint());
        return idempotency.execute(idempotencyKey, requestHash, RESOURCE_TYPE, new IdempotentOperation<>() {
            @Override
            public UserDetail create() {
                return doCreate(command);
            }

            @Override
            public UUID resourceId(UserDetail created) {
                return created.id();
            }

            @Override
            public UserDetail reload(UUID resourceId) {
                return get(resourceId);
            }
        });
    }

    private UserDetail doCreate(CreateUserCommand command) {
        String username = User.normalize(command.username());
        String email = User.normalize(command.email());
        if (users.existsByUsername(username)) {
            throw new ConflictException(IdentityErrorCodes.USER_USERNAME_TAKEN,
                    "Username '" + username + "' is already taken");
        }
        if (users.existsByEmail(email)) {
            throw new ConflictException(IdentityErrorCodes.USER_EMAIL_TAKEN,
                    "Email '" + email + "' is already registered");
        }
        User user = User.register(username, email, passwordHasher.hash(command.password()), command.displayName());
        user.replaceRoles(resolveRoles(command.roleCodes()));
        users.save(user);
        UserDetail created = toDetail(user);
        audit.record(AuditAction.USER_CREATED, RESOURCE_TYPE, user.getId(), null, created);
        return created;
    }

    @Transactional
    public UserDetail update(UUID id, UpdateUserCommand command) {
        User user = load(id);
        user.assertVersion(command.version());
        UserDetail before = toDetail(user);
        if (command.email() != null) {
            String email = User.normalize(command.email());
            if (!email.equals(user.getEmail()) && users.existsByEmail(email)) {
                throw new ConflictException(IdentityErrorCodes.USER_EMAIL_TAKEN,
                        "Email '" + email + "' is already registered");
            }
            user.changeEmail(email);
        }
        if (command.displayName() != null) {
            user.changeDisplayName(command.displayName());
        }
        users.saveAndFlush(user);
        UserDetail after = toDetail(user);
        audit.record(AuditAction.USER_UPDATED, RESOURCE_TYPE, user.getId(), before, after);
        return after;
    }

    /**
     * Locks the account, revokes every session and refresh token immediately (D-03/D-07) and
     * publishes {@code UserLocked} through the outbox (D-14). An administrator cannot lock
     * themselves - that would be a guaranteed lock-out (422 {@code USER_CANNOT_LOCK_SELF}).
     */
    @Transactional
    public UserDetail lock(UUID id, String reason) {
        UUID actorId = currentUser.actorId().orElse(null);
        if (id.equals(actorId)) {
            throw new BusinessRuleException(IdentityErrorCodes.USER_CANNOT_LOCK_SELF,
                    "You cannot lock your own account");
        }
        User user = load(id);
        UserDetail before = toDetail(user);
        user.lock();
        revocation.revokeAllForUser(user.getId());
        users.saveAndFlush(user);
        UserDetail after = toDetail(user);
        audit.record(AuditAction.USER_LOCKED, RESOURCE_TYPE, user.getId(), before, after);
        outbox.append(UserLockedEvent.AGGREGATE_TYPE, user.getId(), UserLockedEvent.EVENT_TYPE,
                new UserLockedEvent(user.getId(), user.getUsername(), reason, actorId, clock.instant()));
        return after;
    }

    @Transactional
    public UserDetail unlock(UUID id) {
        User user = load(id);
        UserDetail before = toDetail(user);
        user.unlock();
        users.saveAndFlush(user);
        UserDetail after = toDetail(user);
        audit.record(AuditAction.USER_UNLOCKED, RESOURCE_TYPE, user.getId(), before, after);
        return after;
    }

    /**
     * Soft delete (03-DB §3.3/§29, D-26): the row is kept for foreign keys and audit history, the
     * account becomes {@code DISABLED} and all its sessions die. Not reversible through the API,
     * and - like lock - never applicable to the caller's own account.
     */
    @Transactional
    public void delete(UUID id) {
        UUID actorId = currentUser.actorId().orElse(null);
        if (id.equals(actorId)) {
            throw new BusinessRuleException(IdentityErrorCodes.USER_CANNOT_DELETE_SELF,
                    "You cannot delete your own account");
        }
        User user = load(id);
        UserDetail before = toDetail(user);
        user.disable(clock.instant());
        revocation.revokeAllForUser(user.getId());
        users.saveAndFlush(user);
        audit.record(IdentityAuditActions.USER_DELETED, RESOURCE_TYPE, user.getId(), before, toDetail(user));
    }

    @Transactional
    public UserDetail assignRoles(UUID id, List<String> roleCodes) {
        User user = load(id);
        UserDetail before = toDetail(user);
        user.replaceRoles(resolveRoles(roleCodes));
        users.saveAndFlush(user);
        UserDetail after = toDetail(user);
        audit.record(AuditAction.USER_ROLES_CHANGED, RESOURCE_TYPE, user.getId(), before, after);
        return after;
    }

    // --- helpers ----------------------------------------------------------------------------

    private User load(UUID id) {
        return users.findById(id)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new NotFoundException(IdentityErrorCodes.USER_NOT_FOUND,
                        "User " + id + " not found"));
    }

    private Set<Role> resolveRoles(List<String> roleCodes) {
        Set<String> wanted = Set.copyOf(roleCodes.stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .toList());
        List<Role> found = wanted.isEmpty() ? List.of() : roles.findByCodeIn(wanted);
        if (found.size() != wanted.size()) {
            List<String> missing = new ArrayList<>(wanted);
            found.forEach(role -> missing.remove(role.getCode()));
            throw new NotFoundException(IdentityErrorCodes.ROLE_NOT_FOUND, "Unknown role code(s): " + missing);
        }
        return Set.copyOf(found);
    }

    private UserDetail toDetail(User user) {
        return UserDetail.from(user, teamMemberships.membershipsOf(user.getId()));
    }
}
