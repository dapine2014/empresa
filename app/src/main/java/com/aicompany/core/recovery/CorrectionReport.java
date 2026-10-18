package com.aicompany.core.recovery;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.SandboxResult;
import tools.jackson.databind.json.JsonMapper;

import com.aicompany.core.agent.validation.CompilerErrorParser;
import com.aicompany.core.service.ComponentSandbox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Spec misiones durables §3: lo que falla de la última validación, para la ronda de corrección. {@code failing} = escenarios
 * sin test aprobado + chequeos fallidos (sin contar SCENARIO_COVERAGE) + errores de compilación distintos de cada restore/build fallido (o el paso, si no se leen) + los demás pasos fallidos; sin validación, 1
 * (la falla antes del código).
 */
public record CorrectionReport(String text, int failing, List<String> failures, boolean fromValidation) {

    /** Revisión final (5): {@code fromValidation} = el reporte sale de una validación del estado actual (no del mensaje de la misión). */
    public CorrectionReport(String text, int failing, List<String> failures) {
        this(text, failing, failures, true);
    }

    public static final String HEADER = "CORRECCIÓN AUTOMÁTICA (Java)";
    public static final String RULE = "Corrige hasta que todos los escenarios del incremento pasen. No borres, renombres ni "
            + "debilites tests.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COVERAGE_REPORT = "Reporte de cobertura de escenarios (Java, QA casos de uso):";

    public static CorrectionReport from(Optional<AgentTask> lastValidation, String missionMessage) {
        var lines = new ArrayList<String>();
        var failing = 0;
        var failures = new ArrayList<String>();
        if (lastValidation.isEmpty()) {
            lines.add("- La misión falló antes de tener código: " + (missionMessage == null ? "" : missionMessage));
            failing = 1;
            failures.add("La misión falló antes de tener código: " + (missionMessage == null ? "" : missionMessage));
        } else {
            var task = lastValidation.get();
            var result = task.result() == null ? "" : task.result();
            if (result.startsWith(COVERAGE_REPORT)) {
                for (var line : result.substring(COVERAGE_REPORT.length()).split("\n")) {
                    if (line.strip().startsWith("❌")) {
                        lines.add("- Escenario sin test aprobado: " + line.strip().substring(1).strip());
                        failures.add("Escenario sin test aprobado: " + line.strip().substring(1).strip());
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
                            failures.add("Chequeo " + check + " falló: " + node.path("detail").asString());
                            failing++;
                        }
                    }
                }
                if (task.sandboxResult() != null) {
                    var sandbox = JSON.readValue(task.sandboxResult(), SandboxResult.class);
                    for (var step : sandbox.stepsOrEmpty()) {
                        if ("FAIL".equals(step.status()) || "TIMEOUT".equals(step.status())) {
                            var errors = compileErrors(step);
                            if (!errors.isEmpty()) {
                                lines.add("- Paso del sandbox " + step.name() + " " + step.status() + ", " + errors.size()
                                        + " error(es) de compilación:");
                                errors.forEach(e -> lines.add(COMPILE_ITEM + e));
                                failures.addAll(errors);
                                failing += errors.size();
                                continue;
                            }
                            var tail = step.outputTail() == null ? "" : step.outputTail();
                            lines.add("- Paso del sandbox " + step.name() + " " + step.status() + ":\n"
                                    + tail.substring(Math.max(0, tail.length() - 3000)));
                            failures.add("Paso del sandbox " + step.name() + " " + step.status() + ": " + lastLine(tail));
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
                failures.add("La validación terminó en " + task.validationStatus() + " sin un detalle legible");
            }
        }
        return new CorrectionReport(HEADER + ":\n" + String.join("\n", lines) + "\n" + RULE, failing, List.copyOf(failures),
                lastValidation.isPresent());
    }

    public static final String REPEATED_MARK = " ⚠ REPETIDO: ya fallaba igual en la ronda anterior y no se corrigió";

    /**
     * Pong de 3 niveles (2026-10-17): Vera y Kael dijeron haber corregido errores de compilación que siguieron idénticos
     * en la ronda siguiente. Java marca cada error de compilación cuya huella ({@link #fingerprint}) ya estaba en las
     * fallas de la ronda anterior; {@code failures} no cambia (el freno y la estrategia comparan las mismas huellas).
     */
    public CorrectionReport withRepeated(List<String> previousFailures) {
        if (previousFailures == null || previousFailures.isEmpty()) {
            return this;
        }
        var previous = previousFailures.stream().map(CorrectionReport::fingerprint).collect(java.util.stream.Collectors.toSet());
        var repeated = 0;
        var lines = new ArrayList<String>();
        for (var line : text.split("\n", -1)) {
            if (line.startsWith(COMPILE_ITEM) && previous.contains(fingerprint(line.substring(COMPILE_ITEM.length())))) {
                lines.add(line + REPEATED_MARK);
                repeated++;
            } else {
                lines.add(line);
            }
        }
        if (repeated == 0) {
            return this;
        }
        var marked = String.join("\n", lines);
        var warning = "ATENCIÓN: " + repeated + " error(es) de compilación se repiten idénticos de la ronda anterior (marcados "
                + "REPETIDO). Decir que se corrigieron no basta: el dueño de cada archivo debe cambiar ESAS líneas.\n";
        marked = marked.startsWith(HEADER + ":\n")
                ? HEADER + ":\n" + warning + marked.substring(HEADER.length() + 2) : warning + marked;
        return new CorrectionReport(marked, failing, failures, fromValidation);
    }

    /** Así se lista cada error de compilación en {@link #text}. */
    private static final String COMPILE_ITEM = "  - ";

    /**
     * Spec misiones durables §3 (revisión 2026-10-06): la huella de una falla para saber si es la misma de una ronda
     * anterior: minúsculas, sin números de línea ({@code :12}, {@code (12,5)}), sin números largos ni rutas temporales.
     */
    public static String fingerprint(String failure) {
        if (failure == null) {
            return "";
        }
        return failure.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("/tmp/\\S*|\\bjob-\\S*", "<tmp>")
                .replaceAll("\\(\\d+,\\d+\\)", "(<n>)")
                .replaceAll(":\\d+", ":<n>")
                .replaceAll("(?<![a-z\\d])\\d{4,}", "<n>")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String lastLine(String text) {
        var lines = text.strip().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1].strip();
    }

    /**
     * Spec misiones durables §3: los errores del compilador de un paso restore/build ({@code backend/build} incluido),
     * distintos y con la ruta real en el repositorio ({@code archivo:línea código mensaje}); cada uno cuenta aparte.
     */
    private static List<String> compileErrors(SandboxResult.StepResult step) {
        var name = ComponentSandbox.stepName(step.name());
        if (!"restore".equals(name) && !"build".equals(name)) {
            return List.of();
        }
        var dir = ComponentSandbox.dirOf(step.name());
        var distinct = new LinkedHashSet<String>();
        for (var e : CompilerErrorParser.parse(step.outputTail())) {
            distinct.add((dir.isEmpty() ? "" : dir + "/") + e.path() + ":" + e.line() + " " + e.code() + " " + e.message());
        }
        return List.copyOf(distinct);
    }
}
