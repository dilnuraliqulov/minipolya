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
}
