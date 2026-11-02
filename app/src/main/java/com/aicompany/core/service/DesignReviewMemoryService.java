package com.aicompany.core.service;

import com.aicompany.core.agent.validation.DesignComparison;
import com.aicompany.core.model.DesignScreenReview;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Spec 2026-11-01 front con diseño visual §14: el estado de la revisión de diseño por pantalla e incremento
 * ({@code (:Mission)-[:HAS_DESIGN_REVIEW]->(:DesignScreenReview)}), para que un reinicio conserve rondas y decisiones.
 * Un nodo por (misión, incremento, pantalla). Sin constraint (regla R12, como las versiones de diseño).
 */
@Service
public class DesignReviewMemoryService {

    private static final String RETURN = " RETURN r.increment AS increment, r.screen AS screen, r.size AS size, "
            + "r.ssim AS ssim, r.status AS status, r.rounds AS rounds, r.extraRounds AS extraRounds, "
            + "r.decidedBy AS decidedBy, r.decisionNote AS decisionNote, toString(r.updatedAt) AS updatedAt "
            + "ORDER BY r.increment, r.screen";

    private final Driver driver;

    public DesignReviewMemoryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Guarda el resultado de una pasada (el del peor tamaño de la pantalla) con las rondas gastadas y el estado. No toca
     * {@code extraRounds} ni la decisión ({@code decidedBy}, {@code decisionNote}): son del punto de decisión.
     */
    public void save(String missionId, DesignComparison.ScreenResult result, int increment, int rounds, String status) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (m:Mission {id:$id}) "
                                + "MERGE (m)-[:HAS_DESIGN_REVIEW]->(r:DesignScreenReview {increment:$inc, screen:$screen}) "
                                + "ON CREATE SET r.extraRounds = 0 "
                                + "SET r.size = $size, r.ssim = $ssim, r.status = $status, r.rounds = $rounds, "
                                + "r.missing = $missing, r.detail = $detail, r.updatedAt = datetime()",
                        Map.of("id", missionId, "inc", increment, "screen", result.screen(),
                                "size", result.size() == null ? "" : result.size(), "ssim", result.ssim(),
                                "status", status, "rounds", rounds,
                                "missing", result.missing() == null ? List.of() : result.missing(),
                                "detail", result.passed() ? "" : DesignComparison.correction(List.of(result))));
                return null;
            });
        }
    }

    /** Las pantallas del incremento que todavía no pasaron ni fueron aceptadas (PENDING, AWAITING_DECISION u otro). */
    public List<DesignScreenReview> pending(String missionId, int increment) {
        return query("MATCH (:Mission {id:$id})-[:HAS_DESIGN_REVIEW]->(r:DesignScreenReview {increment:$inc}) "
                        + "WHERE NOT r.status IN ['PASSED', 'ACCEPTED']" + RETURN,
                Map.of("id", missionId, "inc", increment));
    }

    /** Todas las revisiones de la misión, de todos los incrementos. */
    public List<DesignScreenReview> all(String missionId) {
        return query("MATCH (:Mission {id:$id})-[:HAS_DESIGN_REVIEW]->(r:DesignScreenReview)" + RETURN,
                Map.of("id", missionId));
    }

    private List<DesignScreenReview> query(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.run(cypher, params).list(DesignReviewMemoryService::review);
        }
    }

    private static DesignScreenReview review(Record r) {
        return new DesignScreenReview(r.get("increment").asInt(), r.get("screen").asString(),
                r.get("size").isNull() ? "" : r.get("size").asString(),
                r.get("ssim").isNull() ? 0.0 : r.get("ssim").asDouble(), r.get("status").asString(),
                r.get("rounds").isNull() ? 0 : r.get("rounds").asInt(),
                r.get("extraRounds").isNull() ? 0 : r.get("extraRounds").asInt(),
                r.get("decidedBy").isNull() ? null : r.get("decidedBy").asString(),
                r.get("decisionNote").isNull() ? null : r.get("decisionNote").asString(),
                r.get("updatedAt").isNull() ? null : r.get("updatedAt").asString());
    }
}
