package uz.minipolya.minipolya_backend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import uz.minipolya.minipolya_backend.service.CardPricing;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Changeset 049: the settings row and the column default are on the 5 000 so'm step. */
@DatabaseTest
class RoundingStepMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CardPricing cardPricing;

    @Test
    void theSettingsRowAndTheColumnDefaultUseFiveThousandSom() {
        assertThat(jdbc.queryForObject("SELECT payment_rounding_step FROM platform_settings", Long.class))
                .isEqualTo(500_000L);
        assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_name = 'platform_settings' AND column_name = 'payment_rounding_step'", String.class))
                .contains("500000");
        assertThat(cardPricing.playerAmount(15_000_000L)).isEqualTo(15_500_000L);
    }
}
