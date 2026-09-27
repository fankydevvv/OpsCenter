package com.opscenter.shared.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code q=%} must search for a percent sign, not for everything. */
class LikePatternsTest {

    @Test
    void escapesLikeMetaCharacters_andLowerCases() {
        assertThat(LikePatterns.contains("  Pay ")).isEqualTo("%pay%");
        assertThat(LikePatterns.contains("50%")).isEqualTo("%50\\%%");
        assertThat(LikePatterns.contains("a_b")).isEqualTo("%a\\_b%");
        assertThat(LikePatterns.contains("c:\\dir")).isEqualTo("%c:\\\\dir%");
    }
}
