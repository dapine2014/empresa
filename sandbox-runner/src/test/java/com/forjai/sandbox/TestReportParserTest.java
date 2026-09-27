package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestReportParserTest {

    @TempDir
    Path work;

    @Test
    void readsDotnetTrxCounters() throws Exception {
        Files.createDirectories(work.resolve(".forjai"));
        Files.writeString(work.resolve(".forjai/results.trx"), """
                <TestRun><ResultSummary outcome="Failed">
                <Counters total="5" executed="5" passed="4" failed="1" error="0" /></ResultSummary></TestRun>
                """);
        assertEquals(new TestReportParser.TestCounts(4, 1), TestReportParser.parse(work));
    }

    @Test
    void readsFlutterMachineJson() throws Exception {
        Files.createDirectories(work.resolve(".forjai"));
        Files.writeString(work.resolve(".forjai/results.json"), String.join("\n",
                "{\"type\":\"start\"}",
                "{\"type\":\"testDone\",\"testID\":1,\"result\":\"success\",\"hidden\":true}",
                "{\"type\":\"testDone\",\"testID\":2,\"result\":\"success\",\"hidden\":false}",
                "{\"type\":\"testDone\",\"testID\":3,\"result\":\"failure\",\"hidden\":false}",
                "{\"type\":\"done\",\"success\":false}"));
        assertEquals(new TestReportParser.TestCounts(1, 1), TestReportParser.parse(work));
    }

    // Review Focus: si el paso falló antes de escribir el reporte, el conteo es 0/0, sin excepción.
    @Test
    void aMissingReportCountsAsZero() {
        assertEquals(new TestReportParser.TestCounts(0, 0), TestReportParser.parse(work));
    }
}
