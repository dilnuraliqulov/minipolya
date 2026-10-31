package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneMaskTest {

    @Test
    void anUzbekNumberShowsOperatorAndLastTwoDigits() {
        assertThat(PhoneMask.mask("+998901234558")).isEqualTo("+998 90 *** ** 58");
    }

    @Test
    void anythingElseKeepsOnlyTheLastTwoDigits() {
        assertThat(PhoneMask.mask("+7 912 345 67 89")).isEqualTo("*** 89");
        assertThat(PhoneMask.mask(null)).isEqualTo("(none)");
        assertThat(PhoneMask.mask("5")).isEqualTo("***");
    }
}
