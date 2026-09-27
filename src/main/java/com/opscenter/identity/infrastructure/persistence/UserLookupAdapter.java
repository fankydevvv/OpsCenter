package com.opscenter.identity.infrastructure.persistence;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.opscenter.identity.application.UserLookup;
import com.opscenter.identity.application.UserRef;
import com.opscenter.identity.domain.User;
import com.opscenter.identity.domain.UserStatus;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link UserLookup} over {@link UserRepository}; maps entities to {@link UserRef} before they leave identity. */
@Component
public class UserLookupAdapter implements UserLookup {

    private final UserRepository users;

    public UserLookupAdapter(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserRef> findUser(UUID userId) {
        return userId == null ? Optional.empty() : users.findById(userId).map(UserLookupAdapter::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, UserRef> findUsers(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds.stream().distinct().toList()).stream()
                .map(UserLookupAdapter::toRef)
                .collect(Collectors.toMap(UserRef::id, Function.identity()));
    }

    private static UserRef toRef(User user) {
        return new UserRef(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getStatus() == UserStatus.ACTIVE && !user.isDeleted(), user.isDeleted());
    }
}
