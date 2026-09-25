package com.aicompany.core.agent.model;

import java.util.List;

/**
 * Contrato de una tarea de DESARROLLO real — separado de {@link AgentResult}
 * (discovery). Ver docs/superpowers/specs/2026-09-21-development-generation-design.md §6.
 */
public record DevelopmentResult(
        String summary,
        List<GeneratedFile> files
) {
    public record GeneratedFile(String path, String content) {
    }
}
