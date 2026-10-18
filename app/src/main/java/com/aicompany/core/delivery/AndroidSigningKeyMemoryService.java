package com.aicompany.core.delivery;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/** {@code (:Mission)-[:HAS_SIGNING_KEY]->(:AndroidSigningKey)}: solo guarda valores ya cifrados. */
@Service
public class AndroidSigningKeyMemoryService {

    public record Stored(String alias, String passwordCipher, String keystoreCipher, String updatedAt) {
        @Override
        public String toString() {
            return "Stored[alias=" + alias + "]";
        }
    }

    private final Driver driver;

    public AndroidSigningKeyMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void save(String missionId, String alias, String passwordCipher, String keystoreCipher, String updatedAt) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (m:Mission {id:$id}) MERGE (m)-[:HAS_SIGNING_KEY]->(k:AndroidSigningKey) "
                            + "SET k.alias=$alias, k.passwordCipher=$p, k.keystoreCipher=$ks, k.updatedAt=$at",
                    Map.of("id", missionId, "alias", alias, "p", passwordCipher, "ks", keystoreCipher, "at", updatedAt)).consume());
        }
    }

    public Optional<Stored> find(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_SIGNING_KEY]->(k:AndroidSigningKey) "
                                    + "RETURN k.alias AS alias, k.passwordCipher AS p, k.keystoreCipher AS ks, k.updatedAt AS at",
                            Map.of("id", missionId))
                    .list(r -> new Stored(r.get("alias").asString(), r.get("p").asString(), r.get("ks").asString(),
                            r.get("at").asString("")))
                    .stream().findFirst();
        }
    }

    public void delete(String missionId) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (:Mission {id:$id})-[:HAS_SIGNING_KEY]->(k:AndroidSigningKey) DETACH DELETE k",
                    Map.of("id", missionId)).consume());
        }
    }
}
