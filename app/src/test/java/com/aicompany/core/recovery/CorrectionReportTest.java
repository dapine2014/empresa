package com.aicompany.core.recovery;

import com.aicompany.core.model.AgentTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Spec misiones durables §3: lo que falla, armado en Java desde la última validación. */
class CorrectionReportTest {

    private static AgentTask validation(String checks, String sandbox, String result) {
        return new AgentTask("M-1-QA-REVIEW", "M-1", "qa", "CODE_REVIEW", "COMPLETED", result, Instant.now(), "VALIDATION",
                null, null, List.of(), "FAILED", checks, sandbox);
    }

    @Test void listsUncoveredScenariosFailedChecksAndTheFailedStep() {
        var report = CorrectionReport.from(Optional.of(validation(
                "[{\"check\":\"SCENARIO_COVERAGE\",\"status\":\"FAIL\",\"detail\":\"4/5: sin test aprobado ESC-05 (ver detalle)\"},"
                        + "{\"check\":\"DDD_LAYERS\",\"status\":\"FAIL\",\"detail\":\"domain importa infrastructure\"},"
                        + "{\"check\":\"ENTRY_FILES\",\"status\":\"PASS\",\"detail\":\"ok\"}]",
                "{\"overall\":\"FAIL\",\"steps\":[{\"name\":\"backend/test\",\"status\":\"FAIL\",\"exitCode\":1,\"durationMs\":1,"
                        + "\"outputTail\":\"Failed ESC_05_detalle\",\"testsPassed\":4,\"testsFailed\":1}]}",
                "Reporte de cobertura de escenarios (Java, QA casos de uso):\n❌ ESC-05 (incremento 1, nuevo) ver detalle: sin test aprobado")),
                "");

        assertTrue(report.text().startsWith(CorrectionReport.HEADER), report.text());
        assertTrue(report.text().contains("ESC-05") && report.text().contains("DDD_LAYERS")
                && report.text().contains("backend/test") && report.text().contains("Failed ESC_05_detalle"), report.text());
        assertFalse(report.text().contains("ENTRY_FILES"), report.text());
        assertTrue(report.text().contains(CorrectionReport.RULE));
        assertEquals(3, report.failing()); // 1 escenario + 1 chequeo no-cobertura + 1 paso
    }

    @Test void aFailureBeforeAnyCodeUsesTheMissionMessage() {
        var report = CorrectionReport.from(Optional.empty(), "El líder engineering no produjo un plan válido");
        assertTrue(report.text().contains("no produjo un plan válido"), report.text());
        assertEquals(1, report.failing());
    }
}
