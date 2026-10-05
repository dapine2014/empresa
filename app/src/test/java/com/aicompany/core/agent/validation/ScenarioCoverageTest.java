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
    @Test void sourceErrorsListMissingAndUnknownIds() {
        var errors = ScenarioCoverage.sourceErrors(List.of(esc("ESC-01"), esc("ESC-02")), Map.of(
                "backend/tests/A.cs", "[Fact] public void ESC_01_x() {}", "app/test/a_test.dart", "test('ESC-09 z', () {});"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-02")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-09") && e.contains("no existe")), errors.toString());
    }
}
