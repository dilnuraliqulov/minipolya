package uz.minipolya.minipolya_backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * Encrypts saved card tokens at rest: AES-256-GCM with a random 12-byte IV per value, the
 * key from {@code CARD_TOKEN_KEY} (32 bytes, base64). Stored as {@code v1:<base64(iv ||
 * ciphertext || tag)>}; a value without the prefix is a token written before encryption
 * and is read as it is (it is encrypted the next time the row is written).
 *
 * <p>Under the {@code prod} or {@code atmos} profile a missing or malformed key stops the
 * app at startup. Elsewhere a fixed development key is used, with a warning — fine for
 * the fake provider's tokens, never for real ones.
 */
@Slf4j
@Component
public class CardTokenCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Set<String> REAL_MONEY_PROFILES = Set.of("prod", "atmos");

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CardTokenCipher(AppProperties appProperties, Environment environment) {
        this.key = new SecretKeySpec(resolveKey(appProperties.getSecurity().getCardTokenKey(), environment), "AES");
    }

    public String encrypt(String token) {
        if (token == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not encrypt a card token", ex);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored; // written before encryption existed
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            // Never put the stored value in the message.
            throw new IllegalStateException("Could not decrypt a card token (wrong CARD_TOKEN_KEY?)");
        }
    }

    private static byte[] resolveKey(String configured, Environment environment) {
        boolean realMoney = Arrays.stream(environment.getActiveProfiles()).anyMatch(REAL_MONEY_PROFILES::contains);
        if (configured == null || configured.isBlank()) {
            if (realMoney) {
                throw new IllegalStateException("CARD_TOKEN_KEY is not set: saved card tokens cannot be encrypted");
            }
            log.warn("CARD_TOKEN_KEY is not set: using the fixed development key for card tokens");
            return sha256("minipolya-development-card-token-key");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("CARD_TOKEN_KEY must be base64 (openssl rand -base64 32)");
        }
        if (key.length != 32) {
            throw new IllegalStateException("CARD_TOKEN_KEY must decode to 32 bytes, not " + key.length);
        }
        return key;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
