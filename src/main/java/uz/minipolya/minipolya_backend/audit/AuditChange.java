package uz.minipolya.minipolya_backend.audit;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Objects;

/**
 * One changed field: {@code { field, oldValue, newValue }}. {@code oldValue} is null for a
 * value that did not exist before (a creation), {@code newValue} for one that is gone.
 *
 * <p>Values are normalised when the change is made, so the stored JSON is the same
 * whatever type the caller had: numbers (money is always tiyin), booleans and strings
 * stay as they are; times, dates, UUIDs, enums and decimals become strings (instants in
 * UTC ISO-8601); collections become lists of normalised values.
 */
public record AuditChange(String field, Object oldValue, Object newValue) {

    /** Normalises on every construction, including when Jackson reads a stored entry back. */
    public AuditChange {
        oldValue = normalise(oldValue);
        newValue = normalise(newValue);
    }

    public static AuditChange of(String field, Object oldValue, Object newValue) {
        return new AuditChange(field, oldValue, newValue);
    }

    /** True when the normalised values differ, i.e. this is a real change. */
    @JsonIgnore
    public boolean isChange() {
        return !Objects.equals(oldValue, newValue);
    }

    static Object normalise(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof OffsetDateTime time) {
            return time.withOffsetSameInstant(ZoneOffset.UTC).toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Enum<?> e) {
            return enumValue(e);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(AuditChange::normalise).toList();
        }
        return value.toString();
    }

    /** The lowercase wire value the project's enums expose through {@code getValue()}, else the name. */
    private static String enumValue(Enum<?> e) {
        try {
            Object v = e.getClass().getMethod("getValue").invoke(e);
            if (v instanceof String s) {
                return s;
            }
        } catch (ReflectiveOperationException ignored) {
            // No getValue(): fall back to the constant's name.
        }
        return e.name();
    }

}
