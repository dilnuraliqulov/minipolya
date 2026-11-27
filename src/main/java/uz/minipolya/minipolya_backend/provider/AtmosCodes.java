package uz.minipolya.minipolya_backend.provider;

import java.util.Set;

/**
 * The ATMOS result codes we act on, shared by {@code AtmosPaymentProvider}, the fake that
 * imitates it and the services that turn a code into an answer for the player. Texts are
 * from the ATMOS docs ("Системные статусы и ошибки").
 *
 * <p>Codes in the second group are <b>not documented</b> by ATMOS (an expired SMS code, a
 * resend too soon, a binding resend at all): they are our own names until ATMOS tells us
 * theirs, listed under "Open ATMOS questions" in CLAUDE.md.
 */
public final class AtmosCodes {

    private AtmosCodes() {
    }

    public static final String OK = "OK";

    /** "Карта не найдена". */
    public static final String CARD_NOT_FOUND = "STPIMS-ERR-004";
    /** "Неверная сумма для платежа" — e.g. under the 500 so'm minimum. */
    public static final String WRONG_AMOUNT = "STPIMS-ERR-039";
    /** "Недостаточно средств". */
    public static final String INSUFFICIENT_FUNDS = "STPIMS-ERR-057";
    /** "Карта заблокирована" — the fake's generic decline. */
    public static final String CARD_BLOCKED = "STPIMS-ERR-058";
    /** "Срок действия вашей карты истек". */
    public static final String CARD_EXPIRED = "STPIMS-ERR-067";
    /** "Карта с данным номером не найдена. Неверно указана дата истечения срока вашей карты." */
    public static final String WRONG_EXPIRY = "STPIMS-ERR-090";
    /** "Транзакция закрыта". */
    public static final String TRANSACTION_CLOSED = "STPIMS-ERR-092";
    /** "OTP код указан неверно". */
    public static final String WRONG_OTP = "STPIMS-ERR-098";
    /** "Запрашиваемая транзакция ранее была отменена" — a second reverse. */
    public static final String ALREADY_REVERSED = "STPIMS-ERR-100";
    /** "Недостаточно средств на балансе карты для проведения платежа". */
    public static final String INSUFFICIENT_BALANCE = "STPIMS-ERR-112";
    /** "Платеж закрыт" — the fake's answer to a reverse after the refund window (an assumption). */
    public static final String PAYMENT_CLOSED = "STPIMS-ERR-113";
    /** "Неправильный номер карты" — anything but Uzcard / Humo. */
    public static final String WRONG_CARD_NUMBER = "STPIMS-ERR-117";
    /** "Холдирование не подтверждено" — created and never applied. */
    public static final String HOLD_NOT_CONFIRMED = "STPIMS-ERR-142";
    /** "Холдирование не отменено" — a release that did not happen. */
    public static final String HOLD_NOT_REMOVED = "STPIMS-ERR-143";
    /** "Время холдирования истекло". */
    public static final String HOLD_EXPIRED = "STPIMS-ERR-144";
    /** "холдирование отменено" (in the cancel example, not in the table). */
    public static final String HOLD_CANCELLED = "STPIMS-ERR-145";
    /** "Невозможно отменить транзакцию, попробуйте позже" (MPS table). */
    public static final String NOT_CANCELLED = "-15";
    /** HTTP 503 "Сервис временно недоступен", as AtmosPaymentProvider reports any HTTP error. */
    public static final String SERVICE_UNAVAILABLE = "HTTP_503";

    // ---- Not documented by ATMOS: our own names ----

