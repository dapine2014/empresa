package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DesignDecision;
import com.aicompany.core.model.DesignScreenReview;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.recovery.MissionRecoveryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec 2026-11-01 front con diseño visual §14-15: el punto único de decisión de las diferencias de diseño. */
class DesignDecisionServiceTest {

    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final DesignReviewMemoryService reviews = mock(DesignReviewMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final MissionRecoveryService recovery = mock(MissionRecoveryService.class);
    private final DesignDecisionService service = new DesignDecisionService(missions, reviews, policies, events, recovery);

    {
        when(policies.activeValue(PolicyKey.DESIGN_MISMATCH_DECIDER)).thenReturn(0.0);
    }

    private void mission(MissionStatus status, String message) {
        when(missions.find("M-1")).thenReturn(Optional.of(new MissionResponse("M-1", status, "TEST", 100, "Bloqueada",
                message, Instant.now(), null, "TEAM-DEVELOPMENT")));
    }

    private static DesignScreenReview review(String screen, String status, int extra, String note) {
        return new DesignScreenReview(1, screen, "phone", 0.8, status, 2, extra, note == null ? null : "founder", note,
                null);
    }

    @Test
    void acceptLastPendingResumesMission() {
        mission(MissionStatus.BLOCKED, "DESIGN_MISMATCH: PANT-01\n\nESTADO");
        when(reviews.decide("M-1", "PANT-01", DesignScreenReview.ACCEPTED, "founder", "se ve bien", 0))
                .thenReturn(Optional.of(new DesignReviewMemoryService.Decided(1, 0)));
        when(reviews.all("M-1")).thenReturn(List.of(review("PANT-01", DesignScreenReview.ACCEPTED, 0, "se ve bien")));

        var view = service.decide("M-1", "PANT-01", DesignDecision.ACCEPT, "se ve bien", "founder");

        assertEquals("ACCEPTED", view.screens().get(0).status());
        assertEquals("FOUNDER", view.decider());
        verify(events).publish(eq("EMPRESA_DESIGN_DECISION"), eq("M-1"), isNull(), eq("human"),
                argThat(d -> "PANT-01".equals(d.get("screen")) && "ACCEPT".equals(d.get("decision"))
                        && "founder".equals(d.get("decidedBy"))));
        verify(recovery).resumeDesign("M-1");
    }

    @Test
    void acceptWithOtherScreensStillAwaitingDoesNotResume() {
        mission(MissionStatus.BLOCKED, "DESIGN_MISMATCH: PANT-01, PANT-02");
        when(reviews.decide("M-1", "PANT-01", DesignScreenReview.ACCEPTED, "founder", "", 0))
                .thenReturn(Optional.of(new DesignReviewMemoryService.Decided(1, 1)));

        service.decide("M-1", "PANT-01", DesignDecision.ACCEPT, null, "founder");

        verify(events).publish(eq("EMPRESA_DESIGN_DECISION"), eq("M-1"), isNull(), eq("human"), anyMap());
        verify(recovery, never()).resumeDesign(anyString());
    }

    @Test
    void anotherRoundAddsOneExtraRoundAndResumes() {
        mission(MissionStatus.BLOCKED, "DESIGN_MISMATCH: PANT-01");
        when(reviews.decide("M-1", "PANT-01", DesignScreenReview.PENDING, "founder", "el botón va abajo", 1))
                .thenReturn(Optional.of(new DesignReviewMemoryService.Decided(1, 0)));
        when(reviews.all("M-1")).thenReturn(List.of(review("PANT-01", DesignScreenReview.PENDING, 1, "el botón va abajo")));

        var view = service.decide("M-1", "PANT-01", DesignDecision.ANOTHER_ROUND, "el botón va abajo", "founder");

        verify(reviews).decide("M-1", "PANT-01", DesignScreenReview.PENDING, "founder", "el botón va abajo", 1);
        assertEquals(3, view.screens().get(0).roundLimit());
        verify(events).publish(eq("EMPRESA_DESIGN_DECISION"), eq("M-1"), isNull(), eq("human"),
                argThat(d -> "ANOTHER_ROUND".equals(d.get("decision"))));
        verify(recovery).resumeDesign("M-1");
    }

    @Test
    void anotherRoundNeedsAComment() {
        mission(MissionStatus.BLOCKED, "DESIGN_MISMATCH: PANT-01");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.decide("M-1", "PANT-01", DesignDecision.ANOTHER_ROUND, "  ", "founder"));
        assertTrue(ex.getMessage().contains("comentario"), ex.getMessage());
        verify(reviews, never()).decide(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    // Review Focus 5: doble clic. La segunda no decide ni reanuda otra vez.
    @Test
    void doubleAcceptIsRejected() {
        mission(MissionStatus.BLOCKED, "DESIGN_MISMATCH: PANT-01");
        when(reviews.decide("M-1", "PANT-01", DesignScreenReview.ACCEPTED, "founder", "", 0))
                .thenReturn(Optional.of(new DesignReviewMemoryService.Decided(1, 0)))
                .thenReturn(Optional.empty());

        service.decide("M-1", "PANT-01", DesignDecision.ACCEPT, "", "founder");
        var ex = assertThrows(IllegalStateException.class,
                () -> service.decide("M-1", "PANT-01", DesignDecision.ACCEPT, "", "founder"));

        assertTrue(ex.getMessage().contains("ya tiene una decisión en curso"), ex.getMessage());
        verify(recovery, times(1)).resumeDesign("M-1");
        verify(events, times(1)).publish(eq("EMPRESA_DESIGN_DECISION"), anyString(), any(), anyString(), anyMap());
    }

    // Review Focus 5 con dos hilos a la vez: el claim atómico (simulado acá con un CAS) es el único guardia.
    @Test
    void concurrentAcceptsResumeExactlyOnce() throws Exception {
        mission(MissionStatus.BLOCKED, "DESIGN_MISMATCH: PANT-01");
        var claimed = new AtomicBoolean(false);
        when(reviews.decide(eq("M-1"), eq("PANT-01"), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(inv -> claimed.compareAndSet(false, true)
                        ? Optional.of(new DesignReviewMemoryService.Decided(1, 0)) : Optional.empty());
        var start = new CountDownLatch(1);
        var errors = Collections.synchronizedList(new ArrayList<Throwable>());
        var pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    service.decide("M-1", "PANT-01", DesignDecision.ACCEPT, "", "founder");
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, errors.size(), "una de las dos llamadas tiene que fallar");
        assertInstanceOf(IllegalStateException.class, errors.get(0));
        verify(recovery, times(1)).resumeDesign("M-1");
    }

    @Test
    void decideRejectedWhenMissionNotBlocked() {
        mission(MissionStatus.EVALUATING, "Validando");
        var ex = assertThrows(IllegalStateException.class,
                () -> service.decide("M-1", "PANT-01", DesignDecision.ACCEPT, "", "founder"));
        assertTrue(ex.getMessage().contains("BLOCKED"), ex.getMessage());
        verify(reviews, never()).decide(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        verify(recovery, never()).resumeDesign(anyString());
    }

    // R13: una falla de captura no es una diferencia de diseño; se retoma con «reanuda».
    @Test
    void captureFailureIsNotDecidedHere() {
        mission(MissionStatus.BLOCKED, "DESIGN_CAPTURE_FAILED: sandbox caído\n\nESTADO");
        var ex = assertThrows(IllegalStateException.class,
                () -> service.decide("M-1", "PANT-01", DesignDecision.ACCEPT, "", "founder"));
        assertTrue(ex.getMessage().contains("reanuda M-1"), ex.getMessage());
        verify(reviews, never()).decide(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void decideRejectedForAnUnknownMission() {
        when(missions.find("M-9")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.decide("M-9", "PANT-01", DesignDecision.ACCEPT, "", "founder"));
    }

    @Test
    void theDeciderIsResolvedFromThePolicy() {
        assertEquals("FOUNDER", service.decider());
        when(policies.activeValue(PolicyKey.DESIGN_MISMATCH_DECIDER)).thenReturn(1.0);
        assertEquals("PRODUCT_OWNER", service.decider());
    }

    @Test
    void theViewShowsTheBlockReasonAndAllScreens() {
        mission(MissionStatus.BLOCKED, "DESIGN_MISMATCH: PANT-01\n\nESTADO VERIFICABLE");
        when(reviews.all("M-1")).thenReturn(List.of(review("PANT-01", DesignScreenReview.AWAITING_DECISION, 0, null)));

        var view = service.view("M-1").orElseThrow();

        assertEquals("BLOCKED", view.missionStatus());
        assertEquals("DESIGN_MISMATCH: PANT-01", view.blockReason());
        assertEquals(1, view.screens().size());
        assertTrue(service.view("M-9").isEmpty());
    }

    @Test
    void aFailedResumeKeepsTheDecisionAndSaysHowToRetry() {
        mission(MissionStatus.BLOCKED, "DESIGN_MISMATCH: PANT-01");
        when(reviews.decide("M-1", "PANT-01", DesignScreenReview.ACCEPTED, "founder", "", 0))
                .thenReturn(Optional.of(new DesignReviewMemoryService.Decided(1, 0)));
        doThrow(new IllegalStateException("Aria está apagada")).when(recovery).resumeDesign("M-1");

        var ex = assertThrows(IllegalStateException.class,
                () -> service.decide("M-1", "PANT-01", DesignDecision.ACCEPT, "", "founder"));

        assertTrue(ex.getMessage().contains("reanuda M-1") && ex.getMessage().contains("Aria está apagada"),
                ex.getMessage());
        verify(events).publish(eq("EMPRESA_DESIGN_DECISION"), eq("M-1"), isNull(), eq("human"), anyMap());
    }
}
