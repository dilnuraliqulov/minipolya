package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.provider.BoundCard;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PendingCharge;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/dev/fake-payments: dev profile, behind X-Dev-Login-Key, never a token. */
@DatabaseTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.dev.login-key=" + DevAuthControllerTest.KEY)
class DevFakePaymentControllerTest {

    private static final String HEADER = "X-Dev-Login-Key";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private PaymentProvider provider;

    @Test
    void itShowsTheLatestCardsChargesHoldsAndReversesWithoutAnyToken() throws Exception {
        BoundCard card = provider.bindCardConfirm(provider.bindCardInit("9860000011110098", "12/29").bindingId(), "123456");
        String account = UUID.randomUUID().toString();
        PendingCharge charge = provider.charge(ChargeRequest.withToken(
                List.of(Split.owner("rcp-dev", 15_500_000L), Split.platform(2_538_100L)), card.cardToken(), account));
        String successId = provider.confirmCharge(charge.transactionIds(), null).successTransIds().getFirst();
        HoldOutcome hold = provider.hold(new HoldRequest(List.of(Split.owner("rcp-dev", 1_000_000L)),
                card.cardToken(), UUID.randomUUID().toString(), 60));
        provider.confirmHold(hold.holdId(), null);
        try {
            provider.reverse(successId, "dev test");
        } catch (RuntimeException expected) {
            // 0098: every reverse is refused, and that is recorded too
        }

        String body = mvc.perform(get("/api/dev/fake-payments").header(HEADER, DevAuthControllerTest.KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards[0].cardId").value(card.cardId()))
                .andExpect(jsonPath("$.data.cards[0].maskedPan").value("9860 •••• •••• 0098"))
                .andExpect(jsonPath("$.data.cards[0].cardType").value("humo"))
                .andExpect(jsonPath("$.data.cards[0].scenario").value("reverse_fails"))
                .andExpect(jsonPath("$.data.cards[0].balance").value(1_000_000_000L - 18_038_100L - 1_000_000L))
                .andExpect(jsonPath("$.data.charges[0].account").value(account))
                .andExpect(jsonPath("$.data.charges[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.charges[0].amount").value(18_038_100L))
                .andExpect(jsonPath("$.data.charges[0].commission").value(232_500L + 38_072L))
                .andExpect(jsonPath("$.data.charges[0].splits[0].recipient").value("owner"))
                .andExpect(jsonPath("$.data.charges[0].splits[0].recipientId").value("rcp-dev"))
                .andExpect(jsonPath("$.data.charges[0].splits[1].recipient").value("platform"))
                .andExpect(jsonPath("$.data.charges[0].splits[1].recipientReceives").value(2_500_028L))
                .andExpect(jsonPath("$.data.holds[0].id").value(hold.holdId()))
                .andExpect(jsonPath("$.data.holds[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.holds[0].holdTill").isNotEmpty())
                .andExpect(jsonPath("$.data.reverses[0].transactionId").value(successId))
                .andExpect(jsonPath("$.data.reverses[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.data.reverses[0].errorCode").value("-15"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(card.cardToken()).doesNotContain("9860000011110098").doesNotContain("token");
    }

    @Test
    void withoutTheKeyItDoesNotExist() throws Exception {
        mvc.perform(get("/api/dev/fake-payments"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/dev/fake-payments").header(HEADER, "wrong-key-wrong-key"))
                .andExpect(status().isNotFound());
    }
}
