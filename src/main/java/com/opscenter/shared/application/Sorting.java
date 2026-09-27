package com.opscenter.shared.application;

import java.util.Set;

import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Guards the {@code sort} query parameter of paginated endpoints (04-API §2.4).
 * <p>
 * Spring Data binds {@code sort=<property>} straight to the entity, so without a whitelist a
 * client could order users by {@code passwordHash} (an oracle over the hashes) or trigger a 500
 * with a property that does not exist. Each list use case therefore names the properties it is
 * willing to sort by; anything else is a {@code 400 VALIDATION_FAILED}.
 */
public final class Sorting {

    private Sorting() {
    }

    /**
     * @param pageable        what the client asked for
     * @param allowed         entity properties that may appear in {@code sort}
     * @param defaultProperty ordering applied when the client sent no {@code sort}
     * @return a {@link Pageable} that is safe to hand to a repository
     */
    public static Pageable restrict(Pageable pageable, Set<String> allowed, String defaultProperty) {
        if (!pageable.getSort().isSorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(defaultProperty));
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidRequestException(ErrorCodes.VALIDATION_FAILED,
                        "Unsupported sort property '" + order.getProperty() + "'; allowed: " + allowed);
            }
        }
        return pageable;
    }
}
