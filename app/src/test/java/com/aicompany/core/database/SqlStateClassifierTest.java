package com.aicompany.core.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seguimiento Diego ambientes (§2): tabla SQLState → problema del ambiente. */
class SqlStateClassifierTest {

    @Test
    void infrastructureStatesAreEnvironmentProblems() {
        for (var s : new String[]{"08006", "08001", "28P01", "28000", "53300", "53200", "57014", "57P01", "58030",
                "55P03", "42501", "40001", "40P01"}) {
            assertTrue(SqlStateClassifier.isEnvironmentProblem(s), s);
        }
    }

    @Test
    void unknownStateIsAnEnvironmentProblem() {
        assertTrue(SqlStateClassifier.isEnvironmentProblem(null));
        assertTrue(SqlStateClassifier.isEnvironmentProblem(" "));
    }

    @Test
    void genuineSqlErrorsRemainCorrectable() {
        for (var s : new String[]{"42601", "42P01", "42703", "42P07", "23505", "23502", "22P02", "22001"}) {
            assertFalse(SqlStateClassifier.isEnvironmentProblem(s), s);
        }
    }
}
