package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

import java.util.Locale;

/**
 * The text matching every admin list search shares. Names match case-insensitively;
 * phones ignore spaces and the "+" on both sides, so "+998 90 123" finds "998901234567".
 * The search text is matched literally: {@code %} and {@code _} are not wildcards.
 */
public final class SearchPredicates {

    private SearchPredicates() {
    }

    /** {@code expression} contains {@code text}, ignoring case. */
    public static Predicate containsIgnoreCase(CriteriaBuilder cb, Expression<String> expression, String text) {
        return cb.like(cb.lower(expression), "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%", '\\');
    }

    /**
     * {@code expression} (a phone) contains {@code text} with spaces and "+" removed from
     * both. Never matches when the text has nothing left after that, so a search of "+"
     * alone does not match every phone.
     */
    public static Predicate phoneContains(CriteriaBuilder cb, Expression<String> expression, String text) {
        String digits = normalizePhone(text);
        if (digits.isEmpty()) {
            return cb.disjunction();
        }
        Expression<String> stored = cb.function("replace", String.class,
                cb.function("replace", String.class, expression, cb.literal(" "), cb.literal("")),
                cb.literal("+"), cb.literal(""));
        return cb.like(stored, "%" + escapeLike(digits) + "%", '\\');
    }

    static String normalizePhone(String text) {
        return text == null ? "" : text.replace(" ", "").replace("+", "").trim();
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
