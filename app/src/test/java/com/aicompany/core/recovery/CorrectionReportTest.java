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

    // Revisión final (5): solo el reporte que sale de una validación pide estrategia.
    @Test void onlyAReportFromAValidationIsMarkedAsSuch() {
        assertFalse(CorrectionReport.from(Optional.empty(), "falló").fromValidation());
        assertTrue(new CorrectionReport("t", 1, List.of("x")).fromValidation());
    }

    @Test void countsEachDistinctCompileErrorOfAFailedBuild() {
        var tail = "  Domain -> /work/backend/src/Domain/bin/Domain.dll\n"
                + "/work/src/Application/Orders/CreateOrder.cs(12,17): error CS0246: The type or namespace name 'IOrderRepository' "
                + "could not be found (are you missing a using directive or an assembly reference?) [/work/src/Application/Application.csproj]\n"
                + "/work/src/Api/Program.cs(5,1): error CS0103: The name 'builder' does not exist in the current context [/work/src/Api/Api.csproj]\n"
                + "/work/src/Api/Program.cs(7,9): warning CS8618: Non-nullable property\n"
                + "Build FAILED.\n"
                + "/work/src/Application/Orders/CreateOrder.cs(12,17): error CS0246: The type or namespace name 'IOrderRepository' "
                + "could not be found (are you missing a using directive or an assembly reference?) [/work/src/Application/Application.csproj]\n"
                + "    2 Error(s)\n";
        var sandbox = "{\"overall\":\"FAIL\",\"steps\":[{\"name\":\"backend/restore\",\"status\":\"PASS\",\"exitCode\":0,\"durationMs\":1,"
                + "\"outputTail\":\"ok\",\"testsPassed\":0,\"testsFailed\":0},{\"name\":\"backend/build\",\"status\":\"FAIL\","
                + "\"exitCode\":1,\"durationMs\":1,\"outputTail\":" + tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(tail) + ",\"testsPassed\":0,\"testsFailed\":0}]}";

        var report = CorrectionReport.from(Optional.of(validation("[]", sandbox, "")), "");

        assertEquals(2, report.failing(), report.text());
        assertTrue(report.text().contains("backend/src/Application/Orders/CreateOrder.cs:12 CS0246"), report.text());
        assertTrue(report.text().contains("backend/src/Api/Program.cs:5 CS0103 The name 'builder' does not exist"), report.text());
        assertFalse(report.text().contains("CS8618"), report.text());
    }

    private static String buildFailure(String tail) {
        return "{\"overall\":\"FAIL\",\"steps\":[{\"name\":\"build\",\"status\":\"FAIL\",\"exitCode\":1,\"durationMs\":1,"
                + "\"outputTail\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(tail)
                + ",\"testsPassed\":0,\"testsFailed\":0}]}";
    }

    // Pong de 3 niveles (2026-10-17): Vera dejó el mismo Do(...) de 2 argumentos de R16 a R17.
    @Test void compileErrorsRepeatedFromThePreviousRoundAreMarked() {
        var doError = "/work/game/playthroughs/ESC_07_Anotar.cs(162,22): error CS1501: No overload for method 'Do' takes 2 "
                + "arguments [/work/game/Game.csproj]\n";
        var previous = CorrectionReport.from(Optional.of(validation("[]", buildFailure(doError
                + "/work/game/Main.cs(134,9): error CS0165: Use of unassigned local variable 'pelotasLista' [/work/game/Game.csproj]\n"),
                "")), "");
        // En la ronda siguiente el Do se corrió de línea (sigue igual) y aparece un error nuevo.
        var current = CorrectionReport.from(Optional.of(validation("[]", buildFailure(doError.replace("162,22", "170,22")
                + "/work/tests/Pong.Tests/ESC_08.cs(145,5): error CS1503: Argument 3: cannot convert from 'string' [/work/x.csproj]\n"),
                "")), "");

        var marked = current.withRepeated(previous.failures());

        assertTrue(marked.text().startsWith(CorrectionReport.HEADER + ":\nATENCIÓN: 1 error(es) de compilación se repiten"),
                marked.text());
        assertTrue(marked.text().contains("ESC_07_Anotar.cs:170 CS1501 No overload for method 'Do' takes 2 arguments"
                + CorrectionReport.REPEATED_MARK), marked.text());
        assertFalse(marked.text().contains("CS1503 Argument 3: cannot convert from 'string'" + CorrectionReport.REPEATED_MARK),
                marked.text());
        assertEquals(current.failures(), marked.failures());
        assertEquals(current.failing(), marked.failing());
        assertSame(current, current.withRepeated(List.of()));
        assertSame(current, current.withRepeated(List.of("otra cosa")));
    }

    @Test void aFailedBuildWithoutReadableErrorsCountsAsOne() {
        var sandbox = "{\"overall\":\"FAIL\",\"steps\":[{\"name\":\"build\",\"status\":\"FAIL\",\"exitCode\":1,\"durationMs\":1,"
                + "\"outputTail\":\"MSBUILD : error MSB1009: Project file does not exist.\",\"testsPassed\":0,\"testsFailed\":0}]}";
        var report = CorrectionReport.from(Optional.of(validation("[]", sandbox, "")), "");
        assertEquals(1, report.failing(), report.text());
        assertTrue(report.text().contains("MSB1009"), report.text());
    }

    @Test void eachFailureIsListedAndFingerprintsIgnoreLineNumbersAndPaths() {
        var tail = "/work/src/A.cs(12,5): error CS0246: The type 'Foo' could not be found [/work/src/A.csproj]\n"
                + "/work/src/A.cs(40,9): error CS0246: The type 'Bar' could not be found [/work/src/A.csproj]\n";
        var sandbox = "{\"overall\":\"FAIL\",\"steps\":[{\"name\":\"build\",\"status\":\"FAIL\",\"exitCode\":1,\"durationMs\":1,"
                + "\"outputTail\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(tail)
                + ",\"testsPassed\":0,\"testsFailed\":0}]}";
        var report = CorrectionReport.from(Optional.of(validation(
                "[{\"check\":\"DDD_LAYERS\",\"status\":\"FAIL\",\"detail\":\"domain importa infrastructure\"}]", sandbox, "")), "");
        assertEquals(3, report.failing());
        assertEquals(report.failing(), report.failures().size());
        assertEquals(CorrectionReport.fingerprint("src/A.cs(12,5): error CS0246: The type 'Foo' could not be found"),
                CorrectionReport.fingerprint("src/A.cs(40,9): error CS0246: The type 'Foo' could not be found"));
        assertEquals(CorrectionReport.fingerprint("src/A.cs:12 CS0246 x in /tmp/job-123/work"),
                CorrectionReport.fingerprint("src/A.cs:99 CS0246 x in /tmp/job-456/work"));
        assertNotEquals(CorrectionReport.fingerprint("error CS0246: 'Foo'"), CorrectionReport.fingerprint("error CS0103: 'Foo'"));
    }

    @Test void fallbacksCountAsOneFailureEach() {
        assertEquals(1, CorrectionReport.from(Optional.empty(), "x").failures().size());
        assertEquals(1, CorrectionReport.from(Optional.of(validation("[]", null, "")), "").failures().size());
    }
}
