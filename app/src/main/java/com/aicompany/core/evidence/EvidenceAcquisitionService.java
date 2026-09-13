package com.aicompany.core.evidence;

import com.aicompany.core.agent.model.AgentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orquesta búsqueda + recuperación de URLs. Deliberadamente NO produce
 * {@code AgentResult.Evidence} con {@code verified=true} solo porque una
 * URL respondió — eso confundiría "la fuente es accesible" con "el dato
 * concreto está verificado", que es exactamente lo que el resto del
 * proyecto (AgentResultValidator, EvidenceValidationGate) existe para
 * evitar.
 *
 * Verificación semántica: {@code confirmReachable} usa
 * {@link ClaimRelevanceChecker} (heurística léxica determinista, sin
 * modelo) para descartar fuentes que respondieron pero no tienen
 * relación con lo buscado. No confirma que el dato concreto esté en la
 * página (para eso haría falta NLP/extracción real, ver
 * EMPRESA_AI_NUEVO_TODO_EVIDENCE.md §11, §16 — sigue sin implementarse) —
 * solo que la fuente al menos habla del tema.
 *
 * Conectado a {@code AgentRuntime}/tool-calling de Ollama vía
 * {@code CeoService.executeAgentTask}: cuando el modelo pide
 * {@code search_web_evidence}, {@code CeoService.executeTool} llama a
 * {@code searchEvidence} para buscar y luego a {@code confirmReachable}
 * sobre cada candidato antes de devolverle nada al modelo — los
 * candidatos que no responden o no están relacionados con la consulta se
 * descartan ahí mismo, nunca llegan al turno final. Ver "Evidence
 * Acquisition" en CLAUDE.md para el diseño de dos turnos.
 */
@Service
public class EvidenceAcquisitionService {

    private final WebSearchPort searchPort;
    private final WebPageFetcher pageFetcher;
    private final String defaultCountry;
    private final String defaultLanguage;

    public EvidenceAcquisitionService(
            WebSearchPort searchPort,
            WebPageFetcher pageFetcher,
            @Value("${evidence.web-search.country:CO}") String defaultCountry,
            @Value("${evidence.web-search.language:es}") String defaultLanguage) {

        this.searchPort = searchPort;
        this.pageFetcher = pageFetcher;
        this.defaultCountry = defaultCountry;
        this.defaultLanguage = defaultLanguage;
    }

    public List<EvidenceCandidate> searchEvidence(String query) {

        var results = searchPort.search(query, defaultCountry, defaultLanguage, 10);

        return results.stream()
                .map(result -> new EvidenceCandidate(
                        query,
                        result.url(),
                        result.title(),
                        safe(result.description()),
                        "WEB"
                ))
                .toList();
    }

    /**
     * Confirma que la URL de un candidato existe, responde con contenido,
     * y que ese contenido al menos **menciona** los términos de la
     * búsqueda ({@link ClaimRelevanceChecker} — heurística léxica
     * determinista, no NLP real). Sigue sin confirmar que el dato
     * concreto esté ahí — por eso el resultado sigue con
     * {@code verified=false} siempre. Fuente recuperable y relacionada ≠
     * afirmación verificada; pero fuente recuperable y **no** relacionada
     * ya no se acepta como evidencia (antes sí se aceptaba: la URL podía
     * ser sobre cualquier cosa, con tal de que respondiera).
     */
    public AgentResult.Evidence confirmReachable(EvidenceCandidate candidate) {

        var content = pageFetcher.fetch(candidate.url());

        if (content.isBlank()) {
            throw new IllegalStateException(
                    "La URL no devolvió contenido: " + candidate.url());
        }

        var relevance = ClaimRelevanceChecker.check(candidate.claim(), content);

        if (!relevance.supported()) {
            throw new IllegalStateException(
                    "El contenido de " + candidate.url()
                            + " no parece relacionado con \""
                            + candidate.claim()
                            + "\" (" + relevance.matchedTerms() + "/"
                            + relevance.totalTerms()
                            + " términos coincidieron) — no se usa como evidencia."
            );
        }

        return new AgentResult.Evidence(
                candidate.claim()
                        + " — fuente accesible y relacionada ("
                        + relevance.matchedTerms() + "/" + relevance.totalTerms()
                        + " términos): "
                        + safe(candidate.title()),
                candidate.url(),
                candidate.sourceType(),
                false
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
