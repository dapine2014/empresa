package com.aicompany.core.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClaimRelevanceCheckerTest {

    @Test
    void supportsWhenMostTermsAppearInContent() {
        var result = ClaimRelevanceChecker.check(
                "costo promedio asesoría empresarial Colombia",
                "El costo promedio de una asesoría empresarial en Colombia varía según la región."
        );

        assertTrue(result.supported());
        assertTrue(result.matchedTerms() >= 3);
    }

    @Test
    void rejectsWhenContentIsUnrelated() {
        var result = ClaimRelevanceChecker.check(
                "costo promedio asesoría empresarial Colombia",
                "Receta de pastel de chocolate: mezcla harina, huevos y azúcar."
        );

        assertFalse(result.supported());
        assertEquals(0, result.matchedTerms());
    }

    @Test
    void ignoresAccentsWhenMatching() {
        // El HTML crudo a veces trae/no trae tildes de forma inconsistente.
        var result = ClaimRelevanceChecker.check(
                "asesoría empresarial",
                "La asesoria empresarial es un servicio comun en Colombia."
        );

        assertTrue(result.supported());
    }

    @Test
    void ignoresCaseWhenMatching() {
        var result = ClaimRelevanceChecker.check(
                "PRECIO PROMEDIO",
                "el precio promedio del servicio es de $100.000"
        );

        assertTrue(result.supported());
    }

    @Test
    void doesNotBlockWhenClaimHasNoSignificantTerms() {
        // Solo stopwords / palabras muy cortas — no hay nada que evaluar,
        // no debe bloquear.
        var result = ClaimRelevanceChecker.check("de la y el", "contenido totalmente distinto");

        assertTrue(result.supported());
        assertEquals(0, result.totalTerms());
    }

    @Test
    void handlesNullOrBlankClaimGracefully() {
        assertTrue(ClaimRelevanceChecker.check(null, "cualquier contenido").supported());
        assertTrue(ClaimRelevanceChecker.check("", "cualquier contenido").supported());
    }

    @Test
    void partialMatchBelowThresholdIsRejected() {
        // Solo 1 de 5 términos significativos coincide (20% < 30%).
        var result = ClaimRelevanceChecker.check(
                "el dominio example com existe responde",
                "Example Domain: contenido genérico sin relación."
        );

        assertFalse(result.supported());
    }
}
