package uz.minipolya.minipolya_backend.provider;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for ATMOS until its API docs arrive, so every business flow above it
 * can be built and tested now. Active only while
 * {@code app.payments.provider=fake}, and it refuses to start at all under the
 * {@code prod} profile.
 *
 * <p>It behaves like the real thing in the ways that matter: a binding needs the
 * SMS code, transaction ids are unique, and a hold settles exactly once. Two
 * things make failure paths testable — the SMS code is always
 * {@value #EXPECTED_SMS_CODE}, and a card whose number ends in
 * {@value #DECLINED_SUFFIX} is always declined.
 *
 * <p>State lives in memory, so it resets with the process. Card numbers are held
 * only between {@code startCardBinding} and {@code confirmCardBinding}, are
 * never logged, and never leave this class.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "fake")
public class FakePaymentProvider implements PaymentProvider {

    /** The only SMS code this fake accepts. */
    public static final String EXPECTED_SMS_CODE = "111111";

    /** A card number ending in this is always declined, so failures can be tested. */
    public static final String DECLINED_SUFFIX = "0002";

    private static final String UZCARD_PREFIX = "8600";
    private static final String HUMO_PREFIX = "9860";

    private final Environment environment;

    /** bindingId -> the card being bound. Removed as soon as it is confirmed. */
    private final Map<String, PendingBinding> pendingBindings = new ConcurrentHashMap<>();

    /** token -> whether that card declines, so a charge can fail by card. */
    private final Map<String, Boolean> declineByToken = new ConcurrentHashMap<>();

    /** providerTxnId -> current status, so capture/release/refund can be checked. */
    private final Map<String, ProviderTransactionStatus> transactions = new ConcurrentHashMap<>();

    public FakePaymentProvider(Environment environment) {
        this.environment = environment;
    }

    /**
     * A fake payment provider in production would silently approve every charge
     * and take no money, so refuse to come up at all.
     */
    @PostConstruct
    public void refuseInProduction() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException(
                    "app.payments.provider=fake must never run under the prod profile: "
                            + "configure the real payment provider instead");
        }
        log.warn("FakePaymentProvider is active (app.payments.provider=fake): no real money will move");
    }

    @Override
    public CardBinding startCardBinding(String cardNumber, String expiry) {
        String digits = digitsOf(cardNumber);
        if (digits.length() < 8) {
            throw new BadRequestException("cardNumber is not a card number");
        }
        String bindingId = "bind-" + UUID.randomUUID();
        pendingBindings.put(bindingId, new PendingBinding(digits, expiry));
        // Deliberately logs the id only — never the number.
        log.info("[fake-provider] card binding started: bindingId={}", bindingId);
        return new CardBinding(bindingId);
    }

    @Override
    public BoundCard confirmCardBinding(String bindingId, String smsCode) {
        PendingBinding pending = pendingBindings.get(bindingId);
        if (pending == null) {
            throw new BadRequestException("Unknown or already-confirmed binding");
        }
        if (!EXPECTED_SMS_CODE.equals(smsCode)) {
            throw new BadRequestException("Wrong SMS code");
        }
        pendingBindings.remove(bindingId);

        String token = "tok-" + UUID.randomUUID();
        declineByToken.put(token, pending.digits().endsWith(DECLINED_SUFFIX));
        BoundCard card = new BoundCard(token, mask(pending.digits()), cardType(pending.digits()));
        log.info("[fake-provider] card bound: token={} masked={}", token, card.maskedNumber());
        return card;
    }

    @Override
    public ProviderTransaction charge(ChargeCommand command) {
        return settleImmediately(command, ProviderTransactionStatus.PAID);
    }

    @Override
    public ProviderTransaction hold(ChargeCommand command) {
        return settleImmediately(command, ProviderTransactionStatus.HELD);
    }

    @Override
    public ProviderTransaction capture(String providerTxnId) {
        return moveHeld(providerTxnId, ProviderTransactionStatus.CAPTURED);
    }

    @Override
    public ProviderTransaction release(String providerTxnId) {
        return moveHeld(providerTxnId, ProviderTransactionStatus.RELEASED);
    }

    @Override
    public ProviderTransaction refund(String providerTxnId) {
        ProviderTransactionStatus current = transactions.get(providerTxnId);
        if (current == null) {
            throw new BadRequestException("Unknown transaction " + providerTxnId);
        }
        if (current != ProviderTransactionStatus.PAID && current != ProviderTransactionStatus.CAPTURED) {
            throw new BadRequestException("Only a paid or captured transaction can be refunded, not " + current);
        }
        transactions.put(providerTxnId, ProviderTransactionStatus.REFUNDED);
        log.info("[fake-provider] refunded in full: txn={}", providerTxnId);
        return new ProviderTransaction(providerTxnId, ProviderTransactionStatus.REFUNDED);
    }

    // ---- Helpers ----

    private ProviderTransaction settleImmediately(ChargeCommand command, ProviderTransactionStatus success) {
        validate(command);
        if (Boolean.TRUE.equals(declineByToken.get(command.cardToken()))) {
            log.info("[fake-provider] declined: token={} reference={}",
                    command.cardToken(), command.reference());
            return new ProviderTransaction("failed-" + UUID.randomUUID(), ProviderTransactionStatus.FAILED);
        }
        String txnId = "txn-" + UUID.randomUUID();
        transactions.put(txnId, success);
        log.info("[fake-provider] {}: txn={} amount={} owner={} platform={} reference={}",
                success, txnId, command.amount(), command.ownerAmount(), command.platformAmount(),
                command.reference());
        return new ProviderTransaction(txnId, success);
    }

    private ProviderTransaction moveHeld(String providerTxnId, ProviderTransactionStatus target) {
        ProviderTransactionStatus current = transactions.get(providerTxnId);
        if (current == null) {
            throw new BadRequestException("Unknown transaction " + providerTxnId);
        }
        if (current != ProviderTransactionStatus.HELD) {
            throw new BadRequestException("Only a held transaction can be " + target + ", not one that is " + current);
        }
        transactions.put(providerTxnId, target);
        log.info("[fake-provider] {}: txn={}", target, providerTxnId);
        return new ProviderTransaction(providerTxnId, target);
    }

    private void validate(ChargeCommand command) {
        if (command.amount() <= 0) {
            throw new BadRequestException("amount must be positive");
        }
        if (command.ownerAmount() + command.platformAmount() != command.amount()) {
            throw new BadRequestException("ownerAmount + platformAmount must equal amount");
        }
        if (!declineByToken.containsKey(command.cardToken())) {
            throw new BadRequestException("Unknown card token");
        }
    }

    /** {@code 8600 •••• 1234} — first four and last four, nothing in between. */
    private String mask(String digits) {
        return digits.substring(0, 4) + " •••• " + digits.substring(digits.length() - 4);
    }

    private String cardType(String digits) {
        if (digits.startsWith(UZCARD_PREFIX)) {
            return "uzcard";
        }
        if (digits.startsWith(HUMO_PREFIX)) {
            return "humo";
        }
        return "unknown";
    }

    private String digitsOf(String cardNumber) {
        return cardNumber == null ? "" : cardNumber.replaceAll("\\D", "");
    }

    /** Lives only until the binding is confirmed. */
    private record PendingBinding(String digits, String expiry) {
    }
}
