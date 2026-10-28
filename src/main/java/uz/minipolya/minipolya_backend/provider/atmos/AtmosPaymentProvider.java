package uz.minipolya.minipolya_backend.provider.atmos;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uz.minipolya.minipolya_backend.config.AppProperties;
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
    static final String TRANSACTION_CLOSED = "STPIMS-ERR-092";
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
    private final ObjectMapper json = JsonMapper.builder().build();

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    public AtmosPaymentProvider(AppProperties appProperties,
                                @Qualifier("atmosRestClientBuilder") RestClient.Builder builder,
                                Clock clock) {
        this.config = appProperties.getAtmos();
        this.http = builder.baseUrl(config.getBaseUrl() == null ? "" : config.getBaseUrl()).build();
        this.clock = clock;
    }

    /** Fails the startup, naming every missing variable, rather than the first payment. */
    @PostConstruct
    public void requireConfiguration() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("ATMOS_BASE_URL", config.getBaseUrl());
        required.put("ATMOS_CONSUMER_KEY", config.getConsumerKey());
        required.put("ATMOS_CONSUMER_SECRET", config.getConsumerSecret());
        required.put("ATMOS_STORE_ID", config.getStoreId());
        required.put("ATMOS_PLATFORM_RECIPIENT_ID", config.getPlatformRecipientId());
        List<String> missing = required.entrySet().stream()
                .filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(Map.Entry::getKey)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The atmos profile is on but these environment variables are not set: "
                    + String.join(", ", missing));
        }
        log.info("AtmosPaymentProvider is active: storeId={} receipts={}", config.getStoreId(), receiptsOn());
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

    @Override
    public BoundCard bindCardConfirm(String bindingId, String otp) {
        ObjectNode body = withId(json.createObjectNode(), "transaction_id", bindingId).put("otp", otp);
        JsonNode answer = call("bind-card/confirm", HttpMethod.POST, "/partner/bind-card/confirm", body);
        requireOk(answer, "bind-card/confirm");
        // TODO(ATMOS): the table nests the card under "data"; the example lists it flat. Read both.
        JsonNode card = answer.has("data") ? answer.path("data") : answer;
        return new BoundCard(card.path("card_token").asString(), card.path("card_id").asString(),
                maskPan(card.path("pan").asString("")), fromAtmosExpiry(card.path("expiry").asString("")));
    }

    @Override
    public void removeCard(String cardId, String cardToken) {
        ObjectNode body = withId(json.createObjectNode(), "id", cardId).put("token", cardToken);
        requireOk(call("remove-card", HttpMethod.POST, "/partner/remove-card", body), "remove-card");
    }

    // ---- Charges ----

    /**
     * {@code bulk/pay/create} with one item per split, then {@code bulk/pay/pre-apply} with
     * the token (or the card number and expiry, after which ATMOS sends an SMS code).
     *
     * <p>TODO(ATMOS): the create table puts {@code store_id} in each params item but the
     * example has one at the top; recipients differ per split, so it is sent per item here.
     * The example sends {@code amount} as a number, so do we. Whether {@code ofd_items} may
     * be sent per item in a bulk payment is undocumented.
     */
    @Override
    public PendingCharge charge(ChargeRequest request) {
        ObjectNode create = withId(json.createObjectNode(), "store_id", config.getStoreId());
        ArrayNode params = create.putArray("params");
        for (Split split : request.splits()) {
            ObjectNode item = withId(params.addObject(), "store_id", recipientOf(split))
                    .put("account", request.account())
                    .put("amount", split.amount())
                    .put("details", split.platform() ? "MiniPolya service" : "Pitch rental");
            if (receiptsOn()) {
                item.putArray("ofd_items").addObject()
                        .put("ofd_code", split.platform() ? config.getOfdCodeService() : config.getOfdCodeRental())
                        .put("name", split.platform() ? "MiniPolya service" : "Pitch rental")
                        .put("amount", split.amount());
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
        if (request.cardToken() != null) {
            preApply.put("card_token", request.cardToken());
        } else {
            preApply.put("card_number", digitsOf(request.cardNumber())).put("expiry", toAtmosExpiry(request.expiry()));
        }
        JsonNode preApplied = call("bulk/pay/pre-apply", HttpMethod.POST, "/merchant/bulk/pay/pre-apply", preApply);
        String code = codeOf(preApplied);
        if (DECLINES.contains(code)) {
            // Declined before any money moved: the confirmation will fail the same way.
            log.info("ATMOS pre-apply declined: account={} code={}", request.account(), code);
        } else {
            requireOk(preApplied, "bulk/pay/pre-apply");
        }
        return new PendingCharge(ids, request.cardToken() == null);
    }

    @Override
    public ChargeOutcome confirmCharge(List<String> transactionIds, String otp) {
        ObjectNode body = withId(json.createObjectNode(), "store_id", config.getStoreId())
                .put("otp", otp == null ? TOKEN_OTP : otp);
        transactionIds.forEach(id -> body.withArray("transaction_id").add(asNumber(id)));
        JsonNode answer = call("bulk/pay/apply", HttpMethod.POST, "/merchant/bulk/pay/apply", body);
        String code = codeOf(answer);
        if (DECLINES.contains(code)) {
            log.info("ATMOS charge declined: transactions={} code={}", transactionIds, code);
            return ChargeOutcome.failed(code);
        }
        requireOk(answer, "bulk/pay/apply");
        return outcomeOf(answer.path("store_transaction"));
    }

    /** TODO(ATMOS): the status values of {@code pay/get} are not listed; confirmed=true is read as success. */
    @Override
    public ChargeOutcome getCharge(String transactionId) {
        ObjectNode body = withId(withId(json.createObjectNode(), "store_id", config.getStoreId()),
                "transaction_id", transactionId);
        JsonNode answer = call("pay/get", HttpMethod.POST, "/merchant/pay/get", body);
        String code = codeOf(answer);
        if (TRANSACTION_CLOSED.equals(code)) {
            return new ChargeOutcome(ProviderStatus.REVERSED, List.of(), null, null, code);
        }
        if (DECLINES.contains(code)) {
            return ChargeOutcome.failed(code);
        }
        requireOk(answer, "pay/get");
        JsonNode txn = answer.path("store_transaction");
        return outcomeOf(txn.isArray() ? txn : json.createArrayNode().add(txn));
    }

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
        for (Split split : request.splits()) {
            withId(items.addObject(), "store_id", recipientOf(split))
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
        return new HoldOutcome(holdId, ProviderStatus.CAPTURED, null, commissionOf(answer.path("store_transaction")), null);
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

    /** One authorised call; a 401 refreshes the token once and tries again. */
    private JsonNode call(String operation, HttpMethod method, String path, JsonNode body) {
        try {
            return send(method, path, body, false);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                log.info("ATMOS answered 401 to {}; refreshing the token once", operation);
                try {
                    return send(method, path, body, true);
                } catch (RestClientResponseException again) {
                    throw httpError(operation, again.getStatusCode());
                }
            }
            throw httpError(operation, ex.getStatusCode());
        } catch (ResourceAccessException ex) {
            log.warn("ATMOS did not answer {} in time", operation);
            throw new ProviderTimeoutException(operation, ex);
        }
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
            raw = http.post().uri("/token")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(String.class);
        } catch (RestClientResponseException ex) {
            throw httpError("token", ex.getStatusCode());
        } catch (ResourceAccessException ex) {
            throw new ProviderTimeoutException("token", ex);
        }
        JsonNode answer = json.readTree(raw);
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
            case TRANSACTION_CLOSED -> new TransactionClosedException(code);
            case HOLD_CANCELLED -> new HoldCancelledException(code);
            case HOLD_EXPIRED -> new HoldExpiredException(code);
            default -> new PaymentProviderException(code, "ATMOS refused " + operation);
        };
    }

    private static String codeOf(JsonNode answer) {
        return answer.path("result").path("code").asString("MISSING_RESULT");
    }

    /** All of a confirmed charge's transactions, one per split. */
    private ChargeOutcome outcomeOf(JsonNode transactions) {
        List<String> successIds = new ArrayList<>();
        boolean allConfirmed = transactions.size() > 0;
        String ofdUrl = null;
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

    /** {@code commission_value} comes as a string in the examples; summed over the transactions. */
    private static Long commissionOf(JsonNode transactions) {
        if (transactions.isMissingNode() || transactions.isNull()) {
            return null;
        }
        Iterable<JsonNode> each = transactions.isArray() ? transactions : List.of(transactions);
        long total = 0;
        boolean any = false;
        for (JsonNode txn : each) {
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

    private String recipientOf(Split split) {
        String id = split.platform() ? config.getPlatformRecipientId() : split.recipientId();
        if (id == null || id.isBlank()) {
            throw new PaymentProviderException("RECIPIENT_MISSING",
                    "The stadium owner has no ATMOS recipient yet; card payments cannot be split to them");
        }
        return id;
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

    /** {@code 986009******1840} → {@code 9860 •••• 1840}. */
    static String maskPan(String pan) {
        String clean = pan == null ? "" : pan.replaceAll("[^0-9*]", "");
        if (clean.length() < 8) {
            return "•••• " + (clean.length() >= 4 ? clean.substring(clean.length() - 4) : "");
        }
        return clean.substring(0, 4) + " •••• " + clean.substring(clean.length() - 4);
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
