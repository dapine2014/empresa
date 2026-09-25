package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.StaticReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryEvidenceGateTest {

    private static final String SHA = "a".repeat(40);
    private final RepositoryEvidenceGate gate = new RepositoryEvidenceGate();
    private final Map<String, Set<String>> filesBySha = Map.of(SHA, Set.of("web/index.html", "web/game/main.js"));

    private static StaticReviewResult reviewWith(List<AgentResult.Evidence> evidence) {
        return new StaticReviewResult("NO_EVIDENT_ISSUES", List.of(), List.of(), "coherente",
                List.of("No se verificó la ejecución."), evidence);
    }

    private static AgentResult.Evidence internal(String source) {
        return new AgentResult.Evidence("Revisé el archivo", source, "INTERNAL", true);
    }

    @Test
    void acceptsACitationOfARealFileInARealCommit() {
        var source = RepositoryEvidenceGate.citation("MISSION-1", SHA, "web/game/main.js");
        assertEquals(List.of(), gate.validate(reviewWith(List.of(internal(source))), "MISSION-1", filesBySha));
    }

    @Test
    void requiresAtLeastOneInternalCitation() {
        var errors = gate.validate(reviewWith(List.of()), "MISSION-1", filesBySha);
        assertFalse(errors.isEmpty());
    }

    @Test
    void rejectsAShaThatIsNotACommitOfTheMission() {
        var source = RepositoryEvidenceGate.citation("MISSION-1", "b".repeat(40), "web/index.html");
        var errors = gate.validate(reviewWith(List.of(internal(source))), "MISSION-1", filesBySha);
        assertTrue(errors.stream().anyMatch(e -> e.contains("no es un commit")), errors.toString());
    }

    @Test
    void rejectsAFileThatDoesNotExistInThatCommit() {
        var source = RepositoryEvidenceGate.citation("MISSION-1", SHA, "web/inventado.js");
        var errors = gate.validate(reviewWith(List.of(internal(source))), "MISSION-1", filesBySha);
        assertTrue(errors.stream().anyMatch(e -> e.contains("web/inventado.js")), errors.toString());
    }

    @Test
    void rejectsAMalformedSourceAndAnotherMissionsWorkspace() {
        assertFalse(gate.validate(reviewWith(List.of(internal("web/index.html"))), "MISSION-1", filesBySha).isEmpty());
        var other = RepositoryEvidenceGate.citation("MISSION-2", SHA, "web/index.html");
        assertFalse(gate.validate(reviewWith(List.of(internal(other))), "MISSION-1", filesBySha).isEmpty());
    }
}
