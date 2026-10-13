package uz.minipolya.minipolya_backend.util;

/**
 * User fields extracted from a verified Telegram {@code initData} payload.
 * Only {@code telegramId} is guaranteed; the rest may be null.
 */
public record TelegramUserData(
        Long telegramId,
        String firstName,
        String lastName,
        String username,
        String languageCode
) {

    /** Display name composed from first + last name, falling back to the username. */
    public String displayName() {
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.isBlank()) {
            sb.append(firstName.trim());
        }
        if (lastName != null && !lastName.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(lastName.trim());
        }
        if (sb.isEmpty() && username != null && !username.isBlank()) {
            sb.append(username.trim());
        }
        return sb.isEmpty() ? "Player" : sb.toString();
    }
}
