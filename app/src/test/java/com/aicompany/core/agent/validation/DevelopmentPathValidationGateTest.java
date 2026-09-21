package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopmentPathValidationGateTest {

    private final DevelopmentPathValidationGate gate = new DevelopmentPathValidationGate();

    private DevelopmentResult resultWithPath(String path) {
        return new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile(path, "contenido"))
        );
    }

    @Test
    void acceptsARealRelativePath() {
        var validation = gate.validate(resultWithPath("src/backend/Program.cs"));
        assertTrue(validation.valid());
        assertTrue(validation.errors().isEmpty());
    }

    @Test
    void rejectsAnAbsolutePath() {
        var validation = gate.validate(resultWithPath("/etc/passwd"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsAWindowsStyleAbsolutePath() {
        var validation = gate.validate(resultWithPath("C:\\Windows\\System32\\evil.dll"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsPathTraversal() {
        var validation = gate.validate(resultWithPath("../../etc/passwd"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsPathTraversalInTheMiddleOfThePath() {
        var validation = gate.validate(resultWithPath("src/../../secrets.txt"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsAPathWithAGitSegment() {
        var validation = gate.validate(resultWithPath("src/.git/hook.sh"));
        assertFalse(validation.valid());
    }

    @Test
    void rejectsABlankPath() {
        var validation = gate.validate(resultWithPath("   "));
        assertFalse(validation.valid());
    }

    @Test
    void acceptsAResultWithMultipleValidFiles() {
        var result = new DevelopmentResult(
                "resumen",
                List.of(
                        new DevelopmentResult.GeneratedFile("src/A.java", "contenido A"),
                        new DevelopmentResult.GeneratedFile("src/B.java", "contenido B")
                )
        );
        assertTrue(gate.validate(result).valid());
    }

    @Test
    void treatsANullResultAsValid() {
        assertTrue(gate.validate(null).valid());
    }
}
