package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detecta contradicciones entre los resultados de los distintos agentes de
 * una misión (o dentro de un mismo resultado) que {@link AgentResultValidator}
 * no puede ver porque valida cada {@link AgentResult} de forma aislada.
 *
 * No usa un modelo para "revisarse a sí mismo" (no confiable); son reglas
 * deterministas sobre los campos ya estructurados del contrato.
 */
@Component
public class ContradictionDetector {

    private static final double LOW_CONFIDENCE_FOR_VALIDATED = 0.5;
    private static final double HIGH_CONFIDENCE_FOR_NOT_VALIDATED = 0.85;
    private static final double CALCULATION_TOLERANCE = 0.000001;
    private static final double SEED_CAPITAL_MULTIPLE_THRESHOLD = 100.0;

    /**
     * Marcadores léxicos de incertidumbre/especulación. Si aparecen dentro
     * de {@code facts}, es señal de que el agente está declarando como
     * hecho algo que en realidad es una hipótesis o una estimación.
     */
    private static final List<String> HEDGE_MARKERS = List.of(
            "podría", "podrían", "podria", "podrian",
            "posiblemente", "probablemente",
            "se estima", "estimado", "estimada",
            "aproximadamente", "tal vez", "talvez",
            "quizás", "quizas", "asumiendo",
            "se espera", "presuntamente",
            "hipotéticamente", "hipoteticamente",
            "es posible que", "seguramente"
    );

    public List<String> detect(
            List<AgentResult> results,
            double seedCapitalUsd) {

        var contradictions = new ArrayList<String>();

        detectConfidenceStatusMismatch(results, contradictions);
        detectCalculationNameConflicts(results, contradictions);
        detectMagnitudeOutliers(results, seedCapitalUsd, contradictions);
        detectFactHypothesisBlending(results, contradictions);

        return contradictions;
    }

    /**
     * Dentro de un mismo {@link AgentResult}: el mismo enunciado no puede
     * estar a la vez en {@code facts} y en {@code hypotheses}/{@code estimates}
     * (contradicción directa), y {@code facts} no debería usar lenguaje de
     * cobertura típico de una hipótesis o estimación.
     */
    private void detectFactHypothesisBlending(
            List<AgentResult> results,
            List<String> contradictions) {

        for (var result : results) {

            if (result == null) {
                continue;
            }

            var facts = normalizedSet(result.facts());
            var speculative = normalizedSet(result.hypotheses());
            speculative.addAll(normalizedSet(result.estimates()));

            var overlap = new LinkedHashSet<>(facts);
            overlap.retainAll(speculative);

            if (!overlap.isEmpty()) {

                contradictions.add(
                        result.agent()
                                + ": el mismo enunciado aparece a la vez "
                                + "en facts y en hypotheses/estimates ("
                                + String.join("; ", overlap)
                                + ") — no puede ser hecho e hipótesis "
                                + "al mismo tiempo."
                );
            }

            if (result.facts() == null) {
                continue;
            }

            for (var fact : result.facts()) {

                if (fact == null) {
                    continue;
                }

                var lower = fact.toLowerCase(Locale.ROOT);

                var hedge = HEDGE_MARKERS.stream()
                        .filter(lower::contains)
                        .findFirst();

                if (hedge.isPresent()) {

                    contradictions.add(
                            result.agent()
                                    + ": facts contiene lenguaje de "
                                    + "hipótesis/estimación ('"
                                    + hedge.get()
                                    + "' en \""
                                    + fact
                                    + "\") — no debería declararse como hecho."
                    );
                }
            }
        }
    }

    private LinkedHashSet<String> normalizedSet(List<String> values) {

        var set = new LinkedHashSet<String>();

        if (values == null) {
            return set;
        }

        for (var value : values) {

            if (value != null && !value.isBlank()) {
                set.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }

        return set;
    }

    private void detectConfidenceStatusMismatch(
            List<AgentResult> results,
            List<String> contradictions) {

        for (var result : results) {

            if (result == null) {
                continue;
            }

            var status = result.verificationStatus();
            var confidence = result.confidence();

            if ("VALIDATED".equals(status)
                    && confidence < LOW_CONFIDENCE_FOR_VALIDATED) {

                contradictions.add(
                        result.agent()
                                + ": declara verificationStatus=VALIDATED "
                                + "con confidence baja ("
                                + confidence
                                + ") — una validación real no debería "
                                + "tener tan poca confianza."
                );
            }

            if ("NOT_VALIDATED".equals(status)
                    && confidence > HIGH_CONFIDENCE_FOR_NOT_VALIDATED) {

                contradictions.add(
                        result.agent()
                                + ": declara verificationStatus=NOT_VALIDATED "
                                + "pero con confidence muy alta ("
                                + confidence
                                + ") — revisar si en realidad hay evidencia "
                                + "que no se está declarando."
                );
            }
        }
    }

    private void detectCalculationNameConflicts(
            List<AgentResult> results,
            List<String> contradictions) {

        record NamedResult(String agent, double result) {
        }

        Map<String, List<NamedResult>> byName = new LinkedHashMap<>();

        for (var result : results) {

            if (result == null || result.calculations() == null) {
                continue;
            }

            for (var calculation : result.calculations()) {

                if (calculation == null || calculation.name() == null) {
                    continue;
                }

                var key = calculation.name()
                        .trim()
                        .toLowerCase(Locale.ROOT);

                if (key.isBlank()) {
                    continue;
                }

                byName.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new NamedResult(
                                result.agent(),
                                calculation.result()
                        ));
            }
        }

        for (var entry : byName.entrySet()) {

            var values = entry.getValue();

            if (values.size() < 2) {
                continue;
            }

            var first = values.get(0).result();

            var allEqual = values.stream()
                    .allMatch(v -> Math.abs(v.result() - first) < CALCULATION_TOLERANCE);

            if (!allEqual) {

                var detail = values.stream()
                        .map(v -> v.agent() + "=" + v.result())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");

                contradictions.add(
                        "Cálculo '"
                                + entry.getKey()
                                + "' reportado de forma inconsistente "
                                + "entre agentes: "
                                + detail
                );
            }
        }
    }

    private void detectMagnitudeOutliers(
            List<AgentResult> results,
            double seedCapitalUsd,
            List<String> contradictions) {

        if (seedCapitalUsd <= 0) {
            return;
        }

        var limit = seedCapitalUsd * SEED_CAPITAL_MULTIPLE_THRESHOLD;

        for (var result : results) {

            if (result == null || result.calculations() == null) {
                continue;
            }

            var hasVerifiedEvidence =
                    result.evidence() != null
                            && result.evidence().stream()
                            .anyMatch(e -> e != null && e.verified());

            if (hasVerifiedEvidence) {
                continue;
            }

            for (var calculation : result.calculations()) {

                if (calculation == null) {
                    continue;
                }

                if (Math.abs(calculation.result()) > limit) {

                    contradictions.add(
                            result.agent()
                                    + ": cálculo '"
                                    + calculation.name()
                                    + "' = "
                                    + calculation.result()
                                    + " excede en más de "
                                    + (int) SEED_CAPITAL_MULTIPLE_THRESHOLD
                                    + "x el capital semilla (US$"
                                    + seedCapitalUsd
                                    + ") sin evidencia verificada que lo respalde."
                    );
                }
            }
        }
    }
}
