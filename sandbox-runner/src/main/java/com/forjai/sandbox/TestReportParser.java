package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Conteo de tests desde reportes estructurados (TRX de dotnet test, JSON de flutter test), nunca texto libre. QA casos de
 * uso (2026-10-05): además, cada test con su nombre y resultado.
 */
public final class TestReportParser {

    public record TestCounts(int passed, int failed, List<SandboxResult.TestCase> tests) {
        public TestCounts(int passed, int failed) {
            this(passed, failed, List.of());
        }
    }

    private static final Pattern TRX_COUNTERS = Pattern.compile(
            "<Counters[^>]*\\bpassed=\"(\\d+)\"[^>]*\\bfailed=\"(\\d+)\"");
    private static final Pattern TRX_RESULT = Pattern.compile("<UnitTestResult\\b[^>]*>");
    // Revisión final QA (C1): el reporter real pone "type" al final; se lee el objeto "test" sin depender del orden.
    private static final Pattern JSON_START = Pattern.compile("\"test\":\\{\"id\":(\\d+),\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JSON_DONE_ID = Pattern.compile("\"testID\":(\\d+)");

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private TestReportParser() {
    }

    public static TestCounts parse(Path workDir) {
        var base = parseReports(workDir);
        var games = playthroughs(workDir);
        if (games.isEmpty()) {
            return base;
        }
        var tests = new ArrayList<>(base.tests());
        tests.addAll(games);
        var passed = (int) games.stream().filter(t -> "PASSED".equals(t.outcome())).count();
        return new TestCounts(base.passed() + passed, base.failed() + games.size() - passed, tests);
    }

    /** Spec 2026-10-14 Kael §2.3: partidas simuladas del runner de Forjai; ausente o corrupto = ninguna. */
    private static List<SandboxResult.TestCase> playthroughs(Path workDir) {
        try {
            var file = workDir.resolve(".forjai/playthroughs.json");
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            var out = new ArrayList<SandboxResult.TestCase>();
            // Verificado en vivo (Pong, 2026-10-15): se lee como JSON (System.Text.Json escapa los no ASCII) y la
            // partida fallida lleva su paso y su motivo, para que la corrección no sea a ciegas.
            for (var item : JSON.readTree(file.toFile())) {
                var name = item.path("name").asString("");
                var outcome = item.path("outcome").asString("");
                if (name.isEmpty() || !("PASSED".equals(outcome) || "FAILED".equals(outcome))) {
                    continue;
                }
                var step = item.path("failedStep").asString("").strip();
                var message = item.path("message").asString("").strip();
                var detail = "FAILED".equals(outcome) && !(step + message).isEmpty()
                        ? (step.isEmpty() ? message : message.isEmpty() ? step : step + ": " + message) : null;
                out.add(new SandboxResult.TestCase("[juego] " + name, outcome, detail));
            }
            return out;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** Las partidas fallidas con su paso y motivo, para el final de la salida del paso test; "" si no hay. */
    public static String failureDetails(List<SandboxResult.TestCase> tests) {
        var lines = tests.stream().filter(t -> "FAILED".equals(t.outcome()) && t.detail() != null)
                .map(t -> "- " + t.name() + ": " + t.detail() + "\n").toList();
        return lines.isEmpty() ? "" : "\nPartidas simuladas fallidas (paso y motivo):\n" + String.join("", lines);
    }

    private static TestCounts parseReports(Path workDir) {
        try {
            var trx = workDir.resolve(".forjai/results.trx");
            if (Files.isRegularFile(trx)) {
                var content = Files.readString(trx);
                var matcher = TRX_COUNTERS.matcher(content);
                var tests = trxTests(content);
                return matcher.find()
                        ? new TestCounts(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), tests)
                        : new TestCounts(0, 0, tests);
            }
            var json = workDir.resolve(".forjai/results.json");
            if (Files.isRegularFile(json)) {
                var passed = 0;
                var failed = 0;
                var names = new HashMap<String, String>();
                var tests = new ArrayList<SandboxResult.TestCase>();
                for (var line : Files.readAllLines(json)) {
                    var start = JSON_START.matcher(line);
                    if (line.contains("\"type\":\"testStart\"") && start.find()) {
                        names.put(start.group(1), start.group(2).replace("\\\"", "\"").replace("\\\\", "\\"));
                        continue;
                    }
                    if (!line.contains("\"type\":\"testDone\"") || line.contains("\"hidden\":true")) {
                        continue;
                    }
                    var skipped = line.contains("\"skipped\":true");
                    var success = line.contains("\"result\":\"success\"");
                    if (success && !skipped) {
                        passed++;
                    } else if (!success) {
                        failed++;
                    }
                    var id = JSON_DONE_ID.matcher(line);
                    var name = id.find() ? names.getOrDefault(id.group(1), "") : "";
                    tests.add(new SandboxResult.TestCase(name, skipped ? "SKIPPED" : success ? "PASSED" : "FAILED"));
                }
                return new TestCounts(passed, failed, tests);
            }
        } catch (IOException | RuntimeException ex) {
            return new TestCounts(0, 0);
        }
        return new TestCounts(0, 0);
    }

    /** Revisión final QA (C2): el TRX de vstest no trae los Trait; el id del escenario va en el nombre del método. */
    private static List<SandboxResult.TestCase> trxTests(String content) {
        var tests = new ArrayList<SandboxResult.TestCase>();
        var results = TRX_RESULT.matcher(content);
        while (results.find()) {
            var tag = results.group();
            var name = attribute(tag, "testName");
            var outcome = switch (attribute(tag, "outcome")) {
                case "Passed" -> "PASSED";
                case "Failed", "Error", "Timeout", "Aborted" -> "FAILED";
                default -> "SKIPPED";
            };
            tests.add(new SandboxResult.TestCase(name, outcome));
        }
        return tests;
    }

    private static String attribute(String tag, String name) {
        var m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        // Revisión final QA (M5): entidades XML del TRX.
        return m.find() ? m.group(1).replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&") : "";
    }
}
