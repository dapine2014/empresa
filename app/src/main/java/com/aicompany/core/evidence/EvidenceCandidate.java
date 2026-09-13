package com.aicompany.core.evidence;

/**
 * Un resultado de búsqueda web, todavía sin verificar. Deliberadamente
 * distinto de {@link com.aicompany.core.agent.model.AgentResult.Evidence}:
 * un candidato no tiene "verified" porque nada garantiza todavía que la
 * URL exista, responda, o respalde el {@code claim} — encontrar una URL
 * no es lo mismo que tener evidencia (ver EMPRESA_AI_NUEVO_TODO_EVIDENCE.md
 * §12).
 */
public record EvidenceCandidate(
        String claim,
        String url,
        String title,
        String snippet,
        String sourceType
) {
}
