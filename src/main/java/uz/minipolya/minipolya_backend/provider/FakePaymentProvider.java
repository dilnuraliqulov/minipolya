package uz.minipolya.minipolya_backend.provider;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for ATMOS in dev and tests, so every business flow above it runs without
 * real money. Active only while {@code app.payments.provider=fake}, and it refuses to
 * start at all under the {@code prod} profile.
 *
 * <p>It behaves like ATMOS in the ways that matter: a binding needs the SMS code; a
 * charge is one transaction per split, created then confirmed, and the provider takes
 * {@value #COMMISSION_BPS} bps of each split as commission; a reverse gives a confirmed
 * transaction back once ({@link TransactionClosedException} the second time); a hold
 * lapses {@code durationMinutes} after it is placed, after which a capture fails with
 * {@link HoldExpiredException} ({@value #HOLD_EXPIRED}), and a hold settles exactly once
 * ({@link HoldCancelledException} after a release); the same {@code account} never
 * creates a second charge.
 *
 * <p>Two things make failure paths testable: the SMS code is always
 * {@value #EXPECTED_SMS_CODE}, and a card whose number ends in {@value #DECLINED_SUFFIX}
 * is always declined.
 *
 * <p>State lives in memory and resets with the process. Card numbers are held only
 * between {@code bindCardInit} and {@code bindCardConfirm} and are never logged; tokens
 * are never logged either.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "fake")
public class FakePaymentProvider implements PaymentProvider {

    /** The only SMS code this fake accepts. */
    public static final String EXPECTED_SMS_CODE = "111111";
    /** A card number ending in this is always declined, so failures can be tested. */
    public static final String DECLINED_SUFFIX = "0002";
    /** The commission taken from each split: 150 bps = 1.5%, like ATMOS. */
    public static final int COMMISSION_BPS = 150;
    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    public static final String HOLD_CANCELLED = "HOLD_CANCELLED";
    public static final String TRANSACTION_CLOSED = "TRANSACTION_CLOSED";
    public static final String DECLINED = "DECLINED";

    private final Environment environment;
    private final Clock clock;

    private final Map<String, String> pendingBindings = new ConcurrentHashMap<>();
    /** token → card. */
    private final Map<String, FakeCard> cards = new ConcurrentHashMap<>();
    private final Map<String, FakeTransaction> transactions = new ConcurrentHashMap<>();
    /** account → the transactions created for it, so a retry returns them instead of charging again. */
    private final Map<String, List<String>> chargesByAccount = new ConcurrentHashMap<>();
    private final Map<String, FakeHold> holds = new ConcurrentHashMap<>();

    public FakePaymentProvider(Environment environment, Clock clock) {
        this.environment = environment;
        this.clock = clock;
    }

    /**
     * A fake payment provider in production would silently approve every charge and take
     * no money, so refuse to come up at all.
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

    // ---- Cards ----

    @Override
    public CardBindingStart bindCardInit(String cardNumber, String expiry) {
        String digits = digitsOf(cardNumber);
        if (digits.length() < 8) {
            throw new BadRequestException("cardNumber is not a card number");
        }
        String bindingId = "bind-" + UUID.randomUUID();
        pendingBindings.put(bindingId, digits);
        log.info("[fake-provider] card binding started: bindingId={}", bindingId);
        return new CardBindingStart(bindingId, "********9999");
    }

    @Override
    public BoundCard bindCardConfirm(String bindingId, String otp) {
        String digits = pendingBindings.get(bindingId);
        if (digits == null) {
            throw new BadRequestException("Unknown or already-confirmed binding");
        }
        if (!EXPECTED_SMS_CODE.equals(otp)) {
            throw new BadRequestException("Wrong SMS code");
        }
        pendingBindings.remove(bindingId);
        String token = "tok-" + UUID.randomUUID();
        String cardId = "card-" + UUID.randomUUID();
        cards.put(token, new FakeCard(cardId, digits.endsWith(DECLINED_SUFFIX)));
        BoundCard card = new BoundCard(token, cardId, mask(digits), "12/30");
        log.info("[fake-provider] card bound: cardId={} masked={}", cardId, card.maskedPan());
        return card;
    }

    @Override
    public void removeCard(String cardId, String cardToken) {
        FakeCard card = cards.get(cardToken);
        if (card == null || !card.cardId().equals(cardId)) {
            throw new PaymentProviderException("CARD_NOT_FOUND", "Unknown card");
        }
        cards.remove(cardToken);
        log.info("[fake-provider] card removed: cardId={}", cardId);
    }

    // ---- Charges ----

    @Override
    public PendingCharge charge(ChargeRequest request) {
        boolean byToken = request.cardToken() != null;
        boolean declines;
        if (byToken) {
            FakeCard card = cards.get(request.cardToken());
            if (card == null) {
                throw new BadRequestException("Unknown card token");
            }
            declines = card.declines();
        } else {
            declines = digitsOf(request.cardNumber()).endsWith(DECLINED_SUFFIX);
        }
        List<String> existing = chargesByAccount.get(request.account());
        if (existing != null) {
            // The same payment row again: never a second charge.
            return new PendingCharge(existing, !byToken);
        }
        List<String> ids = new ArrayList<>();
        for (Split split : request.splits()) {
            String id = "txn-" + UUID.randomUUID();
            transactions.put(id, new FakeTransaction(id, split.amount(), request.account(), !byToken, declines));
            ids.add(id);
        }
        chargesByAccount.put(request.account(), List.copyOf(ids));
        log.info("[fake-provider] charge created: account={} total={} splits={}",
                request.account(), request.total(), ids.size());
        return new PendingCharge(ids, !byToken);
    }

    @Override
    public ChargeOutcome confirmCharge(List<String> transactionIds, String otp) {
        List<FakeTransaction> txns = transactionIds.stream().map(this::requireTransaction).toList();
        if (txns.stream().anyMatch(t -> t.status != ProviderStatus.PENDING)) {
            return outcomeOf(txns); // confirming twice answers with where it already stands
        }
        if (txns.stream().anyMatch(t -> t.otpRequired) && !EXPECTED_SMS_CODE.equals(otp)) {
            throw new PaymentProviderException("WRONG_OTP", "Wrong SMS code");
        }
        if (txns.stream().anyMatch(t -> t.declines)) {
            txns.forEach(t -> t.status = ProviderStatus.FAILED);
            log.info("[fake-provider] charge declined: transactions={}", transactionIds);
            return ChargeOutcome.failed(DECLINED);
        }
        for (FakeTransaction t : txns) {
            t.status = ProviderStatus.SUCCESS;
            t.successId = "succ-" + t.id;
        }
        log.info("[fake-provider] charge confirmed: transactions={}", transactionIds);
        return outcomeOf(txns);
    }

    @Override
    public ChargeOutcome getCharge(String transactionId) {
        return outcomeOf(List.of(requireTransaction(transactionId)));
    }

    @Override
    public void reverse(String successTransId, String reason) {
        FakeTransaction txn = transactions.values().stream()
                .filter(t -> successTransId.equals(t.successId))
                .findFirst()
                .orElseThrow(() -> new PaymentProviderException("TRANSACTION_NOT_FOUND", "Unknown transaction"));
        if (txn.status != ProviderStatus.SUCCESS) {
            throw new TransactionClosedException(TRANSACTION_CLOSED);
        }
        txn.status = ProviderStatus.REVERSED;
        log.info("[fake-provider] reversed in full: successTransId={} amount={}", successTransId, txn.amount);
    }

    // ---- Holds ----

    @Override
    public HoldOutcome hold(HoldRequest request) {
        FakeCard card = cards.get(request.cardToken());
        if (card == null) {
            throw new BadRequestException("Unknown card token");
        }
        String holdId = "hold-" + UUID.randomUUID();
        OffsetDateTime till = now().plusMinutes(request.durationMinutes());
        holds.put(holdId, new FakeHold(holdId, request.splits(), till, card.declines()));
        log.info("[fake-provider] hold created: holdId={} total={} till={}", holdId, request.total(), till);
        return new HoldOutcome(holdId, ProviderStatus.PENDING, till, null, null);
    }

    @Override
    public HoldOutcome confirmHold(String holdId, String otp) {
        FakeHold hold = requireHold(holdId);
        if (hold.status == ProviderStatus.PENDING) {
            hold.status = hold.declines ? ProviderStatus.FAILED : ProviderStatus.HELD;
        }
        return outcomeOf(hold);
    }

    @Override
    public HoldOutcome captureHold(String holdId) {
        FakeHold hold = requireHold(holdId);
        if (hold.status == ProviderStatus.HELD && lapsed(hold)) {
            throw new HoldExpiredException(HOLD_EXPIRED);
        }
        if (hold.status == ProviderStatus.RELEASED) {
            throw new HoldCancelledException(HOLD_CANCELLED);
        }
        if (hold.status != ProviderStatus.HELD) {
            throw new TransactionClosedException(TRANSACTION_CLOSED);
        }
        hold.status = ProviderStatus.CAPTURED;
        hold.commission = hold.splits.stream().mapToLong(s -> s.amount() * COMMISSION_BPS / 10_000).sum();
        log.info("[fake-provider] hold captured: holdId={}", holdId);
        return outcomeOf(hold);
    }

    @Override
    public HoldOutcome releaseHold(String holdId) {
        FakeHold hold = requireHold(holdId);
        if (hold.status != ProviderStatus.HELD || lapsed(hold)) {
            throw new HoldCancelledException(HOLD_CANCELLED);
        }
        hold.status = ProviderStatus.RELEASED;
        log.info("[fake-provider] hold released: holdId={}", holdId);
        return outcomeOf(hold);
    }

    @Override
    public HoldOutcome getHold(String holdId) {
        return outcomeOf(requireHold(holdId));
    }

    // ---- Helpers ----

    private ChargeOutcome outcomeOf(List<FakeTransaction> txns) {
        if (txns.stream().allMatch(t -> t.status == ProviderStatus.SUCCESS)) {
            long commission = txns.stream().mapToLong(t -> t.amount * COMMISSION_BPS / 10_000).sum();
            return new ChargeOutcome(ProviderStatus.SUCCESS, txns.stream().map(t -> t.successId).toList(),
                    commission, "https://ofd.fake/receipt/" + txns.getFirst().account, null);
        }
        if (txns.stream().anyMatch(t -> t.status == ProviderStatus.FAILED)) {
            return ChargeOutcome.failed(DECLINED);
        }
        if (txns.stream().allMatch(t -> t.status == ProviderStatus.REVERSED)) {
            return new ChargeOutcome(ProviderStatus.REVERSED, List.of(), null, null, null);
        }
        return ChargeOutcome.pending();
    }

    private HoldOutcome outcomeOf(FakeHold hold) {
        // A hold nobody settled lets go on its own at the provider.
        ProviderStatus status = hold.status == ProviderStatus.HELD && lapsed(hold) ? ProviderStatus.RELEASED : hold.status;
        return new HoldOutcome(hold.id, status, hold.till, hold.commission,
                status == ProviderStatus.FAILED ? DECLINED : null);
    }

    private boolean lapsed(FakeHold hold) {
        return !now().isBefore(hold.till);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private FakeTransaction requireTransaction(String id) {
        FakeTransaction txn = transactions.get(id);
        if (txn == null) {
            throw new PaymentProviderException("TRANSACTION_NOT_FOUND", "Unknown transaction");
        }
        return txn;
    }

    private FakeHold requireHold(String id) {
        FakeHold hold = holds.get(id);
        if (hold == null) {
            throw new PaymentProviderException("HOLD_NOT_FOUND", "Unknown hold");
        }
        return hold;
    }

    /** {@code 8600 •••• 1234} — first four and last four, nothing in between. */
    private String mask(String digits) {
        return digits.substring(0, 4) + " •••• " + digits.substring(digits.length() - 4);
    }

    private String digitsOf(String cardNumber) {
        return cardNumber == null ? "" : cardNumber.replaceAll("\\D", "");
    }

    private record FakeCard(String cardId, boolean declines) {
    }

    private static final class FakeTransaction {
        final String id;
        final long amount;
        final String account;
        final boolean otpRequired;
        final boolean declines;
        ProviderStatus status = ProviderStatus.PENDING;
        String successId;

        FakeTransaction(String id, long amount, String account, boolean otpRequired, boolean declines) {
            this.id = id;
            this.amount = amount;
            this.account = account;
            this.otpRequired = otpRequired;
            this.declines = declines;
        }
    }

    private static final class FakeHold {
        final String id;
        final List<Split> splits;
        final OffsetDateTime till;
        final boolean declines;
        ProviderStatus status = ProviderStatus.PENDING;
        Long commission;

        FakeHold(String id, List<Split> splits, OffsetDateTime till, boolean declines) {
            this.id = id;
            this.splits = List.copyOf(splits);
            this.till = till;
            this.declines = declines;
        }
    }
}
