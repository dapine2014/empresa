package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cadena de modelos gratis (2026-10-15): {@code (:FreeModelChain {id:'FREE-CHAIN', models, updatedAt, updatedBy})}.
 * Sin nodo, manda la semilla de {@code forjai.free-model-chain}.
 */
@Service
public class FreeModelChainMemoryService {

    public record StoredChain(List<String> models, Instant updatedAt, String updatedBy) {
    }

    private final Driver driver;

    public FreeModelChainMemoryService(Driver driver) {
        this.driver = driver;
    }

    public Optional<StoredChain> load() {
        try (var session = driver.session()) {
            var records = session.run("MATCH (c:FreeModelChain {id:'FREE-CHAIN'}) RETURN c.models AS models, "
                    + "c.updatedAt AS updatedAt, c.updatedBy AS updatedBy").list();
            return records.stream().findFirst().map(r -> new StoredChain(r.get("models").asList(v -> v.asString()),
                    Instant.parse(r.get("updatedAt").asString()), r.get("updatedBy").asString("")));
        }
    }

    public void save(List<String> models, String actor) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MERGE (c:FreeModelChain {id:'FREE-CHAIN'}) SET c.models=$models, "
                            + "c.updatedAt=$at, c.updatedBy=$actor",
                    Map.of("models", models, "at", Instant.now().toString(), "actor", actor)).consume());
        }
    }
}
