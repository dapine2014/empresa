package com.aicompany.core.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keys de modelos editables desde Settings (2026-09-29, decisión del fundador): Neo4j es compartido con otros
 * proyectos, así que un secreto se guarda cifrado con AES-256-GCM y la llave maestra vive solo en .env
 * ({@code FORJAI_SECRETS_KEY}, 32 bytes en base64). Formato guardado: {@code v1:base64(iv || texto cifrado)}.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${forjai.secrets-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        var bytes = Base64.getDecoder().decode(base64Key.strip());
        if (bytes.length != 32) {
            throw new IllegalArgumentException("FORJAI_SECRETS_KEY debe tener 32 bytes en base64 (tiene " + bytes.length + ").");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public boolean available() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            var iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo cifrar el secreto.", ex);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Secreto guardado con un formato desconocido.");
        }
        try {
            var bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException ex) {
            // Nunca se incluye el contenido: solo que no se pudo leer (llave maestra distinta o dato alterado).
            throw new IllegalStateException("No se pudo descifrar el secreto guardado (¿cambió FORJAI_SECRETS_KEY?).");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("Falta FORJAI_SECRETS_KEY en .env: sin la llave maestra no se guardan keys.");
        }
    }
}
