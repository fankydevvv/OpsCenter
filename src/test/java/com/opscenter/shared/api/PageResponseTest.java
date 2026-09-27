package com.opscenter.shared.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** 04-API §2.4: {items, page, size, totalItems, totalPages} derived from a Spring Data page. */
class PageResponseTest {

    @Test
    void mapsSpringDataPageToContract() {
        PageImpl<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(2, 20), 120);

        PageResponse<String> response = PageResponse.from(page);

        assertThat(response.items()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalItems()).isEqualTo(120);
        assertThat(response.totalPages()).isEqualTo(6);
    }

    @Test
    void mapsEntitiesToDtosWhileWrapping() {
        PageImpl<Integer> page = new PageImpl<>(List.of(1, 2, 3), PageRequest.of(0, 3), 3);

        PageResponse<String> response = PageResponse.from(page, i -> "n" + i);

        assertThat(response.items()).containsExactly("n1", "n2", "n3");
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    void itemsAreNeverNullAndImmutable() {
        PageResponse<String> response = new PageResponse<>(null, 0, 20, 0, 0);

        assertThat(response.items()).isEmpty();
        assertThat(response.items().getClass().getName()).contains("Immutable");
    }
}
