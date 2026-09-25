package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentPathValidationGateTest {

    private final DevelopmentPathValidationGate gate = new DevelopmentPathValidationGate();

    private DevelopmentResult resultWithPath(String path) {
        return new DevelopmentResult("resumen", List.of(new DevelopmentResult.GeneratedFile(path, "contenido")));
    }

    @Test
    void acceptsARealRelativePath() {
        assertTrue(gate.validate(resultWithPath("src/backend/Program.cs")).valid());
    }

    @Test
    void rejectsAnAbsolutePath() {
        assertFalse(gate.validate(resultWithPath("/etc/passwd")).valid());
    }

    @Test
    void rejectsAWindowsStyleAbsolutePath() {
        assertFalse(gate.validate(resultWithPath("C:\\Windows\\System32\\evil.dll")).valid());
    }

    @Test
    void rejectsPathTraversal() {
        assertFalse(gate.validate(resultWithPath("../../etc/passwd")).valid());
        assertFalse(gate.validate(resultWithPath("src/../../secrets.txt")).valid());
    }

    @Test
    void rejectsABlankPath() {
        assertFalse(gate.validate(resultWithPath("   ")).valid());
    }

    // Review Focus: una ruta dentro de .git podría reescribir la config o los hooks del repo.
    @Test
    void rejectsPathsInsideGitInternals() {
        assertFalse(gate.validate(resultWithPath(".git/config")).valid());
        assertFalse(gate.validate(resultWithPath("src/.git/hooks/pre-commit")).valid());
    }

    @Test
    void treatsANullResultAsValid() {
        assertTrue(gate.validate(null).valid());
    }
}
