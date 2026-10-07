package com.aicompany.core.recovery;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Spec misiones durables §3: rondas de corrección de cada misión, en {@code Mission.correctionRounds} (JSON). */
@Service
public class RecoveryMemoryService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Driver driver;

    public RecoveryMemoryService(Driver driver) {
        this.driver = driver;
    }

    public synchronized void addCorrectionRound(String missionId, CorrectionRound round) {
        var rounds = new ArrayList<>(correctionRounds(missionId));
        rounds.add(round);
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (m:Mission {id:$id}) SET m.correctionRounds = $json",
                    Map.of("id", missionId, "json", JSON.writeValueAsString(rounds))).consume());
        }
    }

    public List<CorrectionRound> correctionRounds(String missionId) {
        try (var session = driver.session()) {
            var json = session.run("MATCH (m:Mission {id:$id}) RETURN m.correctionRounds AS r", Map.of("id", missionId))
                    .list(r -> r.get("r").isNull() ? null : r.get("r").asString()).stream()
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<CorrectionRound>>() { });
        }
    }

    public Optional<CorrectionRound> correctionRound(String missionId, int round) {
        return correctionRounds(missionId).stream().filter(r -> r.round() == round).findFirst();
    }

    /** Spec misiones durables §3 (revisión 2026-10-06): reescribe el pedido de una ronda (p. ej. con la estrategia buscada). */
    public synchronized void updateCorrectionRequest(String missionId, int round, String request) {
        var rounds = new ArrayList<CorrectionRound>();
        for (var r : correctionRounds(missionId)) {
            rounds.add(r.round() == round
                    ? new CorrectionRound(r.round(), r.increment(), request, r.failing(), r.byFounder(), r.failures()) : r);
        }
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (m:Mission {id:$id}) SET m.correctionRounds = $json",
                    Map.of("id", missionId, "json", JSON.writeValueAsString(rounds))).consume());
        }
    }

    /**
     * Spec misiones durables, revisión 2026-10-06 §5: una verificación humana pone en cero el contador de correcciones
     * automáticas del incremento, como una indicación del fundador. La marca es la última ronda de corrección registrada:
     * cuentan solo las posteriores. En {@code Mission.correctionCounterResets} (JSON {incremento: ronda}).
     */
    public synchronized void resetCounter(String missionId, int increment) {
        var afterRound = correctionRounds(missionId).stream().mapToInt(CorrectionRound::round).max().orElse(0);
        var resets = new java.util.TreeMap<>(counterResets(missionId));
        resets.put(increment, afterRound);
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (m:Mission {id:$id}) SET m.correctionCounterResets = $json",
                    Map.of("id", missionId, "json", JSON.writeValueAsString(resets))).consume());
        }
    }

    /** Revisión 2026-10-06 §5: la ronda de la última marca de reinicio del incremento; 0 si nunca se reinició. */
    public int counterResetAfter(String missionId, int increment) {
        return counterResets(missionId).getOrDefault(increment, 0);
    }

    private Map<Integer, Integer> counterResets(String missionId) {
        try (var session = driver.session()) {
            var json = session.run("MATCH (m:Mission {id:$id}) RETURN m.correctionCounterResets AS r", Map.of("id", missionId))
                    .list(r -> r.get("r").isNull() ? null : r.get("r").asString()).stream()
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return json == null ? Map.of() : JSON.readValue(json, new TypeReference<Map<Integer, Integer>>() { });
        }
    }

    /** Spec misiones durables §3 (revisión 2026-10-06): estrategias buscadas por falla repetida, en {@code Mission.correctionStrategies}. */
    public synchronized void addStrategy(String missionId, CorrectionStrategy strategy) {
        var all = new ArrayList<>(strategies(missionId));
        all.add(strategy);
        try (var session = driver.session()) {
            session.executeWrite(tx -> tx.run("MATCH (m:Mission {id:$id}) SET m.correctionStrategies = $json",
                    Map.of("id", missionId, "json", JSON.writeValueAsString(all))).consume());
        }
    }

    public List<CorrectionStrategy> strategies(String missionId) {
        try (var session = driver.session()) {
            var json = session.run("MATCH (m:Mission {id:$id}) RETURN m.correctionStrategies AS r", Map.of("id", missionId))
                    .list(r -> r.get("r").isNull() ? null : r.get("r").asString()).stream()
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<CorrectionStrategy>>() { });
        }
    }
}
