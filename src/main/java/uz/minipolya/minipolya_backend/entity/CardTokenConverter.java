package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.config.CardTokenCipher;

/**
 * {@code saved_cards.provider_token} is encrypted in the database and plain only in
 * memory, while a payment is being made. Hibernate gets this converter from Spring, so
 * the key never leaves {@link CardTokenCipher}.
 */
@Component
@Converter
public class CardTokenConverter implements AttributeConverter<String, String> {

    private final CardTokenCipher cipher;

    public CardTokenConverter(CardTokenCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String token) {
        return cipher.encrypt(token);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return cipher.decrypt(stored);
    }
}
