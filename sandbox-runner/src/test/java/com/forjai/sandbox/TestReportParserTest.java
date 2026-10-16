package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        var counts = TestReportParser.parse(work);
        assertEquals(1, counts.passed());
        assertEquals(1, counts.failed());
    }

    // Review Focus: si el paso falló antes de escribir el reporte, el conteo es 0/0, sin excepción.
    @Test
    void aMissingReportCountsAsZero() {
        assertEquals(new TestReportParser.TestCounts(0, 0), TestReportParser.parse(work));
    }
    // QA casos de uso (2026-10-05): cada test con su nombre y resultado (revisión final C2: el TRX real no trae Trait).
    @Test
    void trxTestsComeWithNameOutcomeAndTraits(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".forjai"));
        Files.writeString(dir.resolve(".forjai/results.trx"), """
                <TestRun><Results>
                  <UnitTestResult testId="a" testName="Crm.Tests.ClientesTests.ESC_01_crea_cliente" outcome="Passed" />
                  <UnitTestResult testId="b" testName="Crm.Tests.ClientesTests.Lista" outcome="Failed" />
                  <UnitTestResult testId="c" testName="Crm.Tests.ClientesTests.Viejo" outcome="NotExecuted" />
                </Results><TestDefinitions>
                  <UnitTest id="b" name="Lista"><Properties><Property><Key>Escenario</Key><Value>ESC-02</Value></Property></Properties></UnitTest>
                </TestDefinitions>
                <ResultSummary><Counters total="3" passed="1" failed="1" /></ResultSummary></TestRun>
                """);
        var counts = TestReportParser.parse(dir);
        assertEquals(1, counts.passed());
        assertEquals(List.of(new SandboxResult.TestCase("Crm.Tests.ClientesTests.ESC_01_crea_cliente", "PASSED"),
                new SandboxResult.TestCase("Crm.Tests.ClientesTests.Lista", "FAILED"),
                new SandboxResult.TestCase("Crm.Tests.ClientesTests.Viejo", "SKIPPED")), counts.tests());
    }

    @Test
    void flutterJsonTestsComeWithNameAndOutcome(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".forjai"));
        Files.writeString(dir.resolve(".forjai/results.json"), String.join("\n",
                "{\"type\":\"testStart\",\"test\":{\"id\":1,\"name\":\"loading /work/test/a_test.dart\"}}",
                "{\"type\":\"testDone\",\"testID\":1,\"result\":\"success\",\"hidden\":true,\"skipped\":false}",
                "{\"type\":\"testStart\",\"test\":{\"id\":2,\"name\":\"ESC-11 crea oportunidad\"}}",
                "{\"type\":\"testDone\",\"testID\":2,\"result\":\"success\",\"hidden\":false,\"skipped\":false}",
                "{\"type\":\"testStart\",\"test\":{\"id\":3,\"name\":\"ESC-12 lista\"}}",
                "{\"type\":\"testDone\",\"testID\":3,\"result\":\"success\",\"hidden\":false,\"skipped\":true}",
                "{\"type\":\"testStart\",\"test\":{\"id\":4,\"name\":\"ESC-13 borra\"}}",
                "{\"type\":\"testDone\",\"testID\":4,\"result\":\"failure\",\"hidden\":false,\"skipped\":false}"));
        var counts = TestReportParser.parse(dir);
        assertEquals(List.of(new SandboxResult.TestCase("ESC-11 crea oportunidad", "PASSED"),
                new SandboxResult.TestCase("ESC-12 lista", "SKIPPED"),
                new SandboxResult.TestCase("ESC-13 borra", "FAILED")), counts.tests());
    }

    // Revisión final QA (C1): el reporter real de package:test emite "type" al final de cada línea.
    @Test
    void realFlutterReporterOrderStillGivesNames(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".forjai"));
        Files.writeString(dir.resolve(".forjai/results.json"), String.join("\n",
                "{\"test\":{\"id\":3,\"name\":\"Clientes ESC-11 crea \\\"oportunidad\\\"\",\"suiteID\":0,\"groupIDs\":[2]},\"type\":\"testStart\",\"time\":3}",
                "{\"testID\":3,\"result\":\"success\",\"skipped\":false,\"hidden\":false,\"type\":\"testDone\",\"time\":9}"));
        var counts = TestReportParser.parse(dir);
        assertEquals(List.of(new SandboxResult.TestCase("Clientes ESC-11 crea \"oportunidad\"", "PASSED")), counts.tests());
    }

    // Revisión final QA (M5): los nombres del TRX se desescapan.
    @Test
    void trxNamesAreUnescaped(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".forjai"));
        Files.writeString(dir.resolve(".forjai/results.trx"),
                "<TestRun><Results><UnitTestResult testId=\"a\" outcome=\"Passed\" testName=\"Ns.C.ESC_06_t(s: &quot;a&amp;b&quot;)\" /></Results></TestRun>");
        assertEquals(List.of(new SandboxResult.TestCase("Ns.C.ESC_06_t(s: \"a&b\")", "PASSED")), TestReportParser.parse(dir).tests());
    }

    @Test
    void playthroughsAreAddedToTheTrxTests() throws Exception {
        Files.createDirectories(work.resolve(".forjai"));
        Files.writeString(work.resolve(".forjai/results.trx"), """
                <TestRun><Results><UnitTestResult testName="Pong.Tests.ScoreTests.ESC_02_suma" outcome="Passed" /></Results>
                <ResultSummary><Counters total="1" passed="1" failed="0" /></ResultSummary></TestRun>""");
        Files.writeString(work.resolve(".forjai/playthroughs.json"),
                "[{\"name\":\"ESC_01_mueve\",\"outcome\":\"PASSED\",\"failedStep\":\"\",\"message\":\"\"},"
                + "{\"name\":\"ESC_03_pierde\",\"outcome\":\"FAILED\",\"failedStep\":\"paso 2\",\"message\":\"falló la aserción: game over\"}]");
        var counts = TestReportParser.parse(work);
        assertEquals(2, counts.passed());
        assertEquals(1, counts.failed());
        assertTrue(counts.tests().contains(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
        assertTrue(counts.tests().contains(new SandboxResult.TestCase("[juego] ESC_03_pierde", "FAILED", "paso 2: falló la aserción: game over")));
    }

    // Verificado en vivo (Pong, 2026-10-15): sin el paso y el motivo, el equipo corrigió a ciegas las mismas 4 partidas
    // durante 3 rondas. System.Text.Json escapa los no ASCII (\u00F3) y las comillas (\u0022).
    @Test
    void aFailedPlaythroughCarriesItsStepAndMessage() throws Exception {
        Files.createDirectories(work.resolve(".forjai"));
        Files.writeString(work.resolve(".forjai/playthroughs.json"),
                "[{\"name\":\"ESC_01_mueve\",\"outcome\":\"PASSED\",\"failedStep\":null,\"message\":null},"
                + "{\"name\":\"ESC_03_rebota\",\"outcome\":\"FAILED\",\"failedStep\":\"paso 4\","
                + "\"message\":\"fall\\u00F3 la aserci\\u00F3n: \\u0022la pelota rebota\\u0022\"}]");

        var tests = TestReportParser.parse(work).tests();

        assertEquals(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED"), tests.get(0));
        assertEquals(new SandboxResult.TestCase("[juego] ESC_03_rebota", "FAILED",
                "paso 4: falló la aserción: \"la pelota rebota\""), tests.get(1));
    }

    @Test
    void theFailedPlaythroughsAreListedForTheOutput() {
        var tests = List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED"),
                new SandboxResult.TestCase("[juego] ESC_03_rebota", "FAILED", "paso 4: falló la aserción: rebota"),
                new SandboxResult.TestCase("Pong.Tests.X", "FAILED"));

        assertEquals("\nPartidas simuladas fallidas (paso y motivo):\n- [juego] ESC_03_rebota: paso 4: falló la aserción: rebota\n",
                TestReportParser.failureDetails(tests));
        assertEquals("", TestReportParser.failureDetails(List.of(tests.get(0))));
    }

    @Test
    void aMissingOrCorruptPlaythroughReportAddsNothing() throws Exception {
        Files.createDirectories(work.resolve(".forjai"));
        Files.writeString(work.resolve(".forjai/results.trx"), """
                <TestRun><Results><UnitTestResult testName="A.B.C" outcome="Passed" /></Results>
                <ResultSummary><Counters total="1" passed="1" failed="0" /></ResultSummary></TestRun>""");
        assertEquals(1, TestReportParser.parse(work).tests().size());
        Files.writeString(work.resolve(".forjai/playthroughs.json"), "[{\"name\":");
        var counts = TestReportParser.parse(work);
        assertEquals(1, counts.tests().size());
        assertEquals(1, counts.passed());
    }
}
