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

    /** "Try again later": nothing happened at the provider, the caller may simply retry. */
    private static final Set<String> UNAVAILABLE = Set.of(
            "HTTP_500", "HTTP_502", SERVICE_UNAVAILABLE, "HTTP_504",
            "STPIMS-ERR-024", // Нет соединения с банком
            "STPIMS-ERR-026", // Попробуйте позже
            "STPIMS-ERR-041", // Проблема соединения
            "STPIMS-ERR-094", // Нет связи с ЕОПЦ
            "STPIMS-ERR-102"  // СМС не отправилось
    );

    /** True when the code means the provider (or the bank behind it) could not be reached. */
    public static boolean isUnavailable(String code) {
        return code != null && UNAVAILABLE.contains(code);
    }
}
