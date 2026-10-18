package com.aicompany.core.delivery;

import com.aicompany.core.service.SecretCipher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AndroidSigningKeyServiceTest {

    @TempDir Path tmp;
    AndroidSigningKeyMemoryService memory;
    SecretCipher cipher;
    AndroidSigningKeyService service;

    @BeforeEach
    void setUp() {
        memory = mock(AndroidSigningKeyMemoryService.class);
        cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        service = new AndroidSigningKeyService(memory, cipher);
    }

    private String generateKeystore(String alias, String pass) throws Exception {
        var file = tmp.resolve("k.p12");
        Process p;
        try {
            p = new ProcessBuilder("keytool", "-genkeypair", "-keystore", file.toString(), "-storetype", "PKCS12",
                    "-alias", alias, "-storepass", pass, "-keypass", pass, "-dname", "CN=Forjai", "-keyalg", "RSA",
                    "-validity", "10000").redirectErrorStream(true).start();
        } catch (java.io.IOException ex) {
            Assumptions.assumeTrue(false, "sin keytool");
            return null;
        }
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        return Base64.getEncoder().encodeToString(Files.readAllBytes(file));
    }

    @Test
    void aValidKeystoreIsStoredEncryptedAndOnlyTheAliasComesBack() throws Exception {
        var b64 = generateKeystore("forjai", "clave123");
        var status = service.save("M-1", b64, "forjai", "clave123");
        assertTrue(status.configured());
        assertEquals("forjai", status.alias());
        verify(memory).save(eq("M-1"), eq("forjai"), argThat(c -> !c.contains("clave123")), argThat(c -> !c.equals(b64)), any());
    }

    @Test
    void plainRoundTripsAndHidesSecretsInToString() throws Exception {
        var b64 = generateKeystore("forjai", "clave123");
        var captured = new String[2];
        org.mockito.Mockito.doAnswer(i -> {
            captured[0] = i.getArgument(2);
            captured[1] = i.getArgument(3);
            return null;
        }).when(memory).save(any(), any(), any(), any(), any());
        service.save("M-1", b64, "forjai", "clave123");
        when(memory.find("M-1")).thenReturn(Optional.of(new AndroidSigningKeyMemoryService.Stored("forjai", captured[0], captured[1], "t")));
        var plain = service.plain("M-1").orElseThrow();
        assertEquals("clave123", plain.password());
        assertEquals(b64, plain.keystoreBase64());
        assertFalse(plain.toString().contains("clave123"));
        assertTrue(service.status("M-1").configured());
    }

    @Test
    void aWrongPasswordOrAliasIsRejected() throws Exception {
        var b64 = generateKeystore("forjai", "clave123");
        assertThrows(IllegalArgumentException.class, () -> service.save("M-1", b64, "forjai", "otra"));
        assertThrows(IllegalArgumentException.class, () -> service.save("M-1", b64, "otro", "clave123"));
        verify(memory, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void controlCharactersAndBadBase64AreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.save("M-1", "QUJD", "a\nb", "x"));
        assertThrows(IllegalArgumentException.class, () -> service.save("M-1", "QUJD", "a", "x\0"));
        assertThrows(IllegalArgumentException.class, () -> service.save("M-1", "%%%", "a", "x"));
    }

    @Test
    void withoutTheSecretsKeyNothingIsSaved() {
        var noKey = new AndroidSigningKeyService(memory, new SecretCipher(""));
        var ex = assertThrows(IllegalStateException.class, () -> noKey.save("M-1", "QUJD", "a", "x"));
        assertTrue(ex.getMessage().contains("FORJAI_SECRETS_KEY"));
        verify(memory, never()).save(any(), any(), any(), any(), any());
    }
}
