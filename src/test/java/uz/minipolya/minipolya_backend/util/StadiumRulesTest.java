package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The add-stadium rules (D1, D10–D15), shared by create and update. */
class StadiumRulesTest {

    static CreateStadiumRequest valid() {
        return CreateStadiumRequest.builder()
                .name("Yunusobod Arena").address("Yunusobod 4, 12").district("Yunusobod")
                .phone("+998901234567").latitude(new BigDecimal("41.3650")).longitude(new BigDecimal("69.2850"))
                .format("5x5").surfaceType("artificial")
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .pricePerHour(15_000_000L)
                .build();
    }

    private static void createFails(Consumer<CreateStadiumRequest> change, String message) {
        CreateStadiumRequest request = valid();
        change.accept(request);
        assertThatThrownBy(() -> StadiumRules.forCreate(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(message);
    }

    private static void createPasses(Consumer<CreateStadiumRequest> change) {
        CreateStadiumRequest request = valid();
        change.accept(request);
        assertThatCode(() -> StadiumRules.forCreate(request)).doesNotThrowAnyException();
    }

    @Test
    void aCompleteRequestPassesAndIsTrimmed() {
        CreateStadiumRequest request = valid();
        request.setName("  Arena  ");
        request.setAddress("  Chilonzor 9  ");
        request.setLandmark("   ");
        request.setPhone(" +998901234567 ");
        StadiumRules.forCreate(request);
        assertThat(request.getName()).isEqualTo("Arena");
        assertThat(request.getAddress()).isEqualTo("Chilonzor 9");
        assertThat(request.getLandmark()).isNull();
        assertThat(request.getPhone()).isEqualTo("+998901234567");
    }

    @Test
    void name() {
        createFails(r -> r.setName(null), "name is required");
        createFails(r -> r.setName("  "), "name is required");
        createFails(r -> r.setName(" ab "), "name must be between 3 and 150 characters");
        createFails(r -> r.setName("x".repeat(151)), "name must be between 3 and 150 characters");
        createPasses(r -> r.setName("abc"));
        createPasses(r -> r.setName("x".repeat(150)));
    }

    @Test
    void address() {
        createFails(r -> r.setAddress(null), "address is required");
        createFails(r -> r.setAddress("abcd"), "address must be between 5 and 300 characters");
        createFails(r -> r.setAddress("x".repeat(301)), "address must be between 5 and 300 characters");
        createPasses(r -> r.setAddress("abcde"));
    }

    @Test
    void districtIsRequiredOnCreate() {
        createFails(r -> r.setDistrict(null), "district is required");
        createFails(r -> r.setDistrict(""), "district is required");
        createFails(r -> r.setDistrict("Toshkent"), "Invalid district");
        createPasses(r -> r.setDistrict("Mirzo Ulugʻbek"));
    }

    @Test
    void phoneIsRequiredOnCreate() {
        createFails(r -> r.setPhone(null), "phone is required");
        createFails(r -> r.setPhone("901234567"), "phone must be in the format +998XXXXXXXXX");
        createFails(r -> r.setPhone("+99890123456"), "phone must be in the format +998XXXXXXXXX");
        createFails(r -> r.setPhone("+998 90 123 45 67"), "phone must be in the format +998XXXXXXXXX");
    }

    @Test
    void descriptionAndLandmark() {
        createFails(r -> r.setDescription("x".repeat(501)), "description must be at most 500 characters");
        createPasses(r -> r.setDescription("x".repeat(500)));
        createFails(r -> r.setLandmark("x".repeat(151)), "landmark must be at most 150 characters");
        createPasses(r -> r.setLandmark("  " + "x".repeat(150) + "  "));
    }

    @Test
    void locationIsRequiredAndInsideUzbekistan() {
        createFails(r -> r.setLatitude(null), "latitude and longitude are required");
        createFails(r -> r.setLongitude(null), "latitude and longitude are required");
        String outside = "Location must be inside Uzbekistan";
        createFails(r -> r.setLatitude(new BigDecimal("37.09")), outside);
        createFails(r -> r.setLatitude(new BigDecimal("45.61")), outside);
        createFails(r -> r.setLongitude(new BigDecimal("55.89")), outside);
        createFails(r -> r.setLongitude(new BigDecimal("73.21")), outside);
        createFails(r -> { r.setLatitude(new BigDecimal("55.75")); r.setLongitude(new BigDecimal("37.61")); }, outside);
        createPasses(r -> { r.setLatitude(new BigDecimal("37.1")); r.setLongitude(new BigDecimal("55.9")); });
        createPasses(r -> { r.setLatitude(new BigDecimal("45.6")); r.setLongitude(new BigDecimal("73.2")); });
    }

    @Test
    void pricePerHour() {
        String message = "pricePerHour must be between 10 000 and 5 000 000 so'm";
        createFails(r -> r.setPricePerHour(null), "pricePerHour is required");
        createFails(r -> r.setPricePerHour(999_999L), message);
        createFails(r -> r.setPricePerHour(500_000_001L), message);
        createFails(r -> r.setPricePerHour(0L), message);
        createPasses(r -> r.setPricePerHour(1_000_000L));
        createPasses(r -> r.setPricePerHour(500_000_000L));
    }

    @Test
    void hours() {
        createFails(r -> r.setOpenTime(null), "openTime is required");
        createFails(r -> r.setCloseTime(null), "closeTime is required");
        createFails(r -> r.setOpenTime(LocalTime.of(8, 30)), "openTime and closeTime must be on the hour");
        createFails(r -> r.setCloseTime(LocalTime.of(22, 0, 1)), "openTime and closeTime must be on the hour");
        String midnight = "closeTime must be after openTime; closing after midnight is not supported";
        createFails(r -> { r.setOpenTime(LocalTime.of(18, 0)); r.setCloseTime(LocalTime.of(2, 0)); }, midnight);
        createFails(r -> r.setCloseTime(LocalTime.of(8, 0)), midnight);
        createFails(r -> r.setCloseTime(LocalTime.MIDNIGHT), midnight);
        createPasses(r -> { r.setOpenTime(LocalTime.MIDNIGHT); r.setCloseTime(LocalTime.of(23, 0)); });
    }

    // ---- Update: each field only when sent ----

    private static Stadium current() {
        return Stadium.builder().name("Arena").address("Chilonzor 9")
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0)).pricePerHour(15_000_000L).build();
    }

