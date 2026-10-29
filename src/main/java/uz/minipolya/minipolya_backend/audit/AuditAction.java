package uz.minipolya.minipolya_backend.audit;

/**
 * Every action the audit log records. The names are stable codes: they are stored in
 * {@code audit_log.action} and filtered on by the admin panel, so never rename one —
 * add a new code instead. {@code GET /api/admin/audit-log/actions} lists them.
 *
 * <p>Rows written before this enum existed may carry other codes (e.g.
 * {@code PLATFORM_SETTINGS_UPDATED}); the action filter still accepts them.
 */
public enum AuditAction {

    // ---- Admin ----
    ADMIN_LOGIN_SUCCEEDED,
    ADMIN_LOGIN_FAILED,
    APPLICATION_APPROVED,
    APPLICATION_REJECTED,
    STADIUM_APPROVED,
    STADIUM_REJECTED,
    STADIUM_BLOCKED,
    STADIUM_UNBLOCKED,
    STADIUM_STATUS_CHANGED,
    USER_BLOCKED,
    USER_UNBLOCKED,
    USER_ROLE_CHANGED,
    REFUND_RETRIED,
    REFUND_MARKED_MANUAL,
    OWNER_LEGAL_DETAILS_CHANGED,
    SETTINGS_CHANGED,

    // ---- Owner ----
    STADIUM_CREATED,
    STADIUM_UPDATED,
    PRICING_RULE_CREATED,
    PRICING_RULE_UPDATED,
    PRICING_RULE_DELETED,
    CLOSURE_CREATED,
    CLOSURE_DELETED,
    PROMOTION_CREATED,
    PROMOTION_DEACTIVATED,
    MANUAL_BOOKING_CREATED,
    CASH_RECEIVED,
    BOOKING_CANCELLED_BY_OWNER,

    // ---- Player ----
    BOOKING_CREATED,
    BOOKING_CANCELLED_BY_USER,
    BOOKING_REMAINDER_PAID,
    BOOKING_SERIES_CREATED,
    BOOKING_SERIES_CANCELLED,
    GAME_CREATED,
    GAME_JOINED,
    GAME_LEFT,
    GAME_CANCELLED,
    GAME_OPPONENT_LEFT,
    GAME_REMAINDER_PAID,

    // ---- System ----
    BOOKING_CONFIRMED,
    BOOKING_EXPIRED,
    BOOKING_COMPLETED,
    GAME_CONFIRMED,
    GAME_AUTO_CANCELLED,
    PAYMENT_COMPLETED,
    PAYMENT_RELEASED,
    REFUND_CREATED,
    REFUND_FAILED,
    PROMOTION_EXPIRED
}
