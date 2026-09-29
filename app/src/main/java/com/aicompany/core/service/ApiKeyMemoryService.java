package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Keys de modelos editables desde Settings (2026-09-29): {@code (:ApiKey {provider, cipherText, updatedAt, updatedBy})}.
 * Solo guarda texto cifrado por {@link SecretCipher}; nunca ve ni guarda una key en claro.
 */
@Service
public class ApiKeyMemoryService {

    public record StoredApiKey(String provider, String cipherText, Instant updatedAt, String updatedBy) {
    }

    private final Driver driver;

    public ApiKeyMemoryService(Driver driver) {
        this.driver = driver;
    }

    public List<StoredApiKey> all() {
        try (var session = driver.session()) {
            return session.run("MATCH (k:ApiKey) RETURN k.provider AS provider, k.cipherText AS cipherText, "
                    + "k.updatedAt AS updatedAt, k.updatedBy AS updatedBy ORDER BY k.provider").list(r -> new StoredApiKey(
                    r.get("provider").asString(), r.get("cipherText").asString(),
                    Instant.parse(r.get("updatedAt").asString()), r.get("updatedBy").asString("")));
        }
    }

    public void save(String provider, String cipherText, String actor) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MERGE (k:ApiKey {provider:$provider}) SET k.cipherText=$cipherText, "
                            + "k.updatedAt=$at, k.updatedBy=$actor",
                    Map.of("provider", provider, "cipherText", cipherText, "at", Instant.now().toString(), "actor", actor))
                    .consume());
        }
    }
}
