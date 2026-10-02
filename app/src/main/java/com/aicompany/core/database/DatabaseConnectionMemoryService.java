package com.aicompany.core.database;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code (:DatabaseConnection)} (spec 2026-10-02 §1). Solo guarda la clave cifrada ({@code cipherText}); nunca la
 * devuelve en {@link DatabaseConnection}.
 */
@Service
public class DatabaseConnectionMemoryService {

    private static final String RETURN = "RETURN c.id AS id, c.name AS name, c.engine AS engine, c.host AS host, "
            + "c.port AS port, c.database AS database, c.username AS username, c.tls AS tls, c.caCertPem AS caCertPem, "
            + "c.environment AS environment, c.passwordHint AS passwordHint, c.lastCheckedAt AS lastCheckedAt, "
            + "c.lastCheckResult AS lastCheckResult";

    private final Driver driver;

    public DatabaseConnectionMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void save(DatabaseConnection c, String cipherText) {
        var props = new HashMap<String, Object>();
        props.put("name", c.name());
        props.put("engine", c.engine());
        props.put("host", c.host());
        props.put("port", c.port());
        props.put("database", c.database());
        props.put("username", c.username());
        props.put("tls", c.tls());
        props.put("caCertPem", c.caCertPem());
        props.put("environment", c.environment());
        props.put("passwordHint", c.passwordHint());
        props.put("lastCheckedAt", c.lastCheckedAt());
        props.put("lastCheckResult", c.lastCheckResult());
        props.put("cipherText", cipherText);
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MERGE (c:DatabaseConnection {id:$id}) "
                            + "ON CREATE SET c.createdAt=$now SET c += $props, c.updatedAt=$now",
                    Map.of("id", c.id(), "props", props, "now", java.time.Instant.now().toString())).consume());
        }
    }

    public List<DatabaseConnection> all() {
        try (var session = driver.session()) {
            return session.run("MATCH (c:DatabaseConnection) " + RETURN + " ORDER BY c.name")
                    .list(DatabaseConnectionMemoryService::toConnection);
        }
    }

    public Optional<DatabaseConnection> byName(String name) {
        try (var session = driver.session()) {
            return session.run("MATCH (c:DatabaseConnection {name:$name}) " + RETURN, Map.of("name", name))
                    .list(DatabaseConnectionMemoryService::toConnection).stream().findFirst();
        }
    }

    public Optional<DatabaseConnection> byId(String id) {
        try (var session = driver.session()) {
            return session.run("MATCH (c:DatabaseConnection {id:$id}) " + RETURN, Map.of("id", id))
                    .list(DatabaseConnectionMemoryService::toConnection).stream().findFirst();
        }
    }

    public Optional<String> cipherText(String id) {
        try (var session = driver.session()) {
            return session.run("MATCH (c:DatabaseConnection {id:$id}) RETURN c.cipherText AS cipherText", Map.of("id", id))
                    .list(r -> r.get("cipherText").isNull() ? null : r.get("cipherText").asString())
                    .stream().filter(java.util.Objects::nonNull).findFirst();
        }
    }

    public void delete(String id) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (c:DatabaseConnection {id:$id}) DETACH DELETE c", Map.of("id", id))
                    .consume());
        }
    }

    public void recordCheck(String id, String result) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (c:DatabaseConnection {id:$id}) "
                            + "SET c.lastCheckedAt=$at, c.lastCheckResult=$result",
                    Map.of("id", id, "at", java.time.Instant.now().toString(), "result", result)).consume());
        }
    }

    public void linkMission(String missionId, List<String> connectionIds) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (m:Mission {id:$missionId}) UNWIND $ids AS cid "
                            + "MATCH (c:DatabaseConnection {id:cid}) MERGE (m)-[:USES_DATABASE]->(c)",
                    Map.of("missionId", missionId, "ids", connectionIds)).consume());
        }
    }

    public List<DatabaseConnection> connectionsOf(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$missionId})-[:USES_DATABASE]->(c:DatabaseConnection) " + RETURN
                    + " ORDER BY c.name", Map.of("missionId", missionId)).list(DatabaseConnectionMemoryService::toConnection);
        }
    }

    /** En uso = la usa una misión que no terminó (ni COMPLETED, CANCELLED, FAILED ni AWAITING_INVESTOR). */
    public boolean usedByActiveMission(String connectionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission)-[:USES_DATABASE]->(:DatabaseConnection {id:$id}) "
                            + "WHERE NOT m.status IN ['COMPLETED','CANCELLED','FAILED','AWAITING_INVESTOR'] RETURN count(m) AS n",
                    Map.of("id", connectionId)).single().get("n").asLong() > 0;
        }
    }

    private static DatabaseConnection toConnection(Record r) {
        return new DatabaseConnection(r.get("id").asString(), r.get("name").asString(), r.get("engine").asString(),
                r.get("host").asString(), r.get("port").asInt(), r.get("database").asString(),
                r.get("username").asString(), r.get("tls").asString(), r.get("caCertPem").asString(null),
                r.get("environment").asString(), r.get("passwordHint").asString(""),
                r.get("lastCheckedAt").asString(null), r.get("lastCheckResult").asString(null));
    }
}
