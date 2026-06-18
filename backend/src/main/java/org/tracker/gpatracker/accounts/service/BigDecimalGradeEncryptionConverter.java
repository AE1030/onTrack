package org.tracker.gpatracker.accounts.service;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.convert.PropertyValueConverter;
import org.springframework.data.convert.ValueConversionContext;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

@Converter
@Component
public class BigDecimalGradeEncryptionConverter
        implements AttributeConverter<BigDecimal, String>,
                   PropertyValueConverter<BigDecimal, String, ValueConversionContext<? extends org.springframework.data.mapping.PersistentProperty<?>>> {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private static final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey key;

    // No-arg constructor used by Hibernate (JPA) and Spring Data MongoDB (@ValueConverter) via reflection
    public BigDecimalGradeEncryptionConverter() {
        this.key = loadKey(readKeyFromProperties());
    }

    @Autowired
    public BigDecimalGradeEncryptionConverter(@Value("${grade.encryption.key:}") String base64Key) {
        this.key = loadKey(base64Key);
    }

    private static String readKeyFromProperties() {
        try (InputStream is = BigDecimalGradeEncryptionConverter.class
                .getClassLoader().getResourceAsStream("application.properties")) {
            if (is == null) {
                throw new IllegalStateException("application.properties not found on classpath");
            }
            Properties props = new Properties();
            props.load(is);
            String value = props.getProperty("grade.encryption.key", "").trim();
            // Resolve Spring-style ${ENV_VAR} placeholders since this is read
            // outside of Spring's property resolution (no-arg constructor path)
            if (value.startsWith("${") && value.endsWith("}")) {
                String envVar = value.substring(2, value.length() - 1);
                String envValue = System.getenv(envVar);
                if (envValue == null) {
                    envValue = System.getProperty(envVar);
                }
                return envValue != null ? envValue.trim() : "";
            }
            return value;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read application.properties", e);
        }
    }

    // =========================
    // JPA AttributeConverter hooks (PostgreSQL)
    // =========================

    @Override
    public String convertToDatabaseColumn(BigDecimal value) {
        return encryptBigDecimal(value);
    }

    @Override
    public BigDecimal convertToEntityAttribute(String dbValue) {
        return decryptToBigDecimal(dbValue);
    }

    // =========================
    // Spring Data PropertyValueConverter hooks (MongoDB)
    // =========================

    @Override
    public BigDecimal read(String value, ValueConversionContext<? extends org.springframework.data.mapping.PersistentProperty<?>> context) {
        return decryptToBigDecimal(value);
    }

    @Override
    public String write(BigDecimal value, ValueConversionContext<? extends org.springframework.data.mapping.PersistentProperty<?>> context) {
        return encryptBigDecimal(value);
    }

    // =========================
    // Public convenience methods
    // =========================

    public String encryptBigDecimal(BigDecimal value) {
        if (value == null) return null;
        return encryptString(value.toPlainString());
    }

    public BigDecimal decryptToBigDecimal(String dbValue) {
        if (dbValue == null) return null;
        String plaintext = decryptToString(dbValue);
        return new BigDecimal(plaintext);
    }

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
            throw new IllegalStateException("Encryption failed", e);
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
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    private static SecretKey loadKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("grade.encryption.key not set");
        }
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        return new SecretKeySpec(decoded, "AES");
    }
}
