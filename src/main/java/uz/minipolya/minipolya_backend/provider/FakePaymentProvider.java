package uz.minipolya.minipolya_backend.provider;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.provider.fake.FakeBinding;
import uz.minipolya.minipolya_backend.provider.fake.FakeCard;
import uz.minipolya.minipolya_backend.provider.fake.FakeOperation;
import uz.minipolya.minipolya_backend.provider.fake.FakeProviderStore;
import uz.minipolya.minipolya_backend.provider.fake.FakeReverse;
import uz.minipolya.minipolya_backend.provider.fake.FakeSplit;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in for ATMOS in dev and tests, so every business flow above it runs without
 * real money. Active only while {@code app.payments.provider=fake} and never under the
 * {@code atmos} profile; it refuses to start under {@code prod}.
 *
 * <p>It answers the way ATMOS does, with ATMOS's codes ({@link AtmosCodes}), so switching
 * to the real client changes nothing above the {@link PaymentProvider} interface. Its state
 * lives in a {@link FakeProviderStore} — the database in the application — and survives
 * a restart.
 *
 * <p><b>Binding.</b> Any 16-digit Uzcard ({@code 8600…}) or Humo ({@code 9860…}) number
 * with a valid MM/YY that has not passed; anything else is
 * {@link AtmosCodes#WRONG_CARD_NUMBER}, a bad month {@link AtmosCodes#WRONG_EXPIRY}, a past
 * one {@link AtmosCodes#CARD_EXPIRED}. Any SMS code of {@code app.payments.fake.otp-length}
 * digits is accepted except the reserved {@value #WRONG_CODE} (wrong) and
 * {@value #EXPIRED_CODE} (expired); a code lives {@code otp-ttl-seconds} and may be sent
 * again after {@code resend-cooldown-seconds}.
 *
 * <p><b>Money.</b> Every card starts with {@code starting-balance}; a charge or hold takes
 * from it, a reverse or release gives it back, and too little is
 * {@link AtmosCodes#INSUFFICIENT_BALANCE}. Each split is one transaction, of at least
 * {@code min-amount}, and ATMOS keeps {@value #COMMISSION_BPS} bps of each. A reverse is
 * whole, once, within {@code refund-window-months}. A hold lapses after its duration. The
 * same {@code account} never charges (or holds) twice. The card's last four digits pick a
 * scenario: {@link #DECLINED_SUFFIX} declined, {@link #INSUFFICIENT_SUFFIX},
 * {@link #EXPIRED_SUFFIX}, {@link #TIMEOUT_THEN_SUCCESS_SUFFIX},
 * {@link #TIMEOUT_THEN_FAILED_SUFFIX}, {@link #UNAVAILABLE_SUFFIX},
 * {@link #REVERSE_FAILS_SUFFIX}, {@link #CAPTURE_FAILS_SUFFIX}.
 *
 * <p>Card numbers, SMS codes and tokens are never logged or stored: a card is kept by its
 * masked number, last four digits and the SHA-256 of its token.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "fake")
@Profile("!atmos")
public class FakePaymentProvider implements PaymentProvider {

    /** Reserved: always "wrong code". */
    public static final String WRONG_CODE = "000000";
    /** Reserved: always "code expired". */
    public static final String EXPIRED_CODE = "999999";
    /** Any other code of the configured length works; this one is as good as any. */
    public static final String EXPECTED_SMS_CODE = "111111";

    /** Declined ({@link AtmosCodes#CARD_BLOCKED}). */
    public static final String DECLINED_SUFFIX = "0002";
    /** Insufficient funds ({@link AtmosCodes#INSUFFICIENT_FUNDS}). */
    public static final String INSUFFICIENT_SUFFIX = "0051";
    /** Card expired ({@link AtmosCodes#CARD_EXPIRED}). */
    public static final String EXPIRED_SUFFIX = "0054";
    /** The money-taking step (a charge's confirmation, a hold's capture) times out, but it went through: get-status says so. */
    public static final String TIMEOUT_THEN_SUCCESS_SUFFIX = "0091";
    /** The money-taking step times out and nothing was taken: get-status says FAILED (a hold: RELEASED). */
    public static final String TIMEOUT_THEN_FAILED_SUFFIX = "0092";
    /** The provider is unavailable for every charge and hold ({@link AtmosCodes#SERVICE_UNAVAILABLE}). */
    public static final String UNAVAILABLE_SUFFIX = "0099";
    /** Charges work, every reverse is refused ({@link AtmosCodes#NOT_CANCELLED}). */
    public static final String REVERSE_FAILS_SUFFIX = "0098";
    /** Holds work, a capture is refused with an ordinary code ({@link AtmosCodes#CARD_BLOCKED}); the hold stays. */
    public static final String CAPTURE_FAILS_SUFFIX = "0097";

    /** The commission taken from each split: 150 bps = 1.5%, like ATMOS. */
    public static final int COMMISSION_BPS = 150;
    public static final String HOLD_EXPIRED = AtmosCodes.HOLD_EXPIRED;
    public static final String HOLD_CANCELLED = AtmosCodes.HOLD_CANCELLED;
    public static final String TRANSACTION_CLOSED = AtmosCodes.TRANSACTION_CLOSED;
    public static final String DECLINED = AtmosCodes.CARD_BLOCKED;
    /** No such transaction or hold (our name; ATMOS's MPS table has -12). */
    public static final String NOT_FOUND = "TRANSACTION_NOT_FOUND";

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final String CARD_HOLDER = "MINIPOLYA TEST";

    private final Environment environment;
    private final Clock clock;
    private final AppProperties.Fake config;
    private final FakeProviderStore store;
    /** Null in unit tests: the masked phone then ends in {@code **}. */
    private final UserRepository userRepository;

    public FakePaymentProvider(Environment environment, Clock clock, AppProperties properties,
                               FakeProviderStore store, UserRepository userRepository) {
        this.environment = environment;
        this.clock = clock;
        this.config = properties.getPayments().getFake();
        this.store = store;
        this.userRepository = userRepository;
    }

    /**
     * A fake payment provider in production would silently approve every charge and take
     * no money, so refuse to come up at all. (It is never created under {@code atmos}.)
     *
     * <p>{@code staging} is deliberately not on that list: the staging test deployment runs
     * this provider on purpose, because a test server must be able to walk through a booking
     * end to end without a provider contract. No real money can move there — there is no real
     * provider wired in at all — and the warning below names it in the log on every start.
     */
    @PostConstruct
    public void refuseInProduction() {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        if (profiles.contains("prod") || profiles.contains("atmos")) {
            throw new IllegalStateException(
                    "app.payments.provider=fake must never run under the prod or atmos profile: "
                            + "configure the real payment provider instead");
        }
        log.warn("FakePaymentProvider is active (app.payments.provider=fake): no real money will move");
    }

    // ---- Cards ----

    @Override
    public CardBindingStart bindCardInit(String cardNumber, String expiry) {
        pause();
        String digits = digitsOf(cardNumber);
        if (digits.length() != 16 || !(digits.startsWith("8600") || digits.startsWith("9860"))) {
            throw refused(AtmosCodes.WRONG_CARD_NUMBER, "Неправильный номер карты");
        }
        YearMonth validTo = parseExpiry(expiry);
        if (validTo == null) {
            throw refused(AtmosCodes.WRONG_EXPIRY, "Неверно указана дата истечения срока вашей карты");
        }
        if (validTo.isBefore(YearMonth.now(clock.withZone(TASHKENT)))) {
            throw refused(AtmosCodes.CARD_EXPIRED, "Срок действия вашей карты истек");
        }
        String maskedPhone = CardMask.phone(currentUserPhone().orElse(null));
        OffsetDateTime now = now();
        FakeBinding binding = FakeBinding.builder()
                .bindingId("bind-" + UUID.randomUUID())
                .maskedPan(CardMask.pan(digits))
                .last4(digits.substring(12))
                .expiry(expiry.trim())
                .maskedPhone(maskedPhone)
                .cardHolder(CARD_HOLDER)
                .codeSentAt(now)
                .createdAt(now)
                .build();
        store.inTransaction(() -> {
            store.saveBinding(binding);
            return null;
        });
        log.info("[fake-provider] card binding started: bindingId={}", binding.getBindingId());
        return new CardBindingStart(binding.getBindingId(), maskedPhone);
    }

    @Override
    public CardBindingStart resendBindingCode(String bindingId) {
        pause();
        return store.inTransaction(() -> {
            FakeBinding binding = openBinding(bindingId);
            OffsetDateTime allowedAt = binding.getCodeSentAt().plusSeconds(config.getResendCooldownSeconds());
            if (now().isBefore(allowedAt)) {
                throw refused(AtmosCodes.OTP_RESEND_TOO_EARLY, "SMS code was sent less than "
                        + config.getResendCooldownSeconds() + " s ago");
            }
            binding.setCodeSentAt(now());
            store.saveBinding(binding);
            log.info("[fake-provider] binding code sent again: bindingId={}", bindingId);
            return new CardBindingStart(bindingId, binding.getMaskedPhone());
        });
    }

    @Override
    public BoundCard bindCardConfirm(String bindingId, String otp) {
        pause();
        String token = "tok-" + UUID.randomUUID();
        BoundCard bound = store.inTransaction(() -> {
            FakeBinding binding = openBinding(bindingId);
            if (now().isAfter(binding.getCodeSentAt().plusSeconds(config.getOtpTtlSeconds()))) {
                throw refused(AtmosCodes.OTP_EXPIRED, "SMS code has expired");
            }
            requireCode(otp);
            binding.setConfirmedAt(now());
            store.saveBinding(binding);
            FakeCard card = FakeCard.builder()
                    .cardId("card-" + UUID.randomUUID())
                    .tokenHash(sha256(token))
                    .maskedPan(binding.getMaskedPan())
                    .last4(binding.getLast4())
                    .expiry(binding.getExpiry())
                    .cardHolder(binding.getCardHolder())
                    .maskedPhone(binding.getMaskedPhone())
                    .balance(config.getStartingBalance())
                    .active(true)
                    .createdAt(now())
                    .build();
            store.saveCard(card);
            return new BoundCard(token, card.getCardId(), card.getMaskedPan(), card.getExpiry(), card.getCardHolder());
        });
        log.info("[fake-provider] card bound: cardId={} masked={}", bound.cardId(), bound.maskedPan());
        return bound;
    }

    @Override
    public void removeCard(String cardId, String cardToken) {
        pause();
        store.inTransaction(() -> {
            FakeCard card = store.card(cardId)
                    .filter(FakeCard::isActive)
                    .filter(c -> c.getTokenHash().equals(sha256(cardToken)))
                    .orElseThrow(() -> refused(AtmosCodes.CARD_NOT_FOUND, "Карта не найдена"));
            card.setActive(false);
            card.setRemovedAt(now());
            store.saveCard(card);
            return null;
        });
        log.info("[fake-provider] card removed: cardId={}", cardId);
    }

    // ---- Charges ----

    @Override
    public PendingCharge charge(ChargeRequest request) {
        pause();
        boolean byToken = request.cardToken() != null;
        return store.inTransaction(() -> {
            Optional<FakeOperation> existing = store.operationByAccount(FakeOperation.Kind.CHARGE, request.account());
            if (existing.isPresent()) {
                // The same payment row again: never a second charge.
                return new PendingCharge(transactionIdsOf(existing.get()), existing.get().isOtpRequired());
            }
            String cardId = null;
            String last4;
            if (byToken) {
                FakeCard card = activeCard(request.cardToken());
                cardId = card.getCardId();
                last4 = card.getLast4();
            } else {
                String digits = digitsOf(request.cardNumber());
                last4 = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
            }
            requireAvailable(last4);
            requireMinimum(request.splits());
            FakeOperation charge = newOperation(FakeOperation.Kind.CHARGE, "chg-" + UUID.randomUUID(),
                    request.account(), cardId, last4, !byToken, request.splits());
            store.saveOperation(charge);
            log.info("[fake-provider] charge created: account={} total={} splits={}",
                    request.account(), request.total(), charge.getSplits().size());
            return new PendingCharge(transactionIdsOf(charge), !byToken);
        });
    }

    @Override
    public ChargeOutcome confirmCharge(List<String> transactionIds, String otp) {
        pause();
        Result<ChargeOutcome> result = store.inTransaction(() -> {
            FakeOperation charge = chargeOf(transactionIds.getFirst());
            if (charge.getStatus() != ProviderStatus.PENDING) {
                return Result.of(chargeOutcome(charge)); // confirming twice answers with where it stands
            }
            if (charge.isOtpRequired()) {
                requireCode(otp);
            }
            String last4 = charge.getLast4();
            boolean timesOut = last4.equals(TIMEOUT_THEN_SUCCESS_SUFFIX) || last4.equals(TIMEOUT_THEN_FAILED_SUFFIX);
            String decline = declineCodeFor(last4);
            if (decline == null && !debit(charge)) {
                decline = AtmosCodes.INSUFFICIENT_BALANCE;
            }
            if (decline != null) {
                charge.setStatus(ProviderStatus.FAILED);
                charge.setErrorCode(decline);
            } else {
                charge.setStatus(ProviderStatus.SUCCESS);
                charge.setSucceededAt(now());
                charge.getSplits().forEach(s -> s.setSuccessTransId("succ-" + s.getTransactionId()));
            }
            charge.setUpdatedAt(now());
            store.saveOperation(charge);
            log.info("[fake-provider] charge {}: account={} code={}", charge.getStatus(), charge.getAccount(), decline);
            return timesOut
                    ? Result.thenThrow(new ProviderTimeoutException("bulk/pay/apply", null))
                    : Result.of(chargeOutcome(charge));
        });
        return result.get();
    }

    /** Where one split's transaction stands, like {@code pay/get}. */
    @Override
    public ChargeOutcome getCharge(String transactionId) {
        pause();
        return store.inTransaction(() -> {
            FakeOperation charge = chargeOf(transactionId);
            FakeSplit split = charge.getSplits().stream()
                    .filter(s -> s.getTransactionId().equals(transactionId))
                    .findFirst().orElseThrow();
            return switch (charge.getStatus()) {
                case FAILED -> ChargeOutcome.failed(charge.getErrorCode());
                case PENDING -> ChargeOutcome.pending();
                default -> split.getReversedAt() != null
                        ? new ChargeOutcome(ProviderStatus.REVERSED, List.of(), null, null, AtmosCodes.ALREADY_REVERSED)
                        : new ChargeOutcome(ProviderStatus.SUCCESS, List.of(split.getSuccessTransId()),
                                split.getCommission(), ofdUrl(charge), null);
            };
        });
    }

    /**
     * Gives one split's transaction back in full — or a whole captured hold, named by its
     * hold id (how PaymentService reverses a hold until ATMOS says otherwise). Never twice
     * ({@link AtmosCodes#ALREADY_REVERSED}), never after the refund window
     * ({@link AtmosCodes#PAYMENT_CLOSED}), never on a {@link #REVERSE_FAILS_SUFFIX} card
     * ({@link AtmosCodes#NOT_CANCELLED}). Every attempt is recorded for the dev view.
     */
    @Override
    public void reverse(String successTransId, String reason) {
        pause();
        store.inTransaction(() -> {
            FakeOperation operation = store.operationBySuccessId(successTransId)
                    .or(() -> store.operation(successTransId).filter(o -> o.getKind() == FakeOperation.Kind.HOLD))
                    .orElseThrow(() -> refused(NOT_FOUND, "Транзакция не найдена"));
            boolean wholeHold = operation.getId().equals(successTransId);
            List<FakeSplit> splits = wholeHold
                    ? operation.getSplits()
                    : operation.getSplits().stream().filter(s -> successTransId.equals(s.getSuccessTransId())).toList();

            String refusal = null;
            if (operation.getSucceededAt() == null || splits.stream().anyMatch(s -> s.getReversedAt() != null)) {
                refusal = splits.stream().anyMatch(s -> s.getReversedAt() != null)
                        ? AtmosCodes.ALREADY_REVERSED : AtmosCodes.TRANSACTION_CLOSED;
            } else if (operation.getLast4().equals(REVERSE_FAILS_SUFFIX)) {
                refusal = AtmosCodes.NOT_CANCELLED;
            } else if (now().isAfter(operation.getSucceededAt().plusMonths(config.getRefundWindowMonths()))) {
                refusal = AtmosCodes.PAYMENT_CLOSED;
            }

            long amount = splits.stream().mapToLong(FakeSplit::getAmount).sum();
            store.saveReverse(FakeReverse.builder()
                    .id("rev-" + UUID.randomUUID())
                    .operationId(operation.getId())
                    .transactionId(successTransId)
                    .amount(refusal == null ? amount : 0)
                    .outcome(refusal == null ? FakeReverse.Outcome.REVERSED : FakeReverse.Outcome.REFUSED)
                    .errorCode(refusal)
                    .reason(reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500))
                    .createdAt(now())
                    .build());
            if (refusal != null) {
                log.info("[fake-provider] reverse refused: transaction={} code={}", successTransId, refusal);
                return Result.thenThrow(refusal.equals(AtmosCodes.ALREADY_REVERSED) || refusal.equals(AtmosCodes.TRANSACTION_CLOSED)
                        ? new TransactionClosedException(refusal)
                        : new PaymentProviderException(refusal, reverseRefusalMessage(refusal)));
            }
            splits.forEach(s -> s.setReversedAt(now()));
            credit(operation.getCardId(), amount);
            if (operation.allReversed()) {
                operation.setStatus(ProviderStatus.REVERSED);
            }
            operation.setUpdatedAt(now());
            store.saveOperation(operation);
            log.info("[fake-provider] reversed in full: transaction={} amount={}", successTransId, amount);
            return Result.of(null);
        }).get();
    }

    // ---- Holds ----

    @Override
    public HoldOutcome hold(HoldRequest request) {
        pause();
        return store.inTransaction(() -> {
            Optional<FakeOperation> existing = store.operationByAccount(FakeOperation.Kind.HOLD, request.account());
            if (existing.isPresent()) {
                return holdOutcome(existing.get()); // the same payment row again: the same hold
            }
            FakeCard card = activeCard(request.cardToken());
            requireAvailable(card.getLast4());
            requireMinimum(request.splits());
            FakeOperation hold = newOperation(FakeOperation.Kind.HOLD, "hold-" + UUID.randomUUID(),
                    request.account(), card.getCardId(), card.getLast4(), false, request.splits());
            hold.setHoldTill(now().plusMinutes(request.durationMinutes()));
            store.saveOperation(hold);
            log.info("[fake-provider] hold created: holdId={} total={} till={}", hold.getId(), hold.getAmount(), hold.getHoldTill());
            return holdOutcome(hold);
        });
    }

    @Override
    public HoldOutcome confirmHold(String holdId, String otp) {
        pause();
        return store.inTransaction(() -> {
            FakeOperation hold = holdOf(holdId);
            if (hold.getStatus() != ProviderStatus.PENDING) {
                return Result.of(holdOutcome(hold));
            }
            String last4 = hold.getLast4();
            // For a hold, the lost answers (0091 / 0092) come at the capture, where the money is taken.
            String decline = last4.equals(TIMEOUT_THEN_FAILED_SUFFIX) ? null : declineCodeFor(last4);
            if (decline == null && !debit(hold)) {
                decline = AtmosCodes.INSUFFICIENT_BALANCE;
            }
            hold.setStatus(decline == null ? ProviderStatus.HELD : ProviderStatus.FAILED);
            hold.setErrorCode(decline);
            hold.setUpdatedAt(now());
            store.saveOperation(hold);
            log.info("[fake-provider] hold {}: holdId={} code={}", hold.getStatus(), holdId, decline);
            return Result.of(holdOutcome(hold));
        }).get();
    }

    /** Takes the whole held amount. A lapsed hold is {@link HoldExpiredException}, a released one {@link HoldCancelledException}. */
    @Override
    public HoldOutcome captureHold(String holdId) {
        pause();
        return store.inTransaction(() -> {
            FakeOperation hold = holdOf(holdId);
            if (lapse(hold)) {
                store.saveOperation(hold);
                return Result.<HoldOutcome>thenThrow(new HoldExpiredException(HOLD_EXPIRED));
            }
            if (hold.getStatus() == ProviderStatus.RELEASED) {
                return Result.<HoldOutcome>thenThrow(AtmosCodes.HOLD_EXPIRED.equals(hold.getErrorCode())
                        ? new HoldExpiredException(HOLD_EXPIRED) : new HoldCancelledException(HOLD_CANCELLED));
            }
            if (hold.getStatus() != ProviderStatus.HELD) {
                return Result.<HoldOutcome>thenThrow(new TransactionClosedException(TRANSACTION_CLOSED));
            }
            String last4 = hold.getLast4();
            if (last4.equals(CAPTURE_FAILS_SUFFIX)) {
                // The bank refuses with an ordinary code; the hold itself stays where it was.
                log.info("[fake-provider] capture refused: holdId={} code={}", holdId, AtmosCodes.CARD_BLOCKED);
                return Result.<HoldOutcome>thenThrow(new PaymentProviderException(AtmosCodes.CARD_BLOCKED, "Карта заблокирована"));
            }
            if (last4.equals(TIMEOUT_THEN_FAILED_SUFFIX)) {
                // No answer, and the bank cancelled the hold: get-status says RELEASED.
                letGo(hold, HOLD_CANCELLED);
                store.saveOperation(hold);
                return Result.<HoldOutcome>thenThrow(new ProviderTimeoutException("hold capture", null));
            }
            hold.setStatus(ProviderStatus.CAPTURED);
            hold.setSucceededAt(now());
            hold.getSplits().forEach(s -> s.setSuccessTransId("succ-" + s.getTransactionId()));
            hold.setUpdatedAt(now());
            store.saveOperation(hold);
            log.info("[fake-provider] hold captured: holdId={}", holdId);
            return last4.equals(TIMEOUT_THEN_SUCCESS_SUFFIX)
                    ? Result.<HoldOutcome>thenThrow(new ProviderTimeoutException("hold capture", null))
                    : Result.of(holdOutcome(hold));
        }).get();
    }

    /** Lets a hold go. Idempotent: an already released or lapsed hold answers RELEASED again. */
    @Override
    public HoldOutcome releaseHold(String holdId) {
        pause();
        return store.inTransaction(() -> {
            FakeOperation hold = holdOf(holdId);
            lapse(hold);
            switch (hold.getStatus()) {
                case HELD, PENDING -> letGo(hold, null);
                case RELEASED -> {
                    // already let go: answered the same
                }
                default -> {
                    return Result.<HoldOutcome>thenThrow(new TransactionClosedException(TRANSACTION_CLOSED));
                }
            }
            store.saveOperation(hold);
            log.info("[fake-provider] hold released: holdId={}", holdId);
            return Result.of(holdOutcome(hold));
        }).get();
    }

    @Override
    public HoldOutcome getHold(String holdId) {
        pause();
        return store.inTransaction(() -> {
            FakeOperation hold = holdOf(holdId);
            if (lapse(hold)) {
                store.saveOperation(hold);
            }
            return holdOutcome(hold);
        });
    }

    // ---- Money ----

    /** Takes the operation's amount from its card; false when the card has too little. A card-number charge has no balance here. */
    private boolean debit(FakeOperation operation) {
        if (operation.getCardId() == null) {
            return true;
        }
        FakeCard card = store.card(operation.getCardId()).orElseThrow();
        if (card.getBalance() < operation.getAmount()) {
            return false;
        }
        card.setBalance(card.getBalance() - operation.getAmount());
        store.saveCard(card);
        return true;
    }

    private void credit(String cardId, long amount) {
        if (cardId == null || amount == 0) {
            return;
        }
        FakeCard card = store.card(cardId).orElseThrow();
        card.setBalance(card.getBalance() + amount);
        store.saveCard(card);
    }

    /** A held amount going back to the card: released by request ({@code code} null) or by the provider. */
    private void letGo(FakeOperation hold, String code) {
        if (hold.getStatus() == ProviderStatus.HELD) {
            credit(hold.getCardId(), hold.getAmount());
        }
        hold.setStatus(ProviderStatus.RELEASED);
        hold.setErrorCode(code);
        hold.setUpdatedAt(now());
    }

    /** A hold nobody settled lets go on its own at its end; true if that happened just now. */
    private boolean lapse(FakeOperation hold) {
        if (hold.getStatus() == ProviderStatus.HELD && !now().isBefore(hold.getHoldTill())) {
            letGo(hold, HOLD_EXPIRED);
            return true;
        }
        return false;
    }

    // ---- Answers ----

    private ChargeOutcome chargeOutcome(FakeOperation charge) {
        return switch (charge.getStatus()) {
            case SUCCESS -> new ChargeOutcome(ProviderStatus.SUCCESS,
                    charge.getSplits().stream().map(FakeSplit::getSuccessTransId).toList(),
                    charge.getCommission(), ofdUrl(charge), null);
            case FAILED -> ChargeOutcome.failed(charge.getErrorCode());
            case REVERSED -> new ChargeOutcome(ProviderStatus.REVERSED, List.of(), null, null, AtmosCodes.ALREADY_REVERSED);
            default -> ChargeOutcome.pending();
        };
    }

    private HoldOutcome holdOutcome(FakeOperation hold) {
        ProviderStatus status = hold.getStatus();
        return new HoldOutcome(hold.getId(), status, hold.getHoldTill(),
                status == ProviderStatus.CAPTURED || status == ProviderStatus.REVERSED ? hold.getCommission() : null,
                status == ProviderStatus.FAILED || status == ProviderStatus.RELEASED ? hold.getErrorCode() : null);
    }

    private static String ofdUrl(FakeOperation charge) {
        return "https://ofd.fake/receipt/" + charge.getAccount();
    }

    // ---- Checks ----

    private FakeOperation newOperation(FakeOperation.Kind kind, String id, String account, String cardId,
                                       String last4, boolean otpRequired, List<Split> requested) {
        List<FakeSplit> splits = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            Split split = requested.get(i);
            splits.add(FakeSplit.builder()
                    .transactionId((kind == FakeOperation.Kind.HOLD ? "htx-" : "txn-") + UUID.randomUUID())
                    .position(i)
                    .recipientId(split.recipientId())
                    .platform(split.platform())
                    .amount(split.amount())
                    .commission(commissionOf(split.amount()))
                    .build());
        }
        return FakeOperation.builder()
                .id(id)
                .kind(kind)
                .account(account)
                .cardId(cardId)
                .last4(last4)
                .otpRequired(otpRequired)
                .amount(splits.stream().mapToLong(FakeSplit::getAmount).sum())
                .commission(splits.stream().mapToLong(FakeSplit::getCommission).sum())
                .status(ProviderStatus.PENDING)
                .createdAt(now())
                .updatedAt(now())
                .splits(splits)
                .build();
    }

    /** What ATMOS keeps of one split: 1.5%, so the recipient gets {@link PriceCalculator#netAfterFee}. */
    public static long commissionOf(long splitAmount) {
        return splitAmount - PriceCalculator.netAfterFee(splitAmount, COMMISSION_BPS);
    }

    private static String declineCodeFor(String last4) {
        return switch (last4) {
            case DECLINED_SUFFIX, TIMEOUT_THEN_FAILED_SUFFIX -> AtmosCodes.CARD_BLOCKED;
            case INSUFFICIENT_SUFFIX -> AtmosCodes.INSUFFICIENT_FUNDS;
            case EXPIRED_SUFFIX -> AtmosCodes.CARD_EXPIRED;
            default -> null;
        };
    }

    private static void requireAvailable(String last4) {
        if (UNAVAILABLE_SUFFIX.equals(last4)) {
            throw refused(AtmosCodes.SERVICE_UNAVAILABLE, "Сервис временно недоступен");
        }
    }

    private void requireMinimum(List<Split> splits) {
        if (splits.stream().anyMatch(s -> s.amount() < config.getMinAmount())) {
            throw refused(AtmosCodes.WRONG_AMOUNT, "Неверная сумма для платежа");
        }
    }

    /** An SMS code of the configured length; the reserved ones are wrong or expired. */
    private void requireCode(String otp) {
        String code = otp == null ? "" : otp.trim();
        if (EXPIRED_CODE.equals(code)) {
            throw refused(AtmosCodes.OTP_EXPIRED, "SMS code has expired");
        }
        if (WRONG_CODE.equals(code) || !code.matches("\\d{" + config.getOtpLength() + "}")) {
            throw refused(AtmosCodes.WRONG_OTP, "OTP код указан неверно");
        }
    }

    private static String reverseRefusalMessage(String code) {
        return switch (code) {
            case AtmosCodes.NOT_CANCELLED -> "Невозможно отменить транзакцию, попробуйте позже";
            case AtmosCodes.PAYMENT_CLOSED -> "Платеж закрыт: срок отмены истек";
            default -> "Транзакция закрыта";
        };
    }

    private FakeBinding openBinding(String bindingId) {
        return store.binding(bindingId)
                .filter(b -> b.getConfirmedAt() == null)
                .orElseThrow(() -> refused(AtmosCodes.BINDING_NOT_FOUND, "Unknown or already-confirmed binding"));
    }

    private FakeCard activeCard(String token) {
        return store.cardByTokenHash(sha256(token))
                .filter(FakeCard::isActive)
                .orElseThrow(() -> refused(AtmosCodes.CARD_NOT_FOUND, "Карта не найдена"));
    }

    private FakeOperation chargeOf(String transactionId) {
        return store.operationByTransaction(transactionId)
                .filter(o -> o.getKind() == FakeOperation.Kind.CHARGE)
                .orElseThrow(() -> refused(NOT_FOUND, "Транзакция не найдена"));
    }

    private FakeOperation holdOf(String holdId) {
        return store.operation(holdId)
                .filter(o -> o.getKind() == FakeOperation.Kind.HOLD)
                .orElseThrow(() -> refused(NOT_FOUND, "Холд не найден"));
    }

    private static List<String> transactionIdsOf(FakeOperation operation) {
        return operation.getSplits().stream().map(FakeSplit::getTransactionId).toList();
    }

    private Optional<String> currentUserPhone() {
        if (userRepository == null) {
            return Optional.empty();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID userId)) {
            return Optional.empty();
        }
        return userRepository.findById(userId).map(User::getPhone);
    }

    /** MM/YY with a real month, or null. */
    private static YearMonth parseExpiry(String expiry) {
        if (expiry == null || !expiry.trim().matches("\\d{2}/\\d{2}")) {
            return null;
        }
        int month = Integer.parseInt(expiry.trim().substring(0, 2));
        int year = 2000 + Integer.parseInt(expiry.trim().substring(3));
        return month >= 1 && month <= 12 ? YearMonth.of(year, month) : null;
    }

    /** A realistic wait before answering ({@code app.payments.fake.delay-*-ms}; 0 in tests). */
    private void pause() {
        long min = Math.max(0, config.getDelayMinMs());
        long max = Math.max(min, config.getDelayMaxMs());
        if (max == 0) {
            return;
        }
        try {
            Thread.sleep(min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static PaymentProviderException refused(String code, String message) {
        log.info("[fake-provider] refused: code={}", code);
        return new PaymentProviderException(code, message);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String digitsOf(String cardNumber) {
        return cardNumber == null ? "" : cardNumber.replaceAll("\\D", "");
    }

    /**
     * What a call answers once its changes are committed: a value, or an exception thrown
     * only after the commit — a lost answer or a refusal must not undo what the provider did.
     */
    private record Result<T>(T value, RuntimeException error) {

        static <T> Result<T> of(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> thenThrow(RuntimeException error) {
            return new Result<>(null, error);
        }

        T get() {
            if (error != null) {
                throw error;
            }
            return value;
        }
    }
}
