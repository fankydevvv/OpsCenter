package com.opscenter.identity.infrastructure.bootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.opscenter.audit.application.AuditRecorder;
import com.opscenter.audit.domain.AuditAction;
import com.opscenter.identity.domain.PasswordHasher;
import com.opscenter.identity.domain.Role;
import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserStatus;
import com.opscenter.identity.infrastructure.persistence.RoleRepository;
import com.opscenter.identity.infrastructure.persistence.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Creates the first {@code ADMIN} account at start-up when none exists (D-27).
 * <p>
 * WHY: the seed migration V005 deliberately contains no user, so a DEMO/PROD database never holds
 * an account with a password that is printed in the repository (05-DEPLOY §9, §19). Somebody still
 * has to be able to log in on a fresh database, so the operator passes a password of their own
 * choice once through {@code OPSCENTER_BOOTSTRAP_ADMIN_PASSWORD}; after the account exists the
 * variable is ignored and can be removed. The runner is idempotent and audited like any other
 * user creation (actor {@code null} = system, D-11).
 */
@Component
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class AdminAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountBootstrap.class);

    static final String ADMIN_ROLE = "ADMIN";

    private final BootstrapAdminProperties properties;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordHasher passwordHasher;
    private final AuditRecorder audit;
    private final TransactionTemplate transactions;

    public AdminAccountBootstrap(BootstrapAdminProperties properties, UserRepository users, RoleRepository roles,
                                 PasswordHasher passwordHasher, AuditRecorder audit, TransactionTemplate transactions) {
        this.properties = properties;
        this.users = users;
        this.roles = roles;
        this.passwordHasher = passwordHasher;
        this.audit = audit;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** @return {@code true} when an account was created in this call */
    boolean bootstrap() {
        if (users.countWithRoleAndStatus(ADMIN_ROLE, UserStatus.ACTIVE) > 0) {
            log.debug("An active ADMIN exists; bootstrap administrator not needed");
            return false;
        }
        String password = properties.password();
        if (!StringUtils.hasText(password)) {
            log.warn("No ACTIVE user with role ADMIN exists and OPSCENTER_BOOTSTRAP_ADMIN_PASSWORD is not set: "
                    + "nobody can log in. Set the variable (>= {} characters) and restart; see README §10.",
                    BootstrapAdminProperties.MIN_PASSWORD_LENGTH);
            return false;
        }
        if (password.length() < BootstrapAdminProperties.MIN_PASSWORD_LENGTH) {
            log.error("OPSCENTER_BOOTSTRAP_ADMIN_PASSWORD is shorter than {} characters; bootstrap administrator "
                    + "NOT created", BootstrapAdminProperties.MIN_PASSWORD_LENGTH);
            return false;
        }
        String username = User.normalize(properties.username());
        Boolean created = transactions.execute(status -> {
            if (users.existsByUsername(username)) {
                log.error("Username '{}' already exists but is not an active ADMIN; bootstrap administrator NOT "
                        + "created - unlock or fix that account instead", username);
                return false;
            }
            Role admin = roles.findByCode(ADMIN_ROLE)
                    .orElseThrow(() -> new IllegalStateException("Role ADMIN is not seeded (V005)"));
            User user = User.register(username, properties.email(), passwordHasher.hash(password),
                    properties.displayName());
            user.replaceRoles(Set.of(admin));
            users.save(user);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("username", user.getUsername());
            after.put("email", user.getEmail());
            after.put("displayName", user.getDisplayName());
            after.put("status", user.getStatus().name());
            after.put("roles", List.of(ADMIN_ROLE));
            after.put("source", "bootstrap");
            audit.record(AuditAction.USER_CREATED, "User", user.getId(), null, after, null, null);
            log.info("Bootstrap administrator '{}' created from OPSCENTER_BOOTSTRAP_ADMIN_PASSWORD; the variable "
                    + "is no longer needed", username);
            return true;
        });
        return Boolean.TRUE.equals(created);
    }
}
