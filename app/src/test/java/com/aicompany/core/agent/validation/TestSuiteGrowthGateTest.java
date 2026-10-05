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
}
