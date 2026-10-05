package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Conteo de tests desde reportes estructurados (TRX de dotnet test, JSON de flutter test), nunca texto libre. QA casos de
 * uso (2026-10-05): además, cada test con su nombre y resultado; los ESC-NN de los Trait del TRX se suman al nombre.
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
    private static final Pattern TRX_DEFINITION = Pattern.compile("<UnitTest\\b[^>]*\\bid=\"([^\"]+)\"[^>]*>(.*?)</UnitTest>",
            Pattern.DOTALL);
    private static final Pattern SCENARIO = Pattern.compile("ESC-\\d{2}");
    private static final Pattern JSON_START = Pattern.compile("\"type\":\"testStart\".*?\"id\":(\\d+).*?\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JSON_DONE_ID = Pattern.compile("\"testID\":(\\d+)");

    private TestReportParser() {
    }

    public static TestCounts parse(Path workDir) {
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
                    if (start.find()) {
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

    private static List<SandboxResult.TestCase> trxTests(String content) {
        var traits = new HashMap<String, LinkedHashSet<String>>();
        var definitions = TRX_DEFINITION.matcher(content);
        while (definitions.find()) {
            var ids = new LinkedHashSet<String>();
            var scenario = SCENARIO.matcher(definitions.group(2));
            while (scenario.find()) {
                ids.add(scenario.group());
            }
            traits.put(definitions.group(1), ids);
        }
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
            var extra = traits.getOrDefault(attribute(tag, "testId"), new LinkedHashSet<>());
            tests.add(new SandboxResult.TestCase(extra.isEmpty() ? name : name + " [" + String.join(", ", extra) + "]",
                    outcome));
        }
        return tests;
    }

    private static String attribute(String tag, String name) {
        var m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : "";
    }
}
