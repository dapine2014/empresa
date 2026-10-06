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
                    .list(r -> r.get("r").isNull() ? null : r.get("r").asString()).stream().findFirst().orElse(null);
            return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<CorrectionRound>>() { });
        }
    }

    public Optional<CorrectionRound> correctionRound(String missionId, int round) {
        return correctionRounds(missionId).stream().filter(r -> r.round() == round).findFirst();
    }
}
