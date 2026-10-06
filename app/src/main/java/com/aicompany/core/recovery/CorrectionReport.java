package com.aicompany.core.recovery;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.SandboxResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Spec misiones durables §3: lo que falla de la última validación, para la ronda de corrección. {@code failing} = escenarios
 * sin test aprobado + chequeos fallidos (sin contar SCENARIO_COVERAGE) + pasos del sandbox fallidos; sin validación, 1
 * (la falla antes del código).
 */
public record CorrectionReport(String text, int failing) {

    public static final String HEADER = "CORRECCIÓN AUTOMÁTICA (Java)";
    public static final String RULE = "Corrige hasta que todos los escenarios del incremento pasen. No borres, renombres ni "
            + "debilites tests.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COVERAGE_REPORT = "Reporte de cobertura de escenarios (Java, QA casos de uso):";

    public static CorrectionReport from(Optional<AgentTask> lastValidation, String missionMessage) {
        var lines = new ArrayList<String>();
        var failing = 0;
        if (lastValidation.isEmpty()) {
            lines.add("- La misión falló antes de tener código: " + (missionMessage == null ? "" : missionMessage));
            failing = 1;
        } else {
            var task = lastValidation.get();
            var result = task.result() == null ? "" : task.result();
            if (result.startsWith(COVERAGE_REPORT)) {
                for (var line : result.substring(COVERAGE_REPORT.length()).split("\n")) {
                    if (line.strip().startsWith("❌")) {
                        lines.add("- Escenario sin test aprobado: " + line.strip().substring(1).strip());
                        failing++;
                    }
                }
            }
            try {
                if (task.staticChecks() != null) {
                    for (var node : JSON.readTree(task.staticChecks())) {
                        var check = node.path("check").asString();
                        if (!"PASS".equals(node.path("status").asString()) && !"SCENARIO_COVERAGE".equals(check)) {
                            lines.add("- Chequeo " + check + " falló: " + node.path("detail").asString());
                            failing++;
                        }
                    }
                }
                if (task.sandboxResult() != null) {
                    var sandbox = JSON.readValue(task.sandboxResult(), SandboxResult.class);
                    for (var step : sandbox.stepsOrEmpty()) {
                        if ("FAIL".equals(step.status()) || "TIMEOUT".equals(step.status())) {
                            var tail = step.outputTail() == null ? "" : step.outputTail();
                            lines.add("- Paso del sandbox " + step.name() + " " + step.status() + ":\n"
                                    + tail.substring(Math.max(0, tail.length() - 3000)));
                            failing++;
                        }
                    }
                }
            } catch (Exception ex) {
                lines.add("- (No se pudo leer el detalle de la validación: " + ex.getMessage() + ")");
            }
            if (failing == 0) {
                lines.add("- La validación terminó en " + task.validationStatus() + " sin un detalle legible.");
                failing = 1;
            }
        }
        return new CorrectionReport(HEADER + ":\n" + String.join("\n", lines) + "\n" + RULE, failing);
    }
}
