package com.aicompany.core.service;

import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.JointReview.*;
import com.aicompany.core.agent.validation.JointReviewValidatorTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Revisión conjunta (spec 2026-10-15): Neo detecta y Aria decide; Java valida y reintenta con la corrección. */
class JointReviewWriterTest {

    private final CeoService ceo = mock(CeoService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final JointReviewWriter writer = new JointReviewWriter(ceo, company, prompts, "qwen3:8b");

    private static final Contradiction VALID = new Contradiction("CONTRA-01", List.of("HU-01"), List.of("REGLA-01"),
            "800x600", "x > 640", new Proposal(JointReview.DESIGN, "usar 800"));
    private static final Contradiction INVALID = new Contradiction("CONTRA-01", List.of("HU-09"), List.of("REGLA-01"),
            "800x600", "x > 640", new Proposal(JointReview.DESIGN, "usar 800"));

    {
        when(company.agentModel(anyString(), anyString())).thenReturn("nvidia:x/y");
        when(prompts.activePrompt(anyString())).thenReturn("");
    }

    @Test void neoGetsTheStoriesTheDesignAndTheFoundersAdjustmentsAsUntouchable() {
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString())).thenReturn(new Detection(List.of()));

        var found = writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME,
                List.of("la paleta va a la derecha"));

        assertEquals(List.of(), found);
        verify(ceo).detectContradictions(eq("engineering"), argThat(p -> p.contains("HU-01") && p.contains("REGLA-01")
                && p.contains("la paleta va a la derecha") && p.contains("nunca")), anyString(), anyString());
    }

    @Test void anInvalidDetectionIsRetriedWithTheCorrection() {
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Detection(List.of(INVALID)), new Detection(List.of(VALID)));

        assertEquals(List.of(VALID), writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of()));
        verify(ceo).detectContradictions(anyString(), argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR")
                && p.contains("HU-09")), anyString(), anyString());
    }

    @Test void threeInvalidDetectionsFailWithTheReasons() {
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString())).thenReturn(new Detection(List.of(INVALID)));

        var ex = assertThrows(IllegalStateException.class,
                () -> writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of()));
        assertTrue(ex.getMessage().contains("HU-09"), ex.getMessage());
        verify(ceo, times(3)).detectContradictions(anyString(), anyString(), anyString(), anyString());
    }

    @Test void threeInvalidDetectionsGetOneMoreWithNeosPaidFallback() {
        when(company.agentPaidFallbackModel("engineering")).thenReturn("bedrock:kimi");
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new Detection(List.of(INVALID)), new Detection(List.of(INVALID)), new Detection(List.of(INVALID)),
                new Detection(List.of(VALID)));

        assertEquals(List.of(VALID), writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of()));
        verify(ceo).detectContradictions(eq("engineering"), contains("CORRECCIÓN DEL INTENTO ANTERIOR"), anyString(),
                eq("bedrock:kimi"));
    }

    @Test void ariaDecidesWithNeosContradictionsInFront() {
        when(ceo.decideContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Resolution(List.of(new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan"))));

        var decisions = writer.decide("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of(VALID), List.of());

        assertEquals("usar 800", decisions.get(0).change());
        verify(ceo).decideContradictions(eq(RequirementsWriter.ARIA), argThat(p -> p.contains("CONTRA-01")
                && p.contains("x > 640") && p.contains("usar 800")), anyString(), anyString());
    }

    @Test void ariaGetsTheFoundersAdjustmentsAsUntouchable() {
        when(ceo.decideContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Resolution(List.of(new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan"))));

        writer.decide("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of(VALID),
                List.of("la paleta va a la derecha"));

        verify(ceo).decideContradictions(eq(RequirementsWriter.ARIA), argThat(p -> p.contains("la paleta va a la derecha")
                && p.contains("nunca")), anyString(), anyString());
    }

    @Test void aCancellationIsRethrownWithoutRetrying() {
        when(ceo.decideContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new MissionCancellations.MissionCancelledException("M-1"));

        assertThrows(MissionCancellations.MissionCancelledException.class, () -> writer.decide("M-1",
                JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of(VALID), List.of()));
        verify(ceo, times(1)).decideContradictions(anyString(), anyString(), anyString(), anyString());
    }

    @Test void aMissingDecisionIsRetried() {
        when(ceo.decideContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Resolution(List.of()),
                        new Resolution(List.of(new Decision("CONTRA-01", "REQUIREMENTS", "aceptar 640", "mejor"))));

        assertEquals(1, writer.decide("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of(VALID), List.of()).size());
        verify(ceo).decideContradictions(anyString(), argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR")),
                anyString(), anyString());
    }

    @Test void aModelErrorCountsAsAnAttempt() {
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("json roto")).thenReturn(new Detection(List.of()));

        assertEquals(List.of(), writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of()));
    }
}
