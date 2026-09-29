package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Keys de modelos editables desde Settings (2026-09-29): cifradas con la llave maestra de .env, nunca en claro. */
class SecretCipherTest {

    private static final String MASTER = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsAndDecryptsWithoutLeavingThePlainText() {
        var cipher = new SecretCipher(MASTER);

        var stored = cipher.encrypt("nvapi-secreta-123");

        assertFalse(stored.contains("nvapi-secreta-123"));
        assertEquals("nvapi-secreta-123", cipher.decrypt(stored));
    }

    @Test
    void eachEncryptionUsesAFreshIv() {
        var cipher = new SecretCipher(MASTER);

        assertNotEquals(cipher.encrypt("misma"), cipher.encrypt("misma"));
    }

    @Test
    void anotherMasterKeyCannotRead() {
        var stored = new SecretCipher(MASTER).encrypt("nvapi-x");
        var other = new byte[32];
        other[0] = 1;

        assertThrows(IllegalStateException.class,
                () -> new SecretCipher(Base64.getEncoder().encodeToString(other)).decrypt(stored));
    }

    @Test
    void withoutAMasterKeyItIsUnavailableAndRefusesToEncrypt() {
        var cipher = new SecretCipher("");

        assertFalse(cipher.available());
        var ex = assertThrows(IllegalStateException.class, () -> cipher.encrypt("nvapi-x"));
        assertTrue(ex.getMessage().contains("FORJAI_SECRETS_KEY"), ex.getMessage());
    }

    @Test
    void aMasterKeyOfTheWrongSizeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[10])));
    }
}
