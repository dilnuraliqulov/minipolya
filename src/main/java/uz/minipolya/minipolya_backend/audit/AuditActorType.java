package uz.minipolya.minipolya_backend.audit;

/**
 * The role an audit entry's actor acted in. It is the role of the action, not of the
 * user: an owner booking another stadium through the player flow is a PLAYER there.
 * SYSTEM entries — scheduled jobs and automatic consequences such as refunds — have
 * no user.
 */
public enum AuditActorType {
    ADMIN,
    OWNER,
    PLAYER,
    SYSTEM
}
