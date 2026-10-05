package com.aicompany.core.agent.validation;

import com.aicompany.core.model.SandboxResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** QA casos de uso (2026-10-05): la suite solo crece; nada se borra, renombra ni salta. */
class TestSuiteGrowthGateTest {

    @Test void deletingRenamingOrSkippingATestIsRejected() {
        var before = TestInventory.of(Map.of("backend/tests/A.cs", "[Fact] public void ESC_01_x() {}\n[Fact] public void ESC_02_y() {}",
                "app/test/a_test.dart", "test('ESC-11 z', () {});"));
        var now = TestInventory.of(Map.of("backend/tests/A.cs", "[Fact] public void ESC_01_x() {}\n[Fact(Skip = \"luego\")] public void ESC_02_y() {}",
                "app/test/a_test.dart", "test('ESC-11 zz', () {});"));
        var errors = TestSuiteGrowthGate.errors(before, now);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC_02_y") && e.contains("saltado")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-11 z") && e.contains("borraste o renombraste")), errors.toString());
    }
    @Test void addingTestsOrHavingNoPreviousVerifiedIsFine() {
        var now = TestInventory.of(Map.of("app/test/a_test.dart", "test('ESC-11 z', () {});\ntest('ESC-12 w', () {});"));
        assertEquals(List.of(), TestSuiteGrowthGate.errors(TestInventory.of(Map.of("app/test/a_test.dart", "test('ESC-11 z', () {});")), now));
        assertEquals(List.of(), TestSuiteGrowthGate.errors(Map.of(), now));
    }
    @Test void aTestThatPassedAndNoLongerRunsFailsTheSandboxCheck() {
        var check = TestSuiteGrowthGate.check(List.of(new SandboxResult.TestCase("ESC-11 z", "PASSED")),
                List.of(new SandboxResult.TestCase("ESC-12 w", "PASSED")));
        assertFalse(check.passed());
        assertTrue(check.detail().contains("ESC-11 z"), check.detail());
    }

    // Revisión final (I4): formas comunes de C# y Dart.
    @Test
    void theInventoryReadsCommonTestShapes() {
        var inventory = TestInventory.of(Map.of(
                "backend/tests/A.cs", "[Fact, Trait(\"Escenario\", \"ESC-03\")]\npublic async ValueTask ESC_03_a() {}\n"
                        + "[Theory]\n[InlineData(\"a;b{\")]\npublic void ESC_04_b(string s) {}\n"
                        + "[Fact(Skip = \"luego\")] public async Task<int> ESC_05_c() => 1;",
                "app/test/a_test.dart", "test('ESC-11 x', () { expect(1, 1); }, skip: true);\ntest('ESC-12 y', () { expect(1, 1); });"));
        assertEquals(java.util.Map.of("ESC_03_a", false, "ESC_04_b", false, "ESC_05_c", true, "ESC-11 x", true, "ESC-12 y", false),
                inventory);
    }

    // Revisión final (I3): corregir un InlineData, mover un test de clase o renombrar un grupo no es perder un test
    // mientras su escenario siga con un test aprobado.
    @Test
    void renamesThatKeepTheScenarioCoveredAreNotLosses() {
        var before = List.of(new SandboxResult.TestCase("Ns.A.ESC_06_t(x: 1)", "PASSED"),
                new SandboxResult.TestCase("Clientes ESC-11 crea", "PASSED"));
        var now = List.of(new SandboxResult.TestCase("Ns.B.ESC_06_t(x: 2)", "PASSED"),
                new SandboxResult.TestCase("Clientes v2 ESC-11 crea", "PASSED"));
        assertTrue(TestSuiteGrowthGate.check(before, now).passed());
        var lost = TestSuiteGrowthGate.check(List.of(new SandboxResult.TestCase("Ns.A.Sin_escenario", "PASSED")), now);
        assertFalse(lost.passed());
    }
}
