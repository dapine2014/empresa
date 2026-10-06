package com.aicompany.core.recovery;

import com.aicompany.core.agent.model.StrategyProposal;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import com.aicompany.core.evidence.WebSearchPort;
import com.aicompany.core.prospecting.PageDigest;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spec misiones durables §3 (revisión 2026-10-06): cuando una misión no avanza, Java elige el error persistente, lo busca
 * en internet y lee las páginas; Neo propone una estrategia (format, sin tools) y Java exige que cite una página leída y
 * que no repita una estrategia ya intentada para ese error. Nunca lanza: sin estrategia válida, la ronda sigue sin ella.
 */
@Service
public class CorrectionStrategist {

    private static final Logger log = LoggerFactory.getLogger(CorrectionStrategist.class);
    static final int MAX_RESULTS = 5, MAX_PAGES = 3, MAX_ATTEMPTS = 3, EXCERPT_CHARS = PageDigest.MAX_EXCERPT;
    private static final String AGENT = "engineering";

    private final WebSearchPort search;
    private final EvidenceAcquisitionService pageReader;
    private final CeoService ceo;
    private final RecoveryMemoryService recovery;
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final String defaultModel;

    public CorrectionStrategist(WebSearchPort search, EvidenceAcquisitionService pageReader, CeoService ceo,
                                RecoveryMemoryService recovery, CompanyMemoryService companyMemory,
                                PromptMemoryService promptMemory, @Value("${ollama.agent-model}") String defaultModel) {
        this.search = search;
        this.pageReader = pageReader;
        this.ceo = ceo;
        this.recovery = recovery;
        this.companyMemory = companyMemory;
        this.promptMemory = promptMemory;
        this.defaultModel = defaultModel;
    }

    public Optional<CorrectionStrategy> propose(String missionId, int round, CorrectionReport current,
                                                List<CorrectionReport> previous, String stack) {
        try {
            var failure = persistentFailure(current, previous);
            if (failure == null) {
                return Optional.empty();
            }
            var fingerprint = CorrectionReport.fingerprint(failure);
            var tried = recovery.strategies(missionId).stream().filter(s -> s.fingerprint().equals(fingerprint)).toList();
            var pages = readPages(searchQuery(failure, stack));
            if (pages.isEmpty()) {
                return Optional.empty();
            }
            String correction = null;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                var proposal = ceo.proposeCorrectionStrategy(AGENT, prompt(failure, tried, pages, correction),
                        promptMemory.activePrompt(AGENT), companyMemory.agentModel(AGENT, defaultModel));
                var errors = errors(proposal, pages.keySet(), tried);
                if (errors.isEmpty()) {
                    return Optional.of(new CorrectionStrategy(fingerprint, round, proposal.strategyOrEmpty().strip(),
                            proposal.sourcesOrEmpty().stream().filter(pages::containsKey).distinct().toList()));
                }
                correction = String.join("\n", errors);
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("STRATEGY - {} ronda {}: {}", missionId, round, ex.getMessage());
            return Optional.empty();
        }
    }

    /** La falla que se repite en las 2 últimas rondas y en la actual; si ninguna, la primera de la actual. */
    static String persistentFailure(CorrectionReport current, List<CorrectionReport> previous) {
        if (current == null || current.failures().isEmpty()) {
            return null;
        }
        var recent = previous.subList(Math.max(0, previous.size() - 2), previous.size());
        String best = null;
        long bestHits = -1;
        for (var failure : current.failures()) {
            var fp = CorrectionReport.fingerprint(failure);
            long hits = recent.stream().filter(r -> r.failures().stream()
                    .anyMatch(f -> CorrectionReport.fingerprint(f).equals(fp))).count();
            if (hits > bestHits) {
                best = failure;
                bestHits = hits;
            }
        }
        return best;
    }

    /** Mensaje y código del error sin rutas ni números de línea, más el stack. */
    static String searchQuery(String failure, String stack) {
        var clean = failure
                .replaceAll("[\\w./\\\\:-]+\\.(cs|csproj|dart|yaml|sln)\\b(\\(\\d+,\\d+\\))?(:\\d+(:\\d+)?)?", " ")
                .replaceAll("\\(\\d+,\\d+\\)", " ")
                .replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("\\s+", " ").strip();
        if (clean.length() > 200) {
            clean = clean.substring(0, 200);
        }
        return (clean + " " + (stack == null ? "" : stack)).strip();
    }

    private Map<String, String> readPages(String query) {
        var pages = new LinkedHashMap<String, String>();
        for (var result : search.search(query, null, "en", MAX_RESULTS)) {
            if (pages.size() >= MAX_PAGES) {
                break;
            }
            try {
                var excerpt = PageDigest.of(pageReader.fetchPage(result.url()), result.url()).excerpt();
                if (!excerpt.isBlank()) {
                    pages.put(result.url(), excerpt);
                }
            } catch (Exception ex) {
                log.debug("STRATEGY - página ilegible {}: {}", result.url(), ex.getMessage());
            }
        }
        return pages;
    }

    private static List<String> errors(StrategyProposal proposal, Set<String> readUrls, List<CorrectionStrategy> tried) {
        var errors = new ArrayList<String>();
        if (proposal == null || proposal.strategyOrEmpty().isBlank()) {
            errors.add("- La estrategia está vacía.");
            return errors;
        }
        var sources = proposal.sourcesOrEmpty();
        if (sources.isEmpty()) {
            errors.add("- Cita al menos una URL de las páginas de arriba en sources.");
        }
        for (var source : sources) {
            if (!readUrls.contains(source)) {
                errors.add("- La fuente " + source + " no es una de las páginas leídas; cita solo las URL exactas de arriba.");
            }
        }
        var normalized = normalize(proposal.strategy());
        if (tried.stream().anyMatch(t -> normalize(t.strategy()).equals(normalized))) {
            errors.add("- Esa estrategia ya se intentó para este error; propone una distinta.");
        }
        return errors;
    }

    static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static String prompt(String failure, List<CorrectionStrategy> tried, Map<String, String> pages,
                                 String correction) {
        var sb = new StringBuilder("""
                Una misión de desarrollo no avanza: el mismo error se repite en varias rondas de corrección.
                Propón UNA estrategia concreta y distinta para resolverlo. Usa SOLO las páginas de abajo, que Java leyó
                de internet; cita sus URLs exactas en "sources". No inventes fuentes ni APIs que las páginas no muestren.

                ERROR PERSISTENTE:
                """).append(failure).append("\n\n");
        if (!tried.isEmpty()) {
            sb.append("ESTRATEGIAS YA INTENTADAS PARA ESTE ERROR (no las repitas):\n");
            tried.forEach(t -> sb.append("- ").append(t.strategy()).append('\n'));
            sb.append('\n');
        }
        sb.append("PÁGINAS LEÍDAS:\n");
        pages.forEach((url, text) -> sb.append("URL: ").append(url).append('\n').append(text).append("\n\n"));
        if (correction != null) {
            sb.append("CORRECCIÓN DEL INTENTO ANTERIOR:\n").append(correction).append('\n');
        }
        return sb.toString();
    }
}
