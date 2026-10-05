package com.aicompany.core.agent.validation;

import com.aicompany.core.model.SandboxResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** QA casos de uso (2026-10-05): cada escenario de Aria cubierto por un test que corrió y pasó. */
class ScenarioCoverageTest {

    private static ScenarioCoverage.Scenario esc(String id) {
        return new ScenarioCoverage.Scenario(id, "título " + id, 1);
    }

    @Test void aTestNamingTwoScenariosCoversBoth() {
        var rows = ScenarioCoverage.compute(List.of(esc("ESC-03"), esc("ESC-04")),
                List.of(new SandboxResult.TestCase("Crm.ESC_03_y_ESC_04_flujo", "PASSED")));
        assertTrue(rows.stream().allMatch(ScenarioCoverage.Row::covered));
    }
    @Test void aSkippedOrFailedTestDoesNotCover() {
        var rows = ScenarioCoverage.compute(List.of(esc("ESC-11"), esc("ESC-12")), List.of(
                new SandboxResult.TestCase("ESC-11 a", "SKIPPED"), new SandboxResult.TestCase("ESC-12 b", "FAILED")));
        assertTrue(rows.stream().noneMatch(ScenarioCoverage.Row::covered));
        var check = ScenarioCoverage.check(rows);
        assertFalse(check.passed());
        assertTrue(check.detail().contains("0/2") && check.detail().contains("ESC-11"), check.detail());
    }
    @Test void oneComponentPassingIsEnough() {
        var rows = ScenarioCoverage.compute(List.of(esc("ESC-05")), List.of(
                new SandboxResult.TestCase("backend: ESC_05_x", "PASSED"), new SandboxResult.TestCase("ESC-05 app", "FAILED")));
        assertTrue(rows.get(0).covered());
        assertEquals(List.of("ESC-05 app"), rows.get(0).failing());
    }
    // Revisión final QA (M6): el reporte dice de qué componente es cada test y qué escenarios son del incremento en curso
    // (nuevos) o de uno anterior (heredados).
    @Test void theReportNamesTheComponentAndNewOrInheritedScenarios() {
        var sandbox = new SandboxResult("PASS", List.of(
                new SandboxResult.StepResult("backend/test", "PASS", 0, 1, "", 1, 0,
                        List.of(new SandboxResult.TestCase("Crm.ESC_01_crear", "PASSED"))),
                new SandboxResult.StepResult("app/test", "PASS", 0, 1, "", 1, 0,
                        List.of(new SandboxResult.TestCase("ESC-02 listar", "PASSED")))));
        var rows = ScenarioCoverage.compute(List.of(new ScenarioCoverage.Scenario("ESC-01", "crear", 1),
                new ScenarioCoverage.Scenario("ESC-02", "listar", 2)), ScenarioCoverage.componentTests(sandbox));

        var report = ScenarioCoverage.report(rows);

        assertTrue(report.contains("ESC-01 (incremento 1, heredado) crear: [backend] Crm.ESC_01_crear"), report);
        assertTrue(report.contains("ESC-02 (incremento 2, nuevo) listar: [app] ESC-02 listar"), report);
    }

    @Test void aSingleComponentKeepsTheTestNamesAsTheyAre() {
        var sandbox = new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 1, 0,
                List.of(new SandboxResult.TestCase("ESC_01_x", "PASSED")))));
        assertEquals(List.of(new SandboxResult.TestCase("ESC_01_x", "PASSED")), ScenarioCoverage.componentTests(sandbox));
    }

    @Test void sourceErrorsListMissingAndUnknownIds() {
        var files = Map.of("backend/tests/A.cs", "[Fact] public void ESC_01_x() {}", "app/test/a_test.dart", "test('ESC-09 z', () {});");
        var errors = ScenarioCoverage.sourceErrors(List.of(esc("ESC-01"), esc("ESC-02")), files, files);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-02")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-09") && e.contains("no existe")), errors.toString());
    }

    // Revisión final (C2): un id en un comentario o en un Trait no cuenta; solo en el nombre del test.
    @Test
    void onlyTestNamesCountAtGeneration() {
        var files = Map.of("backend/tests/A.cs", "// cubre ESC-02\n[Fact, Trait(\"Escenario\", \"ESC-02\")] public void Alta() {}\n"
                + "[Fact] public void ESC_01_x() {}");
        var errors = ScenarioCoverage.sourceErrors(List.of(esc("ESC-01"), esc("ESC-02")), files, files);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-02")), errors.toString());
        assertTrue(errors.stream().noneMatch(e -> e.contains("Trait")), errors.toString());
    }

    // Revisión final (M3): un id inexistente solo se reclama en lo que Vera devolvió en este intento.
    @Test
    void unknownIdsAreOnlyClaimedInTheReturnedFiles() {
        var head = Map.of("backend/tests/Viejo.cs", "[Fact] public void ESC_09_viejo() {}\n[Fact] public void ESC_01_x() {}");
        assertEquals(List.of(), ScenarioCoverage.sourceErrors(List.of(esc("ESC-01")), head, Map.of()));
    }

    // Revisión final (M1, M2): ids en medio del identificador; los parámetros de un Theory no cuentan.
    @Test
    void idsInsideIdentifiersCountButTheoryArgumentsDoNot() {
        assertEquals(java.util.Set.of("ESC-03", "ESC-04"), ScenarioIds.inTestName("Ns.C.Crear_ESC_03_ESC_04_x"));
        assertEquals(java.util.Set.of("ESC-06"), ScenarioIds.inTestName("Ns.C.ESC_06_t(s: \"ESC-09\")"));
        assertEquals(java.util.Set.of(), ScenarioIds.inTestName("Ns.C.ESC_031_x"));
    }
}
