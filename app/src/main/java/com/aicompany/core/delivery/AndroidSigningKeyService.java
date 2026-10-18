package com.aicompany.core.delivery;

import com.aicompany.core.service.SecretCipher;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Clave de firma de Android por misión (decisión R1: una sola contraseña para el keystore y la clave, como Godot).
 * Se guarda cifrada con {@link SecretCipher}; {@link #status} nunca descifra y {@link #plain} solo lo usa la entrega.
 */
@Service
public class AndroidSigningKeyService {

    public record Status(boolean configured, String alias, String updatedAt) {
    }

    public record Plain(String keystoreBase64, String alias, String password) {
        @Override
        public String toString() {
            return "Plain[alias=" + alias + "]";
        }
    }

    private final AndroidSigningKeyMemoryService memory;
    private final SecretCipher cipher;

    public AndroidSigningKeyService(AndroidSigningKeyMemoryService memory, SecretCipher cipher) {
        this.memory = memory;
        this.cipher = cipher;
    }

    public Status status(String missionId) {
        return memory.find(missionId).map(s -> new Status(true, s.alias(), s.updatedAt()))
                .orElse(new Status(false, null, null));
    }

    public Status save(String missionId, String keystoreBase64, String alias, String password) {
        if (!cipher.available()) {
            throw new IllegalStateException("Falta FORJAI_SECRETS_KEY en .env: sin ella no se guardan claves.");
        }
        if (alias == null || alias.isBlank() || password == null || password.isEmpty() || keystoreBase64 == null) {
            throw new IllegalArgumentException("El keystore, el alias y la contraseña son obligatorios.");
        }
        if (hasControlChars(alias) || hasControlChars(password)) {
            throw new IllegalArgumentException("El alias y la contraseña no pueden tener saltos de línea ni caracteres nulos.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(keystoreBase64.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("El keystore no es base64 válido.");
        }
        if (!opens(bytes, alias, password)) {
            throw new IllegalArgumentException("El keystore no abre con esa contraseña o no tiene el alias " + alias
                    + " (Godot usa la misma contraseña para el keystore y la clave).");
        }
        var at = Instant.now().toString();
        memory.save(missionId, alias, cipher.encrypt(password), cipher.encrypt(Base64.getEncoder().encodeToString(bytes)), at);
        return new Status(true, alias, at);
    }

    public void delete(String missionId) {
        memory.delete(missionId);
    }

    public Optional<Plain> plain(String missionId) {
        return memory.find(missionId).map(s -> new Plain(cipher.decrypt(s.keystoreCipher()), s.alias(),
                cipher.decrypt(s.passwordCipher())));
    }

    private static boolean hasControlChars(String v) {
        return v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\0') >= 0;
    }

    private static boolean opens(byte[] bytes, String alias, String password) {
        for (var type : new String[]{"PKCS12", "JKS"}) {
            try {
                var ks = KeyStore.getInstance(type);
                ks.load(new ByteArrayInputStream(bytes), password.toCharArray());
                return ks.containsAlias(alias) && ks.getKey(alias, password.toCharArray()) != null;
            } catch (Exception ex) {
                // prueba el siguiente tipo
            }
        }
        return false;
    }
}
