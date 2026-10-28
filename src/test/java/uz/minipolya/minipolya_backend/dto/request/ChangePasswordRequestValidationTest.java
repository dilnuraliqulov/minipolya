package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChangePasswordRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aNewPasswordUnderTenCharactersIsRejected() {
        Set<ConstraintViolation<ChangePasswordRequest>> violations = validator.validate(
                ChangePasswordRequest.builder().currentPassword("old-password").newPassword("123456789").build());

        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .containsExactly("newPassword must be at least 10 characters");
    }

    @Test
    void tenCharactersAreEnough() {
        assertThat(validator.validate(
                ChangePasswordRequest.builder().currentPassword("old-password").newPassword("1234567890").build()))
                .isEmpty();
    }

    @Test
    void neitherPasswordIsEverPrinted() {
        String printed = ChangePasswordRequest.builder()
                .currentPassword("old-secret-1").newPassword("new-secret-2").build().toString();

        assertThat(printed).doesNotContain("old-secret-1").doesNotContain("new-secret-2");
    }
}
