package com.aicompany.core.service;

import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.JointReview.*;
import com.aicompany.core.agent.validation.JointReviewValidatorTest;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.PolicyKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Revisión conjunta (spec 2026-10-15): cuándo aplica, qué ajusta y qué queda registrado. */
class JointReviewServiceTest {

    private final JointReviewWriter writer = mock(JointReviewWriter.class);
    private final JointReviewMemoryService memory = mock(JointReviewMemoryService.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    private final InteractionDesignMemoryService designs = mock(InteractionDesignMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final AgentAvailability availability = mock(AgentAvailability.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final JointReviewService service = new JointReviewService(writer, memory, requirements, designs, policies,
            availability, events);

    private static final Contradiction C1 = new Contradiction("CONTRA-01", List.of("HU-01"), List.of("REGLA-01"),
            "800x600", "x > 640", new Proposal(JointReview.DESIGN, "usar 800"));
    private static final Contradiction C2 = new Contradiction("CONTRA-02", List.of("HU-01"), List.of("pelota"),
            "velocidad constante", "+5 %", new Proposal(JointReview.DESIGN, "sin aceleración"));

    {
        when(policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED)).thenReturn(1.0);
        when(availability.isEnabled(anyString())).thenReturn(true);
        when(requirements.instructions("M-1")).thenReturn(List.of());
        when(designs.instructions("M-1")).thenReturn(List.of());
    }

    @Test void aCoherentDesignPassesWithoutCallingAria() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of());

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        assertEquals("COHERENT", outcome.status());
        assertFalse(outcome.needsChanges());
        verify(writer, never()).decide(anyString(), any(), any(), anyList());
        verify(memory).save(eq("M-1"), eq(1), eq(2), eq(3), eq("COHERENT"), anyString(), anyString(), anyString());
        verify(events).publish(eq("EMPRESA_JOINT_REVIEW_COMPLETED"), eq("M-1"), isNull(), eq("engineering"), anyMap());
    }

    @Test void ariasDecisionsAreGroupedIntoOneAdjustmentPerSide() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of(C1, C2));
        when(writer.decide(anyString(), any(), any(), anyList())).thenReturn(List.of(
                new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan"),
                new Decision("CONTRA-02", "REQUIREMENTS", "la pelota acelera 5 %", "más desafío")));

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        assertEquals("RESOLVED", outcome.status());
        assertTrue(outcome.needsChanges());
        assertTrue(outcome.designAdjustment().startsWith(JointReviewService.PREFIX));
        assertTrue(outcome.designAdjustment().contains("CONTRA-01") && outcome.designAdjustment().contains("usar 800"));
        assertFalse(outcome.designAdjustment().contains("CONTRA-02"));
        assertTrue(outcome.requirementsAdjustment().contains("CONTRA-02 (HU-01 ↔ pelota)"));
    }

    @Test void inTheSecondRoundWhatRemainsIsUnresolvedAndAriaIsNotCalled() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of(C1));

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 2);

        assertEquals("UNRESOLVED", outcome.status());
        assertFalse(outcome.needsChanges());
        verify(writer, never()).decide(anyString(), any(), any(), anyList());
    }

    @Test void aFailedReviewDoesNotBlockTheMission() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenThrow(new IllegalStateException("sin respuesta válida"));

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        assertEquals("FAILED", outcome.status());
        assertFalse(outcome.needsChanges());
        assertTrue(outcome.detail().contains("sin respuesta válida"));
        verify(memory).save(eq("M-1"), eq(1), eq(2), eq(3), eq("FAILED"), anyString(), anyString(), contains("sin respuesta válida"));
    }

    @Test void withThePolicyOffOrNeoOrAriaOffItIsSkipped() {
        when(policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED)).thenReturn(0.0);
        assertEquals("SKIPPED", service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1).status());

        when(policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED)).thenReturn(1.0);
        when(availability.isEnabled("engineering")).thenReturn(false);
        var neoOff = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);
        assertEquals("SKIPPED", neoOff.status());
        assertTrue(neoOff.detail().contains("Neo"));
        verifyNoInteractions(writer);
    }

    @Test void withAriaOffItIsSkipped() {
        when(policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED)).thenReturn(1.0);
        when(availability.isEnabled("engineering")).thenReturn(true);
        when(availability.isEnabled(RequirementsWriter.ARIA)).thenReturn(false);

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        assertEquals("SKIPPED", outcome.status());
        assertTrue(outcome.detail().contains("Aria"));
        verify(writer, never()).detect(anyString(), any(), any(), anyList());
    }

    @Test void theFoundersAdjustmentsReachNeoButNotTheReviewsOwn() {
        when(requirements.instructions("M-1")).thenReturn(List.of("sin sonido", JointReviewService.PREFIX + " x"));
        when(designs.instructions("M-1")).thenReturn(List.of("Aria cambió las historias de usuario: rehaz el diseño",
                "la paleta a la derecha"));
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of());

        service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        verify(writer).detect(anyString(), any(), any(), eq(List.of("sin sonido", "la paleta a la derecha")));
    }

    @Test void theFounderNoteListsWhatWasResolvedAndWhatRemains() {
        var resolved = new JointReviewService.Outcome("RESOLVED", List.of(C1),
                List.of(new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan")), null, "x", "");
        var unresolved = new JointReviewService.Outcome("UNRESOLVED", List.of(C2), List.of(), null, null, "");

        var note = service.founderNote(List.of(resolved, unresolved));

        assertTrue(note.contains("Neo y Aria resolvieron 1 contradicción"), note);
        assertTrue(note.contains("CONTRA-01 (HU-01 ↔ REGLA-01) → diseño: usar 800"), note);
        assertTrue(note.contains("⚠ Sin resolver: CONTRA-02"), note);
        assertEquals("", service.founderNote(List.of()));
    }

    @Test void whenMemorySaveFailsTheOutcomeIsStillReturned() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of());
        doThrow(new RuntimeException("neo4j caído")).when(memory).save(anyString(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyString(), anyString());

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        assertEquals("COHERENT", outcome.status());
        assertFalse(outcome.needsChanges());
    }
}
