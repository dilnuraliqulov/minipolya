package uz.minipolya.minipolya_backend.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.util.Locale;

/**
 * How seriously a team plays, chosen by its leader when the team is created and
 * changeable later only by them. It is shown next to the team everywhere — on the
 * challenge board, the accept screen, the game and the chat — so a side can see who
 * it is about to pay to play against. It never blocks a game: a team may always
 * challenge or accept any other, and is only asked to confirm a mismatch.
 *
 * <p>A team created before levels existed has none ({@code null}), which reads as
 * "not set"; the leader is nudged to pick one, and nothing else changes for them.
 *
 * <p>The stored values are the Uzbek words the product uses, lowercase on the wire
 * like every other enum here; the clients translate them for display.
 */
public enum TeamLevel {

    /** "Doʻstlar bilan dam olish uchun oʻynaymiz". */
    HAVASKOR,
    /** "Muntazam oʻynaymiz, yaxshi tayyorgarlik". */
    ORTA,
    /** "Turnir darajasi, kuchli jamoa". */
    PROFESSIONAL;

    @JsonValue
    public String getValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Null stays null; anything that is not one of the three is a 400, never a 500. */
    public static TeamLevel fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown team level: " + value.trim());
        }
    }

    @Converter
    public static class TeamLevelConverter implements AttributeConverter<TeamLevel, String> {
        @Override
        public String convertToDatabaseColumn(TeamLevel attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public TeamLevel convertToEntityAttribute(String dbData) {
            return dbData == null ? null : fromValue(dbData);
        }
    }
}
