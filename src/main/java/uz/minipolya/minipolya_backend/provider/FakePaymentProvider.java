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
import uz.minipolya.minipolya_backend.provider.fake.FakeProviderStore;
import uz.minipolya.minipolya_backend.repository.UserRepository;

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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in for ATMOS in dev and tests, so every business flow above it runs without
 * real money. Active only while {@code app.payments.provider=fake} and never under the
 * {@code atmos} profile; it refuses to start under {@code prod}.
 *
 * <p>It answers the way ATMOS does, with ATMOS's codes ({@link AtmosCodes}), so switching
 * to the real client changes nothing above the {@link PaymentProvider} interface.
 *
 * <p><b>Binding.</b> Any 16-digit Uzcard ({@code 8600…}) or Humo ({@code 9860…}) number
 * with a valid MM/YY that has not passed; anything else is
 * {@link AtmosCodes#WRONG_CARD_NUMBER}, a bad month {@link AtmosCodes#WRONG_EXPIRY}, a past
 * one {@link AtmosCodes#CARD_EXPIRED}. Any SMS code of {@code app.payments.fake.otp-length}
 * digits is accepted except the reserved {@value #WRONG_CODE} (wrong) and
 * {@value #EXPIRED_CODE} (expired); a code lives {@code otp-ttl-seconds} and may be sent
 * again after {@code resend-cooldown-seconds}.
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
    /** A card number ending in this is always declined, so failures can be tested. */
    public static final String DECLINED_SUFFIX = "0002";
    /** The commission taken from each split: 150 bps = 1.5%, like ATMOS. */
    public static final int COMMISSION_BPS = 150;
    public static final String HOLD_EXPIRED = AtmosCodes.HOLD_EXPIRED;
    public static final String HOLD_CANCELLED = AtmosCodes.HOLD_CANCELLED;
    public static final String TRANSACTION_CLOSED = AtmosCodes.TRANSACTION_CLOSED;
    public static final String DECLINED = AtmosCodes.CARD_BLOCKED;

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final String CARD_HOLDER = "MINIPOLYA TEST";

    private final Environment environment;
    private final Clock clock;
    private final AppProperties.Fake config;
    private final FakeProviderStore store;
    /** Null in unit tests: the masked phone then ends in {@code **}. */
    private final UserRepository userRepository;

    private final Map<String, FakeTransaction> transactions = new ConcurrentHashMap<>();
    /** account → the transactions created for it, so a retry returns them instead of charging again. */
    private final Map<String, List<String>> chargesByAccount = new ConcurrentHashMap<>();
    private final Map<String, FakeHold> holds = new ConcurrentHashMap<>();

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
            String code = otp == null ? "" : otp.trim();
            if (EXPIRED_CODE.equals(code)
                    || now().isAfter(binding.getCodeSentAt().plusSeconds(config.getOtpTtlSeconds()))) {
                throw refused(AtmosCodes.OTP_EXPIRED, "SMS code has expired");
            }
            if (WRONG_CODE.equals(code) || !code.matches("\\d{" + config.getOtpLength() + "}")) {
                throw refused(AtmosCodes.WRONG_OTP, "OTP код указан неверно");
            }
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
        boolean byToken = request.cardToken() != null;
        boolean declines;
        if (byToken) {
            declines = activeCard(request.cardToken()).getLast4().equals(DECLINED_SUFFIX);
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
            throw new PaymentProviderException(AtmosCodes.WRONG_OTP, "Wrong SMS code");
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
        FakeCard card = activeCard(request.cardToken());
        String holdId = "hold-" + UUID.randomUUID();
        OffsetDateTime till = now().plusMinutes(request.durationMinutes());
        holds.put(holdId, new FakeHold(holdId, request.splits(), till, card.getLast4().equals(DECLINED_SUFFIX)));
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

    private FakeBinding openBinding(String bindingId) {
        return store.binding(bindingId)
                .filter(b -> b.getConfirmedAt() == null)
                .orElseThrow(() -> refused(AtmosCodes.BINDING_NOT_FOUND, "Unknown or already-confirmed binding"));
    }

    private FakeCard activeCard(String token) {
        return store.inTransaction(() -> store.cardByTokenHash(sha256(token)))
                .filter(FakeCard::isActive)
                .orElseThrow(() -> refused(AtmosCodes.CARD_NOT_FOUND, "Карта не найдена"));
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

    private String digitsOf(String cardNumber) {
        return cardNumber == null ? "" : cardNumber.replaceAll("\\D", "");
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
