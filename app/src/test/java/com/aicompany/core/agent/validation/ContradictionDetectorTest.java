package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContradictionDetectorTest {

    private final ContradictionDetector detector = new ContradictionDetector();

    @Test
    void acceptsCleanResultsWithoutContradictions() {
        var sales = result("sales", "NOT_VALIDATED", 0.4, List.of(), List.of());
        var finance = result(
                "finance",
                "NOT_VALIDATED",
                0.3,
                List.of(),
                List.of(new AgentResult.Calculation("margen", 100, 30, "SUBTRACT", 70))
        );

        var contradictions = detector.detect(List.of(sales, finance), 50, 100);

        assertTrue(contradictions.isEmpty(), () -> String.join(", ", contradictions));
    }

    @Test
    void detectsValidatedWithLowConfidence() {
        var result = result("finance", "VALIDATED", 0.2, List.of(), List.of());

        var contradictions = detector.detect(List.of(result), 50, 100);

        assertTrue(contradictions.stream()
                .anyMatch(c -> c.contains("VALIDATED") && c.contains("confidence baja")));
    }

    @Test
    void detectsNotValidatedWithHighConfidence() {
        var result = result("finance", "NOT_VALIDATED", 0.95, List.of(), List.of());

        var contradictions = detector.detect(List.of(result), 50, 100);

        assertTrue(contradictions.stream()
                .anyMatch(c -> c.contains("NOT_VALIDATED") && c.contains("confidence muy alta")));
    }

    @Test
    void detectsSameCalculationNameWithDifferentResultsAcrossAgents() {
        var finance = result(
                "finance",
                "NOT_VALIDATED",
                0.5,
                List.of(),
                List.of(new AgentResult.Calculation("costo total", 40, 10, "ADD", 50))
        );

        var product = result(
                "product",
                "NOT_VALIDATED",
                0.5,
                List.of(),
                List.of(new AgentResult.Calculation("Costo Total", 40, 10, "ADD", 90))
        );

        var contradictions = detector.detect(List.of(finance, product), 50, 100);

        assertTrue(contradictions.stream()
                .anyMatch(c -> c.contains("costo total") && c.contains("reportado de forma inconsistente")));
    }

    @Test
    void detectsCostFarBeyondSeedCapitalWithoutVerifiedEvidence() {
        // Reproduce el caso real documentado en EMPRESA_AI_TODO.md:
        // Costos: US$52.000, Ingresos: US$600, con capital semilla de US$50.
        var finance = result(
                "finance",
                "NOT_VALIDATED",
                0.4,
                List.of(),
                List.of(
                        new AgentResult.Calculation("costos", 52000, 0, "ADD", 52000),
                        new AgentResult.Calculation("ingresos", 600, 0, "ADD", 600)
                )
        );

        var contradictions = detector.detect(List.of(finance), 50, 100);

        assertTrue(contradictions.stream()
                .anyMatch(c -> c.contains("costos") && c.contains("52000.0") && c.contains("capital semilla")));
    }

    @Test
    void doesNotFlagLargeAmountWhenBackedByVerifiedEvidence() {
        var evidence = new AgentResult.Evidence(
                "Contrato firmado",
                "https://example.com/contrato/1",
                "CONTRACT",
                true
        );

        var finance = result(
                "finance",
                "PARTIALLY_VALIDATED",
                0.6,
                List.of(evidence),
                List.of(new AgentResult.Calculation("ingreso contrato", 10000, 0, "ADD", 10000))
        );

        var contradictions = detector.detect(List.of(finance), 50, 100);

        assertTrue(contradictions.isEmpty(), () -> String.join(", ", contradictions));
    }

    @Test
    void acceptsPlainFactsWithNoOverlapAndNoHedgeLanguage() {
        var result = resultWithClaims(
                "sales",
                List.of("El capital semilla es de US$50."),
                List.of("El mercado podría responder bien a este producto."),
                List.of()
        );

        var contradictions = detector.detect(List.of(result), 50, 100);

        assertTrue(contradictions.isEmpty(), () -> String.join(", ", contradictions));
    }

    @Test
    void detectsSameStatementDeclaredAsFactAndHypothesis() {
        var result = resultWithClaims(
                "sales",
                List.of("El mercado objetivo responde bien a descuentos."),
                List.of("El mercado objetivo responde bien a descuentos."),
                List.of()
        );

        var contradictions = detector.detect(List.of(result), 50, 100);

        assertTrue(contradictions.stream()
                .anyMatch(c -> c.contains("hecho e hipótesis al mismo tiempo")));
    }

    @Test
    void detectsHedgeLanguageInsideFacts() {
        var result = resultWithClaims(
                "finance",
                List.of("El negocio probablemente generará ganancias en el primer mes."),
                List.of(),
                List.of()
        );

        var contradictions = detector.detect(List.of(result), 50, 100);

        assertTrue(contradictions.stream()
                .anyMatch(c -> c.contains("lenguaje de hipótesis/estimación")
                        && c.contains("probablemente")));
    }

    private AgentResult result(
            String agent,
            String verificationStatus,
            double confidence,
            List<AgentResult.Evidence> evidence,
            List<AgentResult.Calculation> calculations) {

        return new AgentResult(
                agent,
                "ACTION",
                verificationStatus,
                List.of(),
                List.of(),
                List.of(),
                evidence,
                List.of(),
                calculations,
                List.of(),
                "recomendación",
                confidence
        );
    }

    private AgentResult resultWithClaims(
            String agent,
            List<String> facts,
            List<String> hypotheses,
            List<String> estimates) {

        return new AgentResult(
                agent,
                "ACTION",
                "NOT_VALIDATED",
                facts,
                hypotheses,
                estimates,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "recomendación",
                0.5
        );
    }
}
