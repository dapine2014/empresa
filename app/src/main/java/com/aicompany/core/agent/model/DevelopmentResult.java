package com.aicompany.core.agent.model;

import java.util.List;

/**
 * Contrato de una tarea de DESARROLLO real — deliberadamente separado de
 * {@link AgentResult} (el contrato de discovery: hechos/hipótesis/
 * evidencia/cálculos no tienen sentido para "escribir código"). Ver
 * docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 3.
 */
public record DevelopmentResult(
        String summary,
        List<GeneratedFile> files
) {
    public record GeneratedFile(String path, String content) {
    }
}
