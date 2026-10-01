package com.aicompany.core.model;

import com.aicompany.core.agent.model.StaticReviewResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaticValidationStatusTest {

    private static StaticReviewResult review(String severity) {
        var findings = severity == null
                ? List.<StaticReviewResult.Finding>of()
                : List.of(new StaticReviewResult.Finding("web/game.js", severity, "detalle"));
        return new StaticReviewResult("ISSUES_FOUND", findings, List.of(), "coherente",
                List.of("No se puede verificar la ejecución."), List.of());
    }

    private static final List<StaticCheck> ALL_PASS = List.of(
            StaticCheck.pass("COMMIT_EXISTS", "ok", "abc", List.of()),
            StaticCheck.pass("ENTRY_POINT", "ok", null, List.of("web/index.html"))
    );

    @Test
    void staticallyValidatedWhenChecksPassAndReviewOnlyHasMinorFindings() {
        assertEquals(StaticValidationStatus.STATICALLY_VALIDATED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR")));
    }

    // Decisión del fundador (verificado en vivo con MISSION-TEAM-VERIFY-2): ISSUES_FOUND con
    // hallazgos MAJOR no puede quedar como STATICALLY_VALIDATED.
    @Test
    void failedWhenReviewFindsIssuesWithAMajorFinding() {
        assertEquals(StaticValidationStatus.FAILED,
                StaticValidationStatus.compute(ALL_PASS, review("MAJOR")));
    }

    // Decisión del fundador: cualquier MAJOR hace fallar la validación, sin importar el verdict.
    @Test
    void anyMajorFindingFailsEvenWithNoEvidentIssuesVerdict() {
        var review = new StaticReviewResult("NO_EVIDENT_ISSUES",
                List.of(new StaticReviewResult.Finding("web/game.js", "MAJOR", "detalle")), List.of(), "coherente",
                List.of("No se puede verificar la ejecución."), List.of());
        assertEquals(StaticValidationStatus.FAILED, StaticValidationStatus.compute(ALL_PASS, review));
    }

    @Test
    void failedWhenAnyDeterministicCheckFails() {
        var checks = List.of(
                StaticCheck.pass("COMMIT_EXISTS", "ok", "abc", List.of()),
                StaticCheck.fail("ENTRY_POINT", "no existe", null, List.of("web/index.html"))
        );
        assertEquals(StaticValidationStatus.FAILED, StaticValidationStatus.compute(checks, review(null)));
    }

    @Test
    void failedWhenReviewReportsABlocker() {
        assertEquals(StaticValidationStatus.FAILED,
                StaticValidationStatus.compute(ALL_PASS, review("BLOCKER")));
    }

    @Test
    void unvalidatedWhenChecksPassButReviewIsMissing() {
        assertEquals(StaticValidationStatus.UNVALIDATED, StaticValidationStatus.compute(ALL_PASS, null));
    }

    @Test
    void failedWhenThereAreNoChecksAtAll() {
        assertEquals(StaticValidationStatus.FAILED, StaticValidationStatus.compute(List.of(), review(null)));
    }

    @Test
    void onlyTheDevelopmentGroupUsesTheDevelopmentMode() {
        assertEquals(TeamExecutionMode.DEVELOPMENT, TeamExecutionMode.forTeamType("DEVELOPMENT"));
        assertEquals(TeamExecutionMode.ANALYSIS, TeamExecutionMode.forTeamType("ENGINEERING"));
        assertEquals(TeamExecutionMode.ANALYSIS, TeamExecutionMode.forTeamType("MARKETING_GROWTH"));
        assertEquals(TeamExecutionMode.ANALYSIS, TeamExecutionMode.forTeamType("CREATIVE_PRODUCT_INTELLIGENCE"));
    }

    private static SandboxResult sandbox(String overall, int passed) {
        return new SandboxResult(overall, List.of(
                new SandboxResult.StepResult("build", "PASS", 0, 1000, "", 0, 0),
                new SandboxResult.StepResult("test", overall, 0, 1000, "", passed, 0)));
    }

    @Test
    void verifiedWhenEverythingPassesWithAtLeastOneTest() {
        assertEquals(StaticValidationStatus.VERIFIED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR"), sandbox("PASS", 3)));
    }

    @Test
    void zeroTestsIsNotVerified() {
        assertEquals(StaticValidationStatus.FAILED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR"), sandbox("PASS", 0)));
    }

    @Test
    void aFailedSandboxFails() {
        assertEquals(StaticValidationStatus.FAILED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR"), sandbox("FAIL", 2)));
    }

    @Test
    void aMissingSandboxIsUnvalidated() {
        assertEquals(StaticValidationStatus.UNVALIDATED,
                StaticValidationStatus.compute(ALL_PASS, review("MINOR"), null));
    }

    // Decisión del fundador (2026-09-26, tras MISSION-SANDBOX-VERIFY-14): con ejecución real exitosa, un MAJOR de
    // la revisión queda como deuda de diseño reportada; solo un BLOCKER impide VERIFIED.
    @Test
    void withAPassingSandboxOnlyABlockerPreventsVerified() {
        assertEquals(StaticValidationStatus.VERIFIED,
                StaticValidationStatus.compute(ALL_PASS, review("MAJOR"), sandbox("PASS", 3)));
        assertEquals(StaticValidationStatus.FAILED,
                StaticValidationStatus.compute(ALL_PASS, review("BLOCKER"), sandbox("PASS", 3)));
    }
}