    private static void updateFails(UpdateStadiumRequest request, String message) {
        assertThatThrownBy(() -> StadiumRules.forUpdate(request, current()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(message);
    }

    @Test
    void anEmptyUpdatePasses() {
        assertThatCode(() -> StadiumRules.forUpdate(new UpdateStadiumRequest(), current())).doesNotThrowAnyException();
        // Stadiums from before these rules may break them; an update that leaves the field alone is fine.
        Stadium old = Stadium.builder().name("A").openTime(LocalTime.of(8, 30)).closeTime(LocalTime.of(2, 0)).build();
        assertThatCode(() -> StadiumRules.forUpdate(UpdateStadiumRequest.builder().description("x").build(), old))
                .doesNotThrowAnyException();
    }

    @Test
    void updateChecksWhatIsSent() {
        updateFails(UpdateStadiumRequest.builder().name("ab").build(), "name must be between 3 and 150 characters");
        updateFails(UpdateStadiumRequest.builder().address(" ").build(), "address must be between 5 and 300 characters");
        updateFails(UpdateStadiumRequest.builder().district("Nowhere").build(), "Invalid district");
        updateFails(UpdateStadiumRequest.builder().phone("12345").build(), "phone must be in the format +998XXXXXXXXX");
        updateFails(UpdateStadiumRequest.builder().description("x".repeat(501)).build(),
                "description must be at most 500 characters");
        updateFails(UpdateStadiumRequest.builder().pricePerHour(100L).build(),
                "pricePerHour must be between 10 000 and 5 000 000 so'm");
        updateFails(UpdateStadiumRequest.builder().latitude(new BigDecimal("41.3")).build(),
                "Send both latitude and longitude");
        updateFails(UpdateStadiumRequest.builder().longitude(new BigDecimal("69.2")).build(),
                "Send both latitude and longitude");
        updateFails(UpdateStadiumRequest.builder().latitude(new BigDecimal("50")).longitude(new BigDecimal("69.2")).build(),
                "Location must be inside Uzbekistan");
    }

    @Test
    void updateChecksTheResultingHours() {
        String midnight = "closeTime must be after openTime; closing after midnight is not supported";
        // Current hours are 08:00–23:00.
        updateFails(UpdateStadiumRequest.builder().closeTime(LocalTime.of(7, 0)).build(), midnight);
        updateFails(UpdateStadiumRequest.builder().openTime(LocalTime.of(23, 0)).build(), midnight);
        updateFails(UpdateStadiumRequest.builder().openTime(LocalTime.of(9, 15)).build(),
                "openTime and closeTime must be on the hour");
        assertThatCode(() -> StadiumRules.forUpdate(
                UpdateStadiumRequest.builder().openTime(LocalTime.of(6, 0)).build(), current()))
                .doesNotThrowAnyException();
    }

    @Test
    void updateTrimsAndKeepsABlankLandmarkAsAClear() {
        UpdateStadiumRequest request = UpdateStadiumRequest.builder().name("  New name ").landmark("  ").build();
        StadiumRules.forUpdate(request, current());
        assertThat(request.getName()).isEqualTo("New name");
        assertThat(request.getLandmark()).isEqualTo(""); // sent blank: the service clears it
    }
}
