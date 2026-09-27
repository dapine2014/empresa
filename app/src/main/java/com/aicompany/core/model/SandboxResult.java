package com.aicompany.core.model;

import java.util.List;

/** Resultado del sandbox-runner (mismo JSON que sandbox-runner/SandboxResult). */
public record SandboxResult(String overall, List<StepResult> steps) {

    public record StepResult(String name, String status, int exitCode, long durationMs, String outputTail,
                             int testsPassed, int testsFailed) {
    }

    public List<StepResult> stepsOrEmpty() {
        return steps == null ? List.of() : steps;
    }

    public int testsPassed() {
        return stepsOrEmpty().stream().mapToInt(StepResult::testsPassed).sum();
    }

    public int testsFailed() {
        return stepsOrEmpty().stream().mapToInt(StepResult::testsFailed).sum();
    }

    public boolean passed() {
        return "PASS".equals(overall) && stepsOrEmpty().stream().allMatch(s -> "PASS".equals(s.status()));
    }
}
