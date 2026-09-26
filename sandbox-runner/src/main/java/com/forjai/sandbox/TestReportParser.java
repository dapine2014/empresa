package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Conteo de tests desde reportes estructurados (TRX de dotnet test, JSON de flutter test), nunca texto libre. */
public final class TestReportParser {

    public record TestCounts(int passed, int failed) {
    }

    private static final Pattern TRX_COUNTERS = Pattern.compile(
            "<Counters[^>]*\\bpassed=\"(\\d+)\"[^>]*\\bfailed=\"(\\d+)\"");

    private TestReportParser() {
    }

    public static TestCounts parse(Path workDir) {
        try {
            var trx = workDir.resolve(".forjai/results.trx");
            if (Files.isRegularFile(trx)) {
                var matcher = TRX_COUNTERS.matcher(Files.readString(trx));
                return matcher.find()
                        ? new TestCounts(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)))
                        : new TestCounts(0, 0);
            }
            var json = workDir.resolve(".forjai/results.json");
            if (Files.isRegularFile(json)) {
                var passed = 0;
                var failed = 0;
                for (var line : Files.readAllLines(json)) {
                    if (!line.contains("\"type\":\"testDone\"") || line.contains("\"hidden\":true")) {
                        continue;
                    }
                    if (line.contains("\"result\":\"success\"")) {
                        passed++;
                    } else {
                        failed++;
                    }
                }
                return new TestCounts(passed, failed);
            }
        } catch (IOException | RuntimeException ex) {
            return new TestCounts(0, 0);
        }
        return new TestCounts(0, 0);
    }
}
