package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.StaticReviewResult.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificado en vivo (MISSION-TEAM-VERIFY-3): Vera marcó 6 BLOCKER afirmando
 * que src/backend/*.js "no existen" aunque estaban en el repo y el chequeo
 * determinista FILES_IN_COMMIT dio PASS.
 */
class MissingFileClaimGateTest {

    private final MissingFileClaimGate gate = new MissingFileClaimGate();
    private final Set<String> repoFiles = Set.of("src/backend/game-logic.js", "src/index.html");

    private static StaticReviewResult review(List<Finding> findings, List<String> missingFiles) {
        return new StaticReviewResult("ISSUES_FOUND", findings, missingFiles, "coherente",
                List.of("No se verificó la ejecución."), List.of());
    }

    @Test
    void rejectsDeclaringAnExistingFileAsMissing() {
        var errors = gate.validate(review(List.of(), List.of("src/backend/game-logic.js")), repoFiles);
        assertTrue(errors.stream().anyMatch(e -> e.contains("src/backend/game-logic.js") && e.contains("sí existe")),
                errors.toString());
    }

    @Test
    void rejectsAFindingThatClaimsAnExistingFileDoesNotExist() {
        var finding = new Finding("src/backend/game-logic.js", "BLOCKER",
                "Los archivos src/backend/game-logic.js no existen en el commit backend.");
        var errors = gate.validate(review(List.of(finding), List.of()), repoFiles);
        assertTrue(errors.stream().anyMatch(e -> e.contains("src/backend/game-logic.js")), errors.toString());
    }

    @Test
    void rejectsANonExistenceClaimThatOnlyNamesTheFileInTheDescription() {
        var finding = new Finding("architecture.md", "MAJOR",
                "La arquitectura menciona src/index.html, pero ese archivo no está presente.");
        var errors = gate.validate(review(List.of(finding), List.of()), repoFiles);
        assertFalse(errors.isEmpty());
    }

    @Test
    void acceptsReallyMissingFiles() {
        var finding = new Finding("src/backend/auth.js", "MAJOR", "El archivo src/backend/auth.js no existe.");
        var errors = gate.validate(review(List.of(finding), List.of("src/backend/auth.js")), repoFiles);
        assertEquals(List.of(), errors);
    }

    @Test
    void acceptsFindingsAboutExistingFilesThatDoNotClaimNonExistence() {
        var finding = new Finding("src/backend/game-logic.js", "MAJOR",
                "game-logic.js solo registra mensajes en consola y no implementa reglas del juego.");
        assertEquals(List.of(), gate.validate(review(List.of(finding), List.of()), repoFiles));
    }
}
