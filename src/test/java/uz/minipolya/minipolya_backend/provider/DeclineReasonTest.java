package uz.minipolya.minipolya_backend.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** R44: the one mapping from the provider's refusal codes to what a player is told. */
@ExtendWith(OutputCaptureExtension.class)
class DeclineReasonTest {

    @Test
    void notEnoughMoneyOnTheCard() {
        assertThat(DeclineReason.of("STPIMS-ERR-057")).isEqualTo(DeclineReason.INSUFFICIENT_FUNDS);
        assertThat(DeclineReason.of("STPIMS-ERR-112")).isEqualTo(DeclineReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void anExpiredCard() {
        assertThat(DeclineReason.of("STPIMS-ERR-067")).isEqualTo(DeclineReason.CARD_EXPIRED);
    }

    @Test
    void everyOtherKnownRefusalIsAPlainDeclineAndNotLogged(CapturedOutput output) {
        for (String code : new String[]{"STPIMS-ERR-058", "STPIMS-ERR-079", "STPIMS-ERR-090", "STPIMS-ERR-004",
                "STPIMS-ERR-039", "STPIMS-ERR-117"}) {
            assertThat(DeclineReason.of(code)).as(code).isEqualTo(DeclineReason.CARD_DECLINED);
        }
        assertThat(output.getAll()).doesNotContain("Unknown decline code");
    }

    @Test
    void anUnknownCodeIsAPlainDeclineAndItsCodeIsLogged(CapturedOutput output) {
        assertThat(DeclineReason.of("STPIMS-ERR-777")).isEqualTo(DeclineReason.CARD_DECLINED);

        assertThat(output.getAll()).contains("Unknown decline code").contains("code=STPIMS-ERR-777");
    }

    @Test
    void aFailureWithoutARefusalHasNoReason() {
        assertThat(DeclineReason.of(null)).isNull();
        assertThat(DeclineReason.of(" ")).isNull();
    }

    /**
     * The card said nothing, so there is nothing to tell the player about their card.
     * {@code STPIMS-ERR-041} ("Проблема соединения") is the one the ATMOS sandbox answers
     * {@code pay/apply} with for payments it has in fact taken: calling that a declined card
     * blamed the card for ATMOS's own connection and sent the player to find another one.
     */
    @Test
    void aProviderThatCouldNotBeReachedIsNoDeclineAtAll(CapturedOutput output) {
        for (String code : new String[]{"STPIMS-ERR-041", "STPIMS-ERR-024", "STPIMS-ERR-026",
                "STPIMS-ERR-094", "STPIMS-ERR-102", "HTTP_502", "HTTP_503"}) {
            assertThat(DeclineReason.of(code)).as(code).isNull();
        }
        assertThat(output.getAll())
                .contains("provider connection problem")
                .doesNotContain("Unknown decline code");
    }
}
