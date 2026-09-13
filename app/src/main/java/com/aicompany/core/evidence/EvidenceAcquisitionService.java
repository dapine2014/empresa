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
 * evitar. La verificación semántica del contenido queda pendiente (ver
 * EMPRESA_AI_NUEVO_TODO_EVIDENCE.md §11, §16) — no implementada aquí.
 *
 * Todavía NO está conectado a {@code AgentRuntime}/tool-calling de Ollama:
 * requiere una API key de búsqueda real (`EVIDENCE_WEB_SEARCH_API_KEY`)
 * que no estaba disponible al construir esto. Es un módulo standalone,
 * probado de forma aislada.
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
     * Confirma que la URL de un candidato existe y responde con contenido.
     * NO confirma que el contenido respalde el {@code claim} — por eso el
     * resultado sigue con {@code verified=false}. Fuente recuperable ≠
     * afirmación verificada.
     */
    public AgentResult.Evidence confirmReachable(EvidenceCandidate candidate) {

        var content = pageFetcher.fetch(candidate.url());

        if (content.isBlank()) {
            throw new IllegalStateException(
                    "La URL no devolvió contenido: " + candidate.url());
        }

        return new AgentResult.Evidence(
                candidate.claim()
                        + " — fuente accesible: "
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
