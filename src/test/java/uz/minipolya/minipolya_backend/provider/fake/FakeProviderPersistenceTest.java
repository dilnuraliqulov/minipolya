package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.provider.BoundCard;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.FakePaymentProvider;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PendingCharge;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fake keeps its cards, charges, holds and reverses in the database: a second
 * provider instance over the same tables — what a backend restart gives — carries on
 * where the first stopped, and our own transaction rolling back never undoes what the
 * "provider" did.
 */
@DatabaseTest
class FakeProviderPersistenceTest {

    @Autowired
    private FakeProviderStore store;
    @Autowired
    private Environment environment;
    @Autowired
    private Clock clock;
    @Autowired
    private AppProperties properties;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private PaymentProvider start() {
        return new FakePaymentProvider(environment, clock, properties, store, null);
    }

    private BoundCard bind(PaymentProvider provider) {
        String bindingId = provider.bindCardInit("8600123412345678", "12/29").bindingId();
        return provider.bindCardConfirm(bindingId, "123456");
    }

    private long balance(String cardId) {
        return jdbc.queryForObject("SELECT balance FROM fake_payment_cards WHERE card_id = ?", Long.class, cardId);
    }

    @Test
    void theStoreIsTheDatabaseOne() {
        assertThat(store).isInstanceOf(JdbcFakeProviderStore.class);
    }

    @Test
    void cardsChargesHoldsAndReversesSurviveARestart() {
        PaymentProvider before = start();
        BoundCard card = bind(before);
        String account = UUID.randomUUID().toString();
        PendingCharge pending = before.charge(ChargeRequest.withToken(
                List.of(Split.owner("rcp-1", 1_000_000L), Split.platform(100_000L)), card.cardToken(), account));
        ChargeOutcome paid = before.confirmCharge(pending.transactionIds(), null);
        HoldOutcome hold = before.hold(new HoldRequest(List.of(Split.owner("rcp-1", 2_000_000L)),
                card.cardToken(), UUID.randomUUID().toString(), 120));
        before.confirmHold(hold.holdId(), null);

        PaymentProvider after = start();

        // The token still works: no "Unknown card token" after a restart.
        PendingCharge second = after.charge(ChargeRequest.withToken(
                List.of(Split.owner("rcp-1", 500_000L)), card.cardToken(), UUID.randomUUID().toString()));
        assertThat(after.confirmCharge(second.transactionIds(), null).status()).isEqualTo(ProviderStatus.SUCCESS);
        // What happened before is still known, and the account is still not charged twice.
        assertThat(after.getCharge(pending.transactionIds().getFirst()).status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(after.charge(ChargeRequest.withToken(List.of(Split.owner("rcp-1", 1_000_000L)), card.cardToken(),
                account)).transactionIds()).isEqualTo(pending.transactionIds());
        assertThat(after.getHold(hold.holdId()).status()).isEqualTo(ProviderStatus.HELD);
        assertThat(balance(card.cardId())).isEqualTo(1_000_000_000L - 1_100_000L - 2_000_000L - 500_000L);

        after.reverse(paid.successTransIds().getFirst(), "cancelled");
        after.releaseHold(hold.holdId());
        assertThat(balance(card.cardId())).isEqualTo(1_000_000_000L - 100_000L - 500_000L);
        assertThat(store.latestReverses(5)).extracting(FakeReverse::getTransactionId)
                .contains(paid.successTransIds().getFirst());

        after.removeCard(card.cardId(), card.cardToken());
        assertThat(start().getHold(hold.holdId()).status()).isEqualTo(ProviderStatus.RELEASED);
    }

    @Test
    void ourRollbackNeverUndoesWhatTheProviderDid() {
        PaymentProvider provider = start();
        BoundCard card = bind(provider);
        TransactionTemplate ours = new TransactionTemplate(transactionManager);
        ours.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        String[] transactionId = new String[1];
        ours.executeWithoutResult(status -> {
            PendingCharge pending = provider.charge(ChargeRequest.withToken(
                    List.of(Split.owner("rcp-1", 300_000L)), card.cardToken(), UUID.randomUUID().toString()));
            provider.confirmCharge(pending.transactionIds(), null);
            transactionId[0] = pending.transactionIds().getFirst();
            status.setRollbackOnly();
        });

        assertThat(provider.getCharge(transactionId[0]).status()).isEqualTo(ProviderStatus.SUCCESS);
        assertThat(balance(card.cardId())).isEqualTo(1_000_000_000L - 300_000L);
    }

    @Test
    void noTokenNumberOrCodeIsStored() {
        BoundCard card = bind(start());

        String row = jdbc.queryForMap("SELECT * FROM fake_payment_cards WHERE card_id = ?", card.cardId()).toString();
        String binding = jdbc.queryForList("SELECT * FROM fake_payment_bindings ORDER BY created_at DESC LIMIT 1").toString();

        assertThat(row).doesNotContain(card.cardToken()).doesNotContain("8600123412345678").contains("8600 •••• •••• 5678");
        assertThat(binding).doesNotContain("8600123412345678").doesNotContain("123456");
    }
}