    /** The SMS code is no longer valid. */
    public static final String OTP_EXPIRED = "OTP_EXPIRED";
    /** A new SMS code was asked for before the cooldown ended. */
    public static final String OTP_RESEND_TOO_EARLY = "OTP_RESEND_TOO_EARLY";
    /** No such binding, or it was already confirmed. */
    public static final String BINDING_NOT_FOUND = "BINDING_NOT_FOUND";
    /** ATMOS documents no way to resend a binding's SMS code. */
    public static final String RESEND_UNSUPPORTED = "BIND_RESEND_UNSUPPORTED";
    /**
     * The platform's split has nowhere to go: {@code ATMOS_PLATFORM_RECIPIENT_ID} is not set.
     * Ours, never the card's — refused before anything is sent to ATMOS.
     */
    public static final String RECIPIENT_MISSING = "RECIPIENT_MISSING";
    /**
     * The stadium owner cannot be paid yet: no signed contract with an ATMOS recipient id
     * ({@code OwnerLegalDetails.canReceivePayouts}). Ours, never the card's.
     */
    public static final String OWNER_NOT_CONNECTED = "OWNER_RECIPIENT_MISSING";

    /** The payment service itself could not be reached, or would not answer. */
    private static final Set<String> UNREACHABLE = Set.of(
            "HTTP_500", "HTTP_502", SERVICE_UNAVAILABLE, "HTTP_504",
            "STPIMS-ERR-024", // Нет соединения с банком
            "STPIMS-ERR-026", // Попробуйте позже
            "STPIMS-ERR-041", // Проблема соединения
            "STPIMS-ERR-093", // Ошибка в биллинговой системе провайдера
            "STPIMS-ERR-094", // Нет связи с ЕОПЦ
            "STPIMS-ERR-102", // СМС не отправилось
            "STPIMS-ERR-120"  // Платеж временно недоступен
    );

    /**
     * Our own integration, our own credentials or our own store ids are wrong — never the
     * player's card. Retrying will not help until somebody fixes the configuration, but the
     * player must still be told the <b>service</b> failed and not that their card was
     * refused: a 403 shown as "card declined" sends people off to try another card for a
     * problem no card of theirs can fix, and hides the outage from us behind what look like
     * ordinary declines.
     *
     * <p>HTTP 401 and 403 are here because both are the gateway refusing the call before
     * ATMOS sees it — an expired or wrong consumer key, an IP that is not allowed, or an
     * endpoint this merchant account may not call, which is what
     * {@code POST /hold/multiple/create} answers today
     * (see {@code docs/claude/atmos-endpoint-audit.md}).
     */
    private static final Set<String> MISCONFIGURED = Set.of(
            "HTTP_401", "HTTP_403",
            "STPIMS-ERR-001", // Внутренняя ошибка
            "STPIMS-ERR-003", // Неверный партнер
            "STPIMS-ERR-006", // Обязательные параметры не найдены
            "STPIMS-ERR-007", // Неверный формат параметра
            "STPIMS-ERR-009", // Неверные параметры
            "STPIMS-ERR-017", // Неактивный партнер
            "STPIMS-ERR-037", // Store ID не найден
            "STPIMS-ERR-044", // Магазин с данным ID не найден
            "STPIMS-ERR-056", // Проблема авторизации
            "STPIMS-ERR-103", // Терминал не найден
            RECIPIENT_MISSING,
            OWNER_NOT_CONNECTED
    );

    /**
     * True when the refusal was the payment service's, not the card's: nothing happened to
     * anybody's money, the player sees "the payment service failed, try again later" rather
     * than a decline, and {@link uz.minipolya.minipolya_backend.provider.DeclineReason}
     * gives the row no reason at all.
     *
     * <p>Deliberately <b>not</b> here: {@code STPIMS-ERR-118} ("Статус платежа
     * неизвестен"). An unknown status is not "nothing happened" — the money may
     * have moved — and this method is also what decides a payment may be rolled back and
     * retried, which is the one thing that must never be assumed about a payment.
     */
    public static boolean isUnavailable(String code) {
        return code != null && (UNREACHABLE.contains(code) || MISCONFIGURED.contains(code));
    }

    /** True for the subset that is our own integration rather than a passing outage. */
    public static boolean isMisconfigured(String code) {
        return code != null && MISCONFIGURED.contains(code);
    }
}
