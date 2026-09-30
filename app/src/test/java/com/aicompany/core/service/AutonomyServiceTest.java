package com.aicompany.core.service;

import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.PolicySnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec modo automático (2026-09-29): interruptores sobre la policy versionada y "esperando tu decisión". */
class AutonomyServiceTest {

    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final DependencyMemoryService dependencies = mock(DependencyMemoryService.class);
    private final com.aicompany.core.prospecting.ProspectingMemoryService prospectingMemory =
            mock(com.aicompany.core.prospecting.ProspectingMemoryService.class);
    private final com.aicompany.core.outreach.OutreachMemoryService outreachMemory =
            mock(com.aicompany.core.outreach.OutreachMemoryService.class);
    private final AutonomyService service = new AutonomyService(policies, missionMemory, dependencies, prospectingMemory,
            outreachMemory);

    {
        when(dependencies.list()).thenReturn(List.of());
    }

    private void productsOn(boolean on, String reason) {
        when(policies.activeValue(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(on ? 1.0 : 0.0);
        when(policies.snapshot(PolicyKey.ORCHESTRATOR_ENABLED)).thenReturn(new PolicySnapshot(
                "ORCHESTRATOR_ENABLED", 2, on ? 1.0 : 0.0, "human", reason, Instant.now(), List.of()));
    }

    @Test
    void turningProductsOnCreatesAVersionWithTheOrigin() {
        productsOn(false, "Pausado por el fundador desde el chat");

        assertTrue(service.setProducts(true, "el Dashboard"));

        verify(policies).createVersion(PolicyKey.ORCHESTRATOR_ENABLED, 1, "Encendido desde el Dashboard");
    }

    @Test
    void turningProductsOffCreatesAVersionWithTheOrigin() {
        productsOn(true, "seed");

        assertTrue(service.setProducts(false, "el chat"));

        verify(policies).createVersion(PolicyKey.ORCHESTRATOR_ENABLED, 0, "Apagado desde el chat");
    }

    @Test
    void turningOnWhatIsAlreadyOnCreatesNoVersion() {
        productsOn(true, "seed");

        assertFalse(service.setProducts(true, "el Dashboard"));

        verify(policies, never()).createVersion(any(), anyDouble(), anyString());
    }

    private void clientsOn(boolean on) {
        when(policies.activeValue(PolicyKey.PROSPECTING_ENABLED)).thenReturn(on ? 1.0 : 0.0);
        when(policies.snapshot(PolicyKey.PROSPECTING_ENABLED)).thenReturn(new PolicySnapshot(
                "PROSPECTING_ENABLED", 1, on ? 1.0 : 0.0, "system", "Valor inicial de seed", Instant.now(), List.of()));
    }

    // Spec búsqueda de prospectos §3: el interruptor de clientes ya existe.
    @Test
    void clientsCanBeTurnedOnFromTheDashboard() {
        productsOn(true, "seed");
        clientsOn(false);

        var view = service.update(new AutonomyService.AutonomyCommand(null, true), "el Dashboard");

        verify(policies).createVersion(PolicyKey.PROSPECTING_ENABLED, 1, "Encendido desde el Dashboard");
        assertTrue(view.clients().available());
    }

    @Test
    void turningClientsOnWhenOnCreatesNoVersion() {
        clientsOn(true);

        assertFalse(service.setClients(true, "el chat"));
        verify(policies, never()).createVersion(eq(PolicyKey.PROSPECTING_ENABLED), anyDouble(), anyString());
    }

    @Test
    void pendingStrategiesWaitForTheFounder() {
        productsOn(true, "seed");
        clientsOn(true);
        when(prospectingMemory.pendingStrategies()).thenReturn(1);

        assertEquals(1, service.view().waiting().pendingStrategies());
    }

    @Test
    void thePauseReasonIsShownOnlyWhenOff() {
        productsOn(false, "Pausado solo: 2 ciclos seguidos fallidos");
        assertEquals("Pausado solo: 2 ciclos seguidos fallidos", service.view().products().pauseReason());

        productsOn(true, "Encendido desde el Dashboard");
        assertNull(service.view().products().pauseReason());
    }

    @Test
    void theViewCountsWhatWaitsForTheFounder() {
        productsOn(true, "seed");
        clientsOn(false);
        when(missionMemory.countAwaitingLaunchedBy("orchestrator")).thenReturn(4);
        when(dependencies.list()).thenReturn(List.of(
                Map.<String, Object>of("status", "PENDING_APPROVAL"), Map.<String, Object>of("status", "APPROVED"),
                Map.<String, Object>of("status", "PENDING_APPROVAL")));

        var view = service.view();

        assertTrue(view.products().enabled());
        assertTrue(view.products().available());
        assertFalse(view.clients().enabled());
        assertTrue(view.clients().available());
        assertEquals(4, view.waiting().orchestratorMissions());
        assertEquals(2, view.waiting().pendingDependencies());
    }

    @Test
    void updateAppliesProductsAndReturnsTheView() {
        productsOn(false, "x");

        service.update(new AutonomyService.AutonomyCommand(true, null), "el Dashboard");

        verify(policies).createVersion(PolicyKey.ORCHESTRATOR_ENABLED, 1, "Encendido desde el Dashboard");
    }

    @Test
    void draftsWaitingForApprovalAreCounted() {
        productsOn(true, "seed");
        clientsOn(true);
        when(outreachMemory.drafts("PENDING_APPROVAL")).thenReturn(List.of(
                new com.aicompany.core.outreach.ContactDraft("D1", "C1", "Acme", "P1", "a@acme.com", "s", "b",
                        "PENDING_APPROVAL", null, Instant.now(), null)));

        assertEquals(1, service.view().waiting().pendingDrafts());
    }
}
