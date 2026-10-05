package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        var counts = TestReportParser.parse(work);
        assertEquals(1, counts.passed());
        assertEquals(1, counts.failed());
    }

    // Review Focus: si el paso falló antes de escribir el reporte, el conteo es 0/0, sin excepción.
    @Test
    void aMissingReportCountsAsZero() {
        assertEquals(new TestReportParser.TestCounts(0, 0), TestReportParser.parse(work));
    }
    // QA casos de uso (2026-10-05): cada test con su nombre y resultado; los Trait del TRX se suman al nombre.
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
                new SandboxResult.TestCase("Crm.Tests.ClientesTests.Lista [ESC-02]", "FAILED"),
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
}
