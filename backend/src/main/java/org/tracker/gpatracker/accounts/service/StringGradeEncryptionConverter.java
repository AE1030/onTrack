package org.tracker.gpatracker.accounts.service;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.convert.PropertyValueConverter;
import org.springframework.data.convert.ValueConversionContext;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

@Converter
@Component
public class StringGradeEncryptionConverter
        implements AttributeConverter<String, String>,
                   PropertyValueConverter<String, String, ValueConversionContext<? extends org.springframework.data.mapping.PersistentProperty<?>>> {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;          // 96-bit nonce (recommended)
    private static final int TAG_LENGTH = 128;        // Authentication tag length (bits)

    private static final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey key;

    public StringGradeEncryptionConverter(@Value("${grade.encryption.key:}") String base64Key) {
        this.key = loadKey(base64Key); //should be coming from env variable during prod
    }

    // =========================
    // Spring Data PropertyValueConverter hooks (MongoDB)
    // =========================

    @Override
    public String read(String dbValue, ValueConversionContext<? extends org.springframework.data.mapping.PersistentProperty<?>> context) {
        return decryptToString(dbValue);
    }

    @Override
    public String write(String value, ValueConversionContext<? extends org.springframework.data.mapping.PersistentProperty<?>> context) {
        return encryptString(value);
    }

    // =========================
    // Public convenience methods
    // =========================

    public String encryptString(String value) {
        if (value == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(value.getBytes());

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv);
            buffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(buffer.array());

        } catch (Exception e) {
            throw new IllegalStateException("Grade encryption failed", e);
        }
    }

    public String decryptToString(String dbValue) {
        if (dbValue == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(dbValue);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext);

        } catch (Exception e) {
            throw new IllegalStateException("Grade decryption failed", e);
        }
    }

    // =========================
    // JPA AttributeConverter hooks (PostgreSQL)
    // =========================

    @Override
    public String convertToDatabaseColumn(String grade) {
        return encryptString(grade);
    }

    @Override
    public String convertToEntityAttribute(String dbValue) {
        return decryptToString(dbValue);
    }

    // =========================
    // Key loading
    // =========================

    private static SecretKey loadKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("grade.encryption.key not set");
        }
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        return new SecretKeySpec(decoded, "AES");
    }
}
