package com.opscenter.shared.application;

import java.util.Set;

import com.opscenter.shared.domain.ErrorCodes;
import com.opscenter.shared.domain.InvalidRequestException;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 04-API §2.4: only whitelisted properties may be sorted by; no sort = the use case's default. */
class SortingTest {

    private static final Set<String> ALLOWED = Set.of("username", "createdAt");

    @Test
    void appliesTheDefaultWhenTheClientSentNoSort() {
        Pageable result = Sorting.restrict(PageRequest.of(1, 5), ALLOWED, "username");

        assertThat(result.getPageNumber()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(5);
        assertThat(result.getSort().getOrderFor("username")).isNotNull();
    }

    @Test
    void keepsAllowedSorts_andRejectsAnythingElse() {
        Pageable allowed = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(Sorting.restrict(allowed, ALLOWED, "username")).isSameAs(allowed);

        assertThatThrownBy(() -> Sorting.restrict(PageRequest.of(0, 20, Sort.by("passwordHash")), ALLOWED, "username"))
                .isInstanceOf(InvalidRequestException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCodes.VALIDATION_FAILED)
                .hasMessageContaining("passwordHash");
    }
}
