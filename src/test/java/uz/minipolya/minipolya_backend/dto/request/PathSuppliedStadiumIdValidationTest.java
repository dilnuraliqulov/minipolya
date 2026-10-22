package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three nested owner endpoints take {@code stadiumId} from the
 * {@code /api/owner/stadiums/{stadiumId}/...} path and overwrite whatever the
 * body carried. Bean validation runs before the controller body, so a
 * {@code @NotNull} on that field would reject a perfectly valid request.
 */
class PathSuppliedStadiumIdValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Set<String> violatedFields(Object request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void closureWithoutStadiumIdInTheBodyIsValid() {
        CreateStadiumClosureRequest request = CreateStadiumClosureRequest.builder()
                .startDate(LocalDate.of(2026, 11, 5))
                .endDate(LocalDate.of(2026, 11, 5))
                .reason("Maintenance")
                .build();

        assertThat(violatedFields(request)).isEmpty();
    }

    @Test
    void promotionWithoutStadiumIdInTheBodyIsValid() {
        CreatePromotionRequest request = CreatePromotionRequest.builder()
                .name("Autumn 10%")
                .discountPercent(10)
                .startsAt(OffsetDateTime.parse("2026-10-22T00:00:00Z"))
                .endsAt(OffsetDateTime.parse("2026-12-31T23:59:59Z"))
                .build();

        assertThat(violatedFields(request)).isEmpty();
    }

    @Test
    void manualBookingWithoutStadiumIdInTheBodyIsValid() {
        CreateManualBookingRequest request = CreateManualBookingRequest.builder()
                .clientName("Walk-in Ali")
                .clientPhone("+998901234567")
                .startsAt(OffsetDateTime.parse("2026-10-23T11:00:00Z"))
                .duration(1)
                .build();

        assertThat(violatedFields(request)).isEmpty();
    }

    @Test
    void theOtherRequiredFieldsAreStillValidated() {
        assertThat(violatedFields(CreateStadiumClosureRequest.builder().build()))
                .containsExactlyInAnyOrder("startDate", "endDate");
        assertThat(violatedFields(CreateManualBookingRequest.builder().build()))
                .containsExactlyInAnyOrder("clientName", "clientPhone", "startsAt", "duration");
        assertThat(violatedFields(CreatePromotionRequest.builder().build()))
                .containsExactlyInAnyOrder("name", "discountPercent", "startsAt", "endsAt");
    }
}
