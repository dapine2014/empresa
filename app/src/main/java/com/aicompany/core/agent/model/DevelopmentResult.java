package com.aicompany.core.agent.model;

import java.util.List;

/**
 * Contrato de una tarea de DESARROLLO real — separado de {@link AgentResult}
 * (discovery). Ver docs/superpowers/specs/2026-09-21-development-generation-design.md §6.
 */
public record DevelopmentResult(
        String summary,
        List<GeneratedFile> files,
        List<PackageRequest> packages,
        Boolean complete,
        List<String> remainingPaths
) {
    public DevelopmentResult(String summary, List<GeneratedFile> files) {
        this(summary, files, List.of(), null, List.of());
    }

    public DevelopmentResult(String summary, List<GeneratedFile> files, List<PackageRequest> packages) {
        this(summary, files, packages, null, List.of());
    }

    /** Spec 2026-10-01 §5 (entrega por lotes): sin el campo, la respuesta cuenta como completa (compatibilidad). */
    public boolean isComplete() {
        return complete == null || complete;
    }

    public List<String> remainingPathsOrEmpty() {
        return remainingPaths == null ? List.of() : remainingPaths;
    }

    public List<PackageRequest> packagesOrEmpty() {
        return packages == null ? List.of() : packages;
    }

    public record GeneratedFile(String path, String content) {
    }

    /** Paquete NuGet que el agente necesita (parte 3): solo versión exacta; Forjai lo agrega al .csproj de su capa. */
    public record PackageRequest(String name, String version) {
    }
}
