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

    @Test
    void aMajorFindingWithNoEvidentIssuesVerdictStaysValidated() {
        var review = new StaticReviewResult("NO_EVIDENT_ISSUES",
                List.of(new StaticReviewResult.Finding("web/game.js", "MAJOR", "detalle")), List.of(), "coherente",
                List.of("No se puede verificar la ejecución."), List.of());
        assertEquals(StaticValidationStatus.STATICALLY_VALIDATED, StaticValidationStatus.compute(ALL_PASS, review));
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
    void onlyEngineeringUsesTheDevelopmentMode() {
        assertEquals(TeamExecutionMode.DEVELOPMENT, TeamExecutionMode.forTeamType("ENGINEERING"));
        assertEquals(TeamExecutionMode.ANALYSIS, TeamExecutionMode.forTeamType("MARKETING_GROWTH"));
        assertEquals(TeamExecutionMode.ANALYSIS, TeamExecutionMode.forTeamType("CREATIVE_PRODUCT_INTELLIGENCE"));
    }
}
