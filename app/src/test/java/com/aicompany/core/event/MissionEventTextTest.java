package com.aicompany.core.event;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Bitácora (spec 2026-10-17): Java arma el resumen de cada evento, nunca un modelo. */
class MissionEventTextTest {

    @Test void missionUpdatedShowsStatusStepAndMessage() {
        assertEquals("Misión → WAITING_AGENT_RESULTS: Desarrollo por capas — Kael (2/3) está generando [game]",
                MissionEventText.summary("EMPRESA_MISSION_UPDATED", "Alex", Map.of("status", "WAITING_AGENT_RESULTS",
                        "progress", 56, "currentStep", "Desarrollo por capas",
                        "message", "Kael (2/3) está generando [game]")));
    }

    @Test void taskCommittedShowsFilesAndRepairRound() {
        assertEquals("Kael commiteó 1 archivo (game/Main.cs) — reparación 2",
                MissionEventText.summary("EMPRESA_TASK_COMMITTED", "Kael",
                        Map.of("commitSha", "abc", "files", List.of("game/Main.cs"), "repairRound", 2)));
        assertEquals("Iris commiteó 3 archivos (a.cs, b.cs, c.cs)",
                MissionEventText.summary("EMPRESA_TASK_COMMITTED", "Iris",
                        Map.of("files", List.of("a.cs", "b.cs", "c.cs"))));
        assertEquals("Vera commiteó 5 archivos (a, b, c…)",
                MissionEventText.summary("EMPRESA_TASK_COMMITTED", "Vera",
                        Map.of("files", List.of("a", "b", "c", "d", "e"))));
    }

    @Test void sandboxEvents() {
        assertEquals("Sandbox arrancó (vuelta 1)",
                MissionEventText.summary("EMPRESA_SANDBOX_STARTED", "sandbox", Map.of("round", 1, "commitSha", "x")));
        assertEquals("Sandbox FAIL en build: 7 errores de compilación",
                MissionEventText.summary("EMPRESA_SANDBOX_FINISHED", "sandbox", Map.of("overall", "FAIL",
                        "failedStep", "build", "testsPassed", 0, "testsFailed", 0, "compileErrors", 7, "round", 0)));
        assertEquals("Sandbox FAIL en test: 27 tests OK, 4 fallan",
                MissionEventText.summary("EMPRESA_SANDBOX_FINISHED", "sandbox", Map.of("overall", "FAIL",
                        "failedStep", "test", "testsPassed", 27, "testsFailed", 4, "compileErrors", 0, "round", 1)));
        assertEquals("Sandbox PASS: 25 tests",
                MissionEventText.summary("EMPRESA_SANDBOX_VERIFICATION_COMPLETED", "sandbox", Map.of("overall", "PASS",
                        "testsPassed", 25, "testsFailed", 0)));
    }

    @Test void correctionRepairTriageRejectionAndModel() {
        assertEquals("Corrección automática ronda 18",
                MissionEventText.summary("EMPRESA_MISSION_CORRECTION_STARTED", "system", Map.of("round", 18)));
        assertEquals("Errores de compilación devueltos a Vera: 4 (2 REPETIDOS) en ESC_07.cs",
                MissionEventText.summary("EMPRESA_REPAIR_REQUESTED", "Vera",
                        Map.of("errors", 4, "repeated", 2, "files", List.of("ESC_07.cs"))));
        assertEquals("Triage de Neo: ESC_04_x → TEST_WRONG",
                MissionEventText.summary("EMPRESA_TEST_TRIAGE_COMPLETED", "Neo",
                        Map.of("verdicts", List.of("ESC_04_x → TEST_WRONG"))));
        assertEquals("Triage de Neo falló: NullPointerException",
                MissionEventText.summary("EMPRESA_TEST_TRIAGE_COMPLETED", "Neo", Map.of("error", "NullPointerException")));
        assertEquals("Intento 2 de Kael rechazado: falta X; falta Y",
                MissionEventText.summary("EMPRESA_TASK_ATTEMPT_REJECTED", "Kael",
                        Map.of("attempt", 2, "reasons", List.of("falta X", "falta Y"))));
        assertEquals("Modelo de Aria: nvidia:a → bedrock:b (PAID)",
                MissionEventText.summary("EMPRESA_MODEL_SWITCHED", "Aria",
                        Map.of("operation", "X", "from", "nvidia:a", "to", "bedrock:b", "kind", "PAID")));
    }

    @Test void unknownTypesUseTheGenericTemplateAndEverythingIsCapped() {
        assertEquals("REQUIREMENTS APPROVED por human",
                MissionEventText.summary("EMPRESA_REQUIREMENTS_APPROVED", "human", Map.of("version", 1)));
        var longMessage = "x".repeat(1000);
        var text = MissionEventText.summary("EMPRESA_MISSION_UPDATED", "Alex",
                Map.of("status", "PLANNING", "currentStep", "Plan", "message", longMessage));
        assertEquals(MissionEventText.MAX_SUMMARY, text.length());
        assertTrue(text.endsWith("…"));
        assertEquals("TASK RETRY por Kael", MissionEventText.summary("EMPRESA_TASK_RETRY", "Kael", null));
        assertEquals("Misión → PLANNING", MissionEventText.summary("EMPRESA_MISSION_UPDATED", null,
                Map.of("status", "PLANNING")));
    }
}
