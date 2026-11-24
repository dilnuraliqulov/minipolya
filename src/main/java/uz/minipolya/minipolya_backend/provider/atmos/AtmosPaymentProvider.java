package uz.minipolya.minipolya_backend.provider.atmos;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.provider.AtmosCodes;
import uz.minipolya.minipolya_backend.provider.CardMask;
import uz.minipolya.minipolya_backend.provider.BoundCard;
import uz.minipolya.minipolya_backend.provider.CardBindingStart;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.HoldCancelledException;
import uz.minipolya.minipolya_backend.provider.HoldExpiredException;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.PendingCharge;
import uz.minipolya.minipolya_backend.provider.ProviderCheck;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.provider.TransactionClosedException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real ATMOS client ({@code https://docs.atmos.uz/ru/}), active only under the
 * {@code atmos} profile. Every amount is tiyin.
 *
 * <p><b>Auth.</b> {@code POST /token} with Basic auth (consumer key:secret) and
 * {@code grant_type=client_credentials}; the token is cached until {@code expires_in}
 * minus five minutes, and a 401 refreshes it once and retries the call.
 *
 * <p><b>Answers.</b> {@code result.code == "OK"} is success. Declines (insufficient funds,
 * blocked or expired card) on confirmation are FAILED outcomes; {@code STPIMS-ERR-092}
 * (transaction closed) and {@code -145} (hold cancelled) / {@code -144} (hold expired) are
 * our own exceptions; every other code is a {@link PaymentProviderException} carrying it —
 * logged, never shown raw to a player. A call that times out is a
 * {@link ProviderTimeoutException}: never assumed failed, never sent again.
 *
 * <p><b>Never logged</b>: request or response bodies, card numbers, expiry dates, SMS codes,
 * card tokens, credentials or the access token. Only operation names, ids and result codes.
 *
 * <p>Where the docs contradict themselves this follows their examples; each such spot is a
 * {@code TODO(ATMOS)} naming the open question.
 */
@Slf4j
@Component
@Profile("atmos")
public class AtmosPaymentProvider implements PaymentProvider {

    static final String OK = "OK";
    static final String TRANSACTION_CLOSED = AtmosCodes.TRANSACTION_CLOSED;
    /** A second reverse of the same transaction: gone either way, so "closed" as well. */
    static final String ALREADY_REVERSED = AtmosCodes.ALREADY_REVERSED;
    static final String HOLD_CANCELLED = "STPIMS-ERR-145";
    static final String HOLD_EXPIRED = "STPIMS-ERR-144";
    /** Codes that mean the card was declined: a FAILED outcome, not an error. */
    static final Set<String> DECLINES = Set.of(
            "STPIMS-ERR-057", // insufficient funds
            "STPIMS-ERR-112", // insufficient balance
            "STPIMS-ERR-058", // card blocked
            "STPIMS-ERR-079", // card not activated
            "STPIMS-ERR-067", // card expired
            "STPIMS-ERR-090"  // card not found / wrong expiry
    );
    /**
     * TODO(ATMOS): the docs give "111111" as the OTP to send when applying a payment or hold
     * made with a card token (no SMS is sent then). Confirm this is right for production.
     */
    static final String TOKEN_OTP = "111111";
    private static final long TOKEN_MARGIN_SECONDS = 300;

    private final AppProperties.Atmos config;
    private final RestClient http;
    private final Clock clock;
    private final AtmosRecipientField recipientField;
    private final boolean fallbackToOwnStore;
    private final ObjectMapper json = JsonMapper.builder().build();

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public AtmosPaymentProvider(AppProperties appProperties,
                                @Qualifier("atmosRestClientBuilder") RestClient.Builder builder,
                                Clock clock,
                                Environment environment) {
        this.config = appProperties.getAtmos();
        this.http = builder.baseUrl(config.getBaseUrl() == null ? "" : config.getBaseUrl()).build();
        this.clock = clock;
        this.recipientField = AtmosRecipientField.of(config.getRecipientField());
        // Only a staging test server may pay itself instead of a recipient it does not know,
        // and prod is named explicitly so that no combination of profiles can turn it on there.
        this.fallbackToOwnStore = environment.acceptsProfiles(Profiles.of("staging"))
                && !environment.acceptsProfiles(Profiles.of("prod"));
    }

    /**
     * Fails the startup, naming every missing variable, rather than the first payment.
     *
     * <p>These four are what no ATMOS call can be made without, so the profile cannot run
     * without them. {@code ATMOS_PLATFORM_RECIPIENT_ID} is not one of them: it is the one
     * value a sandbox is usually given last, and without it only the platform's own split
     * of a team-game fee fails ({@link #recipientOf}) — every ordinary booking, which the
     * platform takes nothing from, is paid and refunded as usual. It is logged as missing
     * here, loudly, so it is not a surprise later.
     *
     * <p>{@code RequiredEnvironmentPostProcessor} checks the same four from the environment
     * before any bean exists, which is where a deployed server normally meets this; this
     * runs on the bound {@code app.atmos.*} values, however they were set.
     */
    @PostConstruct
    public void requireConfiguration() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("ATMOS_BASE_URL", config.getBaseUrl());
        required.put("ATMOS_CONSUMER_KEY", config.getConsumerKey());
        required.put("ATMOS_CONSUMER_SECRET", config.getConsumerSecret());
        required.put("ATMOS_STORE_ID", config.getStoreId());
        List<String> missing = required.entrySet().stream()
                .filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(Map.Entry::getKey)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The atmos profile is on but these environment variables are not set: "
                    + String.join(", ", missing));
        }
        if (platformRecipientMissing()) {
            log.warn("ATMOS_PLATFORM_RECIPIENT_ID is not set: a team game's platform fee cannot be collected "
                    + "until it is. Ordinary bookings are unaffected.");
        }
        log.info("AtmosPaymentProvider is active: environment={} storeId={} recipientField={} receipts={}",
                AtmosEnvironment.of(config.getEnvironment()).value(), config.getStoreId(),
                recipientField.field(), receiptsOn());
        if (fallbackToOwnStore) {
            log.warn("Staging: a payment whose recipient has no ATMOS id will be paid to our own store "
                    + "instead of failing. Never on a production server.");
        }
    }

    private boolean platformRecipientMissing() {
        return config.getPlatformRecipientId() == null || config.getPlatformRecipientId().isBlank();
    }

    /**
     * Asks for a <b>fresh</b> access token (never the cached one), which is the smallest
     * call that proves all four of the network, the base URL, the consumer key and the
     * secret. Nothing is charged and nothing is stored.
     *
     * <p>Never throws: a refused, timed-out or unreachable provider comes back as a
     * {@link ProviderCheck} that is not ok, carrying the code. An unexpected exception is
     * logged by its type alone — its message could hold a URL or a credential.
     */
    @Override
    public ProviderCheck check() {
        String host = ProviderCheck.hostOf(config.getBaseUrl());
        // Which side of ATMOS these credentials are for. The sandbox answers on the same
        // host as production, so this is configured, not guessed (AtmosEnvironment).
        String environment = AtmosEnvironment.of(config.getEnvironment()).value();
        try {
            token(true);
            return ProviderCheck.ok(ProviderCheck.ATMOS, host, environment);
        } catch (PaymentProviderException ex) {
            log.warn("ATMOS provider check failed: code={}", ex.getCode());
            return ProviderCheck.failed(ProviderCheck.ATMOS, host, environment, ex.getCode());
        } catch (ProviderTimeoutException ex) {
            log.warn("ATMOS provider check timed out");
            return ProviderCheck.failed(ProviderCheck.ATMOS, host, environment, ProviderCheck.TIMEOUT);
        } catch (RuntimeException ex) {
            log.warn("ATMOS provider check could not be made: {}", ex.getClass().getSimpleName());
            return ProviderCheck.failed(ProviderCheck.ATMOS, host, environment, ProviderCheck.UNREACHABLE);
        }
    }

    // ---- Cards ----

    @Override
    public CardBindingStart bindCardInit(String cardNumber, String expiry) {
        ObjectNode body = json.createObjectNode()
                .put("card_number", digitsOf(cardNumber))
                .put("expiry", toAtmosExpiry(expiry));
        JsonNode answer = call("bind-card/init", HttpMethod.POST, "/partner/bind-card/init", body);
        requireOk(answer, "bind-card/init");
        return new CardBindingStart(answer.path("transaction_id").asString(), answer.path("phone").asString(null));
    }

    /**
     * ATMOS has no resend for a card binding: {@code merchant/pay/retry-otp} is for a
     * <em>payment</em> being confirmed by SMS, and the docs say so ("Данный метод работает
     * только для транзакций"). Re-running {@code bind-card/init} would need the card number,
     * which we never keep, so this is refused with {@link AtmosCodes#RESEND_UNSUPPORTED} and
     * the player enters the card again.
     */
    @Override
    public CardBindingStart resendBindingCode(String bindingId) {
        throw new PaymentProviderException(AtmosCodes.RESEND_UNSUPPORTED, "ATMOS cannot resend a binding code");
    }

    @Override
    public BoundCard bindCardConfirm(String bindingId, String otp) {
        ObjectNode body = withId(json.createObjectNode(), "transaction_id", bindingId).put("otp", otp);
        JsonNode answer = call("bind-card/confirm", HttpMethod.POST, "/partner/bind-card/confirm", body);
        requireOk(answer, "bind-card/confirm");
        // The docs' example nests the card under "data"; read it flat too, in case an
        // older gateway answers the way the parameter table reads.
        JsonNode card = answer.has("data") ? answer.path("data") : answer;
        return new BoundCard(card.path("card_token").asString(), card.path("card_id").asString(),
                maskPan(card.path("pan").asString("")), fromAtmosExpiry(card.path("expiry").asString("")),
                card.path("card_holder").asString(null));
    }

    @Override
    public void removeCard(String cardId, String cardToken) {
        ObjectNode body = withId(json.createObjectNode(), "id", cardId).put("token", cardToken);
        requireOk(call("remove-card", HttpMethod.POST, "/partner/remove-card", body), "remove-card");
    }

    // ---- Charges ----

    /**
     * One recipient is a <b>single payment</b> ({@code pay/create} → {@code pay/pre-apply}
     * → {@code pay/apply}); several are a <b>multi-transaction</b> ({@code bulk/...}). The
     * docs describe the two as separate flows, and an ordinary booking pays one recipient —
     * the stadium owner, since the platform takes nothing from a booking — so it takes the
     * documented single path rather than a bulk of one.
     *
     * <p>Either way the card is sent at pre-apply: a token confirms with the fixed OTP, a
     * card number makes ATMOS send the cardholder an SMS code.
     */
    @Override
    public PendingCharge charge(ChargeRequest request) {
        List<Split> splits = payable(request.splits(), "payment " + request.account());
        return splits.size() == 1 ? singleCharge(request, splits.getFirst()) : bulkCharge(request, splits);
    }

    /**
     * The splits as they can actually be paid.
     *
     * <p>Normally they are themselves, and a recipient with no ATMOS id is refused: money
     * that cannot reach the person it is owed to must not be taken. On a <b>staging</b> test
     * server that refusal would block the thing staging exists for — walking a real ATMOS
     * payment end to end — because an owner's recipient id is entered by an admin after the
     * contract is signed, and a sandbox platform id arrives later still. So there, and only
     * there, the splits collapse into one payment of the same total to our own store, with a
     * warning naming the payment.
     *
     * <p>Never under {@code prod}: {@link #fallbackToOwnStore} is false there whatever else
     * is active, so {@link #recipientOf} refuses as before. The money is then ours rather
     * than the owner's, which is exactly why it may only happen where no real money moves.
     */
    private List<Split> payable(List<Split> splits, String what) {
        if (!fallbackToOwnStore || recipientsKnown(splits)) {
            return splits;
        }
        long total = splits.stream().mapToLong(Split::amount).sum();
        log.warn("Staging fallback: {} has a recipient with no ATMOS id, so its {} split(s) are paid to "
                + "our own store as one payment of {} tiyin. Nobody is paid out by this.",
                what, splits.size(), total);
        return List.of(Split.owner(config.getStoreId(), total));
    }

    private boolean recipientsKnown(List<Split> splits) {
        return splits.stream().allMatch(split -> split.platform()
                ? !platformRecipientMissing()
                : split.recipientId() != null && !split.recipientId().isBlank());
    }

    /**
     * {@code pay/create} with the one recipient, then {@code pay/pre-apply}.
     *
     * <p>TODO(ATMOS): {@code create} names the store being paid and {@code pre-apply} /
     * {@code apply} name ours, which is how the bulk flow reads; the docs show one
     * {@code store_id} per call without saying whose. Also: the parameter table says
     * {@code account} "takes a numeric value" while every example is text ({@code "user_1"}).
     * Ours is the payment id, which is what keeps ATMOS from taking the same payment twice.
     */
    private PendingCharge singleCharge(ChargeRequest request, Split split) {
        ObjectNode create = recipient(json.createObjectNode(), split)
                .put("account", request.account())
                .put("amount", split.amount());
        if (receiptsOn()) {
            create.set("ofd_items", ofdItems(split));
        }
        JsonNode created = call("pay/create", HttpMethod.POST, "/merchant/pay/create", create);
        requireOk(created, "pay/create");
        String id = created.path("transaction_id").asString(null);
        if (id == null || id.isBlank()) {
            throw new PaymentProviderException("NO_TRANSACTION", "ATMOS created no transaction");
        }

        ObjectNode preApply = withId(json.createObjectNode(), "store_id", config.getStoreId());
        withId(preApply, "transaction_id", id);
        putCard(preApply, request);
        JsonNode preApplied = call("pay/pre-apply", HttpMethod.POST, "/merchant/pay/pre-apply", preApply);
        requireOkUnlessDeclined(preApplied, request, "pay/pre-apply");
        return new PendingCharge(List.of(id), request.cardToken() == null);
    }

    /**
     * {@code bulk/pay/create} with one item per split, then {@code bulk/pay/pre-apply}.
     *
     * <p>TODO(ATMOS): the create table puts {@code store_id} in each params item but the
     * example has one at the top; recipients differ per split, so it is sent per item here.
     * The example sends {@code amount} as a number, so do we. Whether {@code ofd_items} may
     * be sent per item in a bulk payment is undocumented, and so is paying a multi-transaction
     * with a card number rather than a token: the bulk pre-apply example shows only
     * {@code card_token}, while the single-payment one shows both.
     */
    private PendingCharge bulkCharge(ChargeRequest request, List<Split> splits) {
        ObjectNode create = withId(json.createObjectNode(), "store_id", config.getStoreId());
        ArrayNode params = create.putArray("params");
        for (Split split : splits) {
            ObjectNode item = recipient(params.addObject(), split)
                    .put("account", request.account())
                    .put("amount", split.amount())
                    .put("details", detailsOf(split));
            if (receiptsOn()) {
                item.set("ofd_items", ofdItems(split));
            }
        }
        JsonNode created = call("bulk/pay/create", HttpMethod.POST, "/merchant/bulk/pay/create", create);
        requireOk(created, "bulk/pay/create");
        List<String> ids = new ArrayList<>();
        created.path("transaction_id").forEach(id -> ids.add(id.asString()));
        if (ids.isEmpty()) {
            throw new PaymentProviderException("NO_TRANSACTION", "ATMOS created no transaction");
        }

        ObjectNode preApply = withId(json.createObjectNode(), "store_id", config.getStoreId());
        ids.forEach(id -> preApply.withArray("transaction_id").add(asNumber(id)));
        putCard(preApply, request);
        JsonNode preApplied = call("bulk/pay/pre-apply", HttpMethod.POST, "/merchant/bulk/pay/pre-apply", preApply);
        requireOkUnlessDeclined(preApplied, request, "bulk/pay/pre-apply");
        return new PendingCharge(ids, request.cardToken() == null);
    }

    /** A token, or the card number and expiry — never both; ATMOS then sends the SMS code. */
    private void putCard(ObjectNode body, ChargeRequest request) {
        if (request.cardToken() != null) {
            body.put("card_token", request.cardToken());
        } else {
            body.put("card_number", digitsOf(request.cardNumber())).put("expiry", toAtmosExpiry(request.expiry()));
        }
    }

    /** A decline at pre-apply is an answer, not a refusal: no money has moved either way. */
    private void requireOkUnlessDeclined(JsonNode answer, ChargeRequest request, String operation) {
        String code = codeOf(answer);
        if (DECLINES.contains(code)) {
            // Declined before any money moved: the confirmation will fail the same way.
            log.info("ATMOS pre-apply declined: account={} code={}", request.account(), code);
            return;
        }
        requireOk(answer, operation);
    }

    /** One transaction is {@code pay/apply}, several are {@code bulk/pay/apply}. */
    @Override
    public ChargeOutcome confirmCharge(List<String> transactionIds, String otp) {
        boolean single = transactionIds.size() == 1;
        String operation = single ? "pay/apply" : "bulk/pay/apply";
        String path = single ? "/merchant/pay/apply" : "/merchant/bulk/pay/apply";
        ObjectNode body = withId(json.createObjectNode(), "store_id", config.getStoreId())
                .put("otp", otp == null ? TOKEN_OTP : otp);
        if (single) {
            withId(body, "transaction_id", transactionIds.getFirst());
        } else {
            transactionIds.forEach(id -> body.withArray("transaction_id").add(asNumber(id)));
        }
        JsonNode answer = call(operation, HttpMethod.POST, path, body);
        String code = codeOf(answer);
        if (DECLINES.contains(code)) {
            log.info("ATMOS charge declined: transactions={} code={}", transactionIds, code);
            return ChargeOutcome.failed(code);
        }
        requireOk(answer, operation);
        return outcomeOf(answer);
    }

    /**
     * What became of one transaction, asked after an answer was lost.
     *
     * <p>The docs' own example answers {@code STPIMS-ERR-092} ("Транзакция закрыта")
     * while carrying a complete, confirmed transaction with its {@code success_trans_id} — so
     * here the <b>transaction is the answer, not the code</b>. Taking the code at face value
     * would report a payment that had gone through as given back, and the reconcile job would
     * eventually fail a booking the player had paid for. Only when the answer carries no
     * transaction to read is "closed" all we know.
     *
     * <p>TODO(ATMOS): confirm that a closed-and-confirmed transaction is one that was paid
     * rather than one that was reversed; the docs list no status values for {@code pay/get},
     * which is also why {@code confirmed=true} is what "paid" is read from.
     */
    @Override
    public ChargeOutcome getCharge(String transactionId) {
        ObjectNode body = withId(withId(json.createObjectNode(), "store_id", config.getStoreId()),
                "transaction_id", transactionId);
        JsonNode answer = call("pay/get", HttpMethod.POST, "/merchant/pay/get", body);
        String code = codeOf(answer);
        if (DECLINES.contains(code)) {
            return ChargeOutcome.failed(code);
        }
        if (TRANSACTION_CLOSED.equals(code)) {
            ChargeOutcome asRead = outcomeOf(answer);
            return asRead.succeeded() ? asRead
                    : new ChargeOutcome(ProviderStatus.REVERSED, List.of(), null, null, code);
        }
        requireOk(answer, "pay/get");
        return outcomeOf(answer);
    }

    /**
     * {@code transaction_id} here is the {@code success_trans_id} the apply answered with,
     * and the whole transaction goes back: no {@code hold_amount} is sent, because there is
     * no partial refund anywhere in this application.
     */
    @Override
    public void reverse(String successTransId, String reason) {
        ObjectNode body = withId(json.createObjectNode(), "transaction_id", successTransId);
        if (reason != null) {
            body.put("reason", reason);
        }
        requireOk(call("pay/reverse", HttpMethod.POST, "/merchant/pay/reverse", body), "pay/reverse");
    }

    // ---- Holds ----

    /**
     * {@code hold/multiple/create}: one item per split. TODO(ATMOS): the example sends
     * {@code amount} and {@code duration} as strings, so do we; whether capture and cancel
     * take the {@code parent_id} or each item's {@code hold_id} is undocumented — the parent
     * id is used throughout.
     */
    @Override
    public HoldOutcome hold(HoldRequest request) {
        ObjectNode body = json.createObjectNode()
                .put("card_token", request.cardToken())
                .put("duration", String.valueOf(request.durationMinutes()));
        ArrayNode items = body.putArray("items");
        for (Split split : payable(request.splits(), "hold " + request.account())) {
            recipient(items.addObject(), split)
                    .put("account", request.account())
                    .put("payment_details", split.platform() ? "MiniPolya service" : "Pitch rental")
                    .put("amount", String.valueOf(split.amount()));
        }
        JsonNode answer = call("hold/multiple/create", HttpMethod.POST, "/hold/multiple/create", body);
        requireOk(answer, "hold/multiple/create");
        OffsetDateTime till = OffsetDateTime.now(clock).plusMinutes(request.durationMinutes());
        return new HoldOutcome(answer.path("parent_id").asString(), ProviderStatus.PENDING, till, null, null);
    }

    @Override
    public HoldOutcome confirmHold(String holdId, String otp) {
        ObjectNode body = json.createObjectNode().put("otp", otp == null ? TOKEN_OTP : otp);
        JsonNode answer = call("hold/multiple/apply", HttpMethod.PUT, "/hold/multiple/apply/" + holdId, body);
        String code = codeOf(answer);
        if (DECLINES.contains(code)) {
            log.info("ATMOS hold declined: holdId={} code={}", holdId, code);
            return new HoldOutcome(holdId, ProviderStatus.FAILED, null, null, code);
        }
        requireOk(answer, "hold/multiple/apply");
        OffsetDateTime till = null;
        for (JsonNode item : answer.path("items")) {
            till = parseTime(item.path("hold_till"));
        }
        return new HoldOutcome(holdId, ProviderStatus.HELD, till, null, null);
    }

    /** Always the full held amount: no {@code amount} is sent. */
    @Override
    public HoldOutcome captureHold(String holdId) {
        JsonNode answer = call("hold capture", HttpMethod.POST, "/hold/" + holdId, json.createObjectNode());
        requireOk(answer, "hold capture");
        return new HoldOutcome(holdId, ProviderStatus.CAPTURED, null, commissionOf(transactionsOf(answer)), null);
    }

    /** {@code DELETE /hold/{id}} with {@code send_cancel_sms=true}. */
    @Override
    public HoldOutcome releaseHold(String holdId) {
        ObjectNode body = json.createObjectNode().put("send_cancel_sms", true);
        JsonNode answer = call("hold release", HttpMethod.DELETE, "/hold/" + holdId, body);
        requireOk(answer, "hold release");
        return new HoldOutcome(holdId, ProviderStatus.RELEASED, null, null, null);
    }

    /** TODO(ATMOS): hold statuses are not listed; an OK answer with confirmed=true is read as still held. */
    @Override
    public HoldOutcome getHold(String holdId) {
        JsonNode answer = call("hold get", HttpMethod.GET, "/hold/" + holdId, null);
        String code = codeOf(answer);
        if (HOLD_CANCELLED.equals(code) || HOLD_EXPIRED.equals(code)) {
            return new HoldOutcome(holdId, ProviderStatus.RELEASED, null, null, code);
        }
        requireOk(answer, "hold get");
        boolean confirmed = answer.path("store_transaction").path("confirmed").asBoolean(false);
        return new HoldOutcome(holdId, confirmed ? ProviderStatus.HELD : ProviderStatus.PENDING, null, null, null);
    }

    // ---- HTTP ----

    /**
     * One authorised call; a 401 refreshes the token once and tries again.
     *
     * <p>The retry is made after the catch block rather than inside it, so that its own
     * timeout is mapped like any other: an exception thrown inside a {@code catch} is not
     * caught by its own {@code try}, and a timeout escaping here as a transport exception
     * would be read as "it failed" instead of "we do not know", which is the one mistake
     * that must never be made about a payment.
     */
    private JsonNode call(String operation, HttpMethod method, String path, JsonNode body) {
        try {
            return send(method, path, body, false);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 401) {
                throw httpError(operation, ex.getStatusCode());
            }
            log.info("ATMOS answered 401 to {}; refreshing the token once", operation);
        } catch (ResourceAccessException ex) {
            throw timedOut(operation, ex);
        } catch (JacksonException ex) {
            throw unreadable(operation, ex);
        }
        try {
            return send(method, path, body, true);
        } catch (RestClientResponseException again) {
            throw httpError(operation, again.getStatusCode());
        } catch (ResourceAccessException again) {
            throw timedOut(operation, again);
        } catch (JacksonException again) {
            throw unreadable(operation, again);
        }
    }

    private ProviderTimeoutException timedOut(String operation, Throwable cause) {
        log.warn("ATMOS did not answer {} in time", operation);
        return new ProviderTimeoutException(operation, cause);
    }

    /**
     * An answer we cannot read is exactly as unknown as no answer at all — a gateway error
     * page in place of JSON says nothing about whether the money moved — so it is the same
     * exception: the payment stays processing and is settled by asking later, never retried
     * blindly. The body is never logged; it could hold card data.
     */
    private ProviderTimeoutException unreadable(String operation, Throwable cause) {
        log.warn("ATMOS answered {} with something that is not JSON", operation);
        return new ProviderTimeoutException(operation, cause, "sent an answer we could not read");
    }

    private JsonNode send(HttpMethod method, String path, JsonNode body, boolean freshToken) {
        String token = token(freshToken);
        RestClient.RequestBodySpec spec = http.method(method).uri(path)
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            spec.body(json.writeValueAsString(body));
        }
        String raw = spec.retrieve().body(String.class);
        return raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
    }

    /** The cached token, or a new one when it is due (or {@code force}). */
    synchronized String token(boolean force) {
        Instant now = clock.instant();
        if (!force && accessToken != null && now.isBefore(accessTokenExpiresAt)) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        String basic = Base64.getEncoder().encodeToString(
                (config.getConsumerKey() + ":" + config.getConsumerSecret()).getBytes(StandardCharsets.UTF_8));
        String raw;
        try {
            // grant_type is in the query as well as the form: the docs' own call has both.
            raw = http.post().uri("/token?grant_type=client_credentials")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(String.class);
        } catch (RestClientResponseException ex) {
            throw httpError("token", ex.getStatusCode());
        } catch (ResourceAccessException ex) {
            throw new ProviderTimeoutException("token", ex);
        }
        JsonNode answer;
        try {
            answer = json.readTree(raw);
        } catch (JacksonException ex) {
            // Nothing has been sent to ATMOS yet, so this is a plain refusal, not an unknown.
            throw new PaymentProviderException("TOKEN", "ATMOS answered the token call with something unreadable");
        }
        String fresh = answer.path("access_token").asString(null);
        if (fresh == null) {
            throw new PaymentProviderException("TOKEN", "ATMOS gave no access token");
        }
        long expiresIn = answer.path("expires_in").asLong(3600);
        accessToken = fresh;
        accessTokenExpiresAt = now.plusSeconds(Math.max(0, expiresIn - TOKEN_MARGIN_SECONDS));
        log.info("ATMOS access token obtained, valid {} s", expiresIn);
        return accessToken;
    }

    // ---- Answers ----

    private void requireOk(JsonNode answer, String operation) {
        String code = codeOf(answer);
        if (OK.equals(code)) {
            return;
        }
        log.warn("ATMOS refused {}: code={}", operation, code);
        throw switch (code) {
            case TRANSACTION_CLOSED, ALREADY_REVERSED -> new TransactionClosedException(code);
            case HOLD_CANCELLED -> new HoldCancelledException(code);
            case HOLD_EXPIRED -> new HoldExpiredException(code);
            default -> new PaymentProviderException(code, "ATMOS refused " + operation);
        };
    }

    private static String codeOf(JsonNode answer) {
        return answer.path("result").path("code").asString("MISSING_RESULT");
    }

    /**
     * A charge's transactions, read from whichever shape the answer has:
     * {@code store_transaction} is an <b>object</b> in a single payment and an <b>array</b> in
     * a multi-transaction, and {@code bulk/pay/create} calls it {@code store_transactions}.
     * The receipt URL is top-level in a single apply and per transaction elsewhere.
     *
     * <p>Confirmed means all of them are: one split of a team game left unconfirmed is not a
     * payment taken.
     */
    private ChargeOutcome outcomeOf(JsonNode answer) {
        List<JsonNode> transactions = transactionsOf(answer);
        List<String> successIds = new ArrayList<>();
        boolean allConfirmed = !transactions.isEmpty();
        String ofdUrl = answer.hasNonNull("ofd_url") ? answer.path("ofd_url").asString() : null;
        for (JsonNode txn : transactions) {
            allConfirmed &= txn.path("confirmed").asBoolean(false);
            if (!txn.path("success_trans_id").isNull() && !txn.path("success_trans_id").isMissingNode()) {
                successIds.add(txn.path("success_trans_id").asString());
            }
            if (ofdUrl == null && txn.hasNonNull("ofd_url")) {
                ofdUrl = txn.path("ofd_url").asString();
            }
        }
        if (!allConfirmed) {
            return ChargeOutcome.pending();
        }
        return new ChargeOutcome(ProviderStatus.SUCCESS, successIds, commissionOf(transactions), ofdUrl, null);
    }

    private static List<JsonNode> transactionsOf(JsonNode answer) {
        JsonNode node = answer.hasNonNull("store_transaction")
                ? answer.path("store_transaction")
                : answer.path("store_transactions");
        List<JsonNode> transactions = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(transactions::add);
        } else if (node.isObject()) {
            transactions.add(node);
        }
        return transactions;
    }

    /** {@code commission_value} comes as a string in the examples; summed over the transactions. */
    private static Long commissionOf(List<JsonNode> transactions) {
        long total = 0;
        boolean any = false;
        for (JsonNode txn : transactions) {
            String value = txn.path("commission_value").asString(null);
            if (value != null && !value.isBlank()) {
                total += Long.parseLong(value.trim());
                any = true;
            }
        }
        return any ? total : null;
    }

    private PaymentProviderException httpError(String operation, HttpStatusCode status) {
        log.warn("ATMOS answered HTTP {} to {}", status.value(), operation);
        return new PaymentProviderException("HTTP_" + status.value(), "ATMOS refused " + operation);
    }

    /**
     * Names the recipient of one split on a request.
     *
     * <p>With {@code store_id} (the default) the recipient <em>is</em> the store the item
     * names. With {@code terminal_id} it is the terminal, and {@code store_id} stays our own
     * merchant store, which is how the docs' single-payment example reads. Which of the two
     * ATMOS actually pays out on is {@link AtmosRecipientField}'s open question.
     */
    private ObjectNode recipient(ObjectNode node, Split split) {
        String id = recipientOf(split);
        if (recipientField == AtmosRecipientField.TERMINAL_ID) {
            withId(node, "store_id", config.getStoreId());
            // A string in every example, unlike store_id.
            return node.put("terminal_id", id);
        }
        return withId(node, "store_id", id);
    }

    private String recipientOf(Split split) {
        if (split.platform()) {
            if (platformRecipientMissing()) {
                throw new PaymentProviderException("RECIPIENT_MISSING",
                        "ATMOS_PLATFORM_RECIPIENT_ID is not set; the platform's split cannot be paid out");
            }
            return config.getPlatformRecipientId();
        }
        if (split.recipientId() == null || split.recipientId().isBlank()) {
            throw new PaymentProviderException("RECIPIENT_MISSING",
                    "The stadium owner has no ATMOS recipient yet; card payments cannot be split to them");
        }
        return split.recipientId();
    }

    /** What the line is called on the statement and on the receipt. */
    private static String detailsOf(Split split) {
        return split.platform() ? "MiniPolya service" : "Pitch rental";
    }

    /** One fiscal receipt line for a split; only sent when both IKPU codes are configured. */
    private ArrayNode ofdItems(Split split) {
        ArrayNode items = json.createArrayNode();
        items.addObject()
                .put("ofd_code", split.platform() ? config.getOfdCodeService() : config.getOfdCodeRental())
                .put("name", detailsOf(split))
                .put("amount", split.amount());
        return items;
    }

    private boolean receiptsOn() {
        return config.getOfdCodeRental() != null && !config.getOfdCodeRental().isBlank()
                && config.getOfdCodeService() != null && !config.getOfdCodeService().isBlank();
    }

    /** Puts an id as a number when it is one (the ATMOS examples send numbers), else as text. */
    private static ObjectNode withId(ObjectNode node, String field, String id) {
        if (id != null && id.matches("\\d{1,18}")) {
            return node.put(field, Long.parseLong(id));
        }
        return node.put(field, id);
    }

    /** Ids are numbers in the ATMOS examples; ours are strings. Sent as numbers when they are. */
    private JsonNode asNumber(String id) {
        if (id != null && id.matches("\\d{1,18}")) {
            return json.getNodeFactory().numberNode(Long.parseLong(id));
        }
        return json.getNodeFactory().stringNode(id);
    }

    private static String digitsOf(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    /** Ours is MM/YY; ATMOS wants YYMM. */
    static String toAtmosExpiry(String mmYy) {
        String d = digitsOf(mmYy);
        return d.length() == 4 ? d.substring(2) + d.substring(0, 2) : d;
    }

    /** ATMOS gives YYMM; ours is MM/YY. */
    static String fromAtmosExpiry(String yymm) {
        String d = digitsOf(yymm);
        return d.length() == 4 ? d.substring(2) + "/" + d.substring(0, 2) : null;
    }

    /** {@code 986009******1840} → {@code 9860 •••• •••• 1840}. */
    static String maskPan(String pan) {
        return CardMask.pan(pan);
    }

    private OffsetDateTime parseTime(JsonNode node) {
        if (node.isNumber()) {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(node.asLong()), ZoneOffset.UTC);
        }
        String text = node.asString(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (RuntimeException ex) {
            return null; // TODO(ATMOS): hold_till's format is not documented
        }
    }

}
