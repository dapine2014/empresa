package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DependencyFetch;
import com.aicompany.core.model.DependencyRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DependencyServiceTest {

    private final DependencyMemoryService memory = mock(DependencyMemoryService.class);
    private final SandboxRunnerClient runner = mock(SandboxRunnerClient.class);
    private final OsvClient osv = mock(OsvClient.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final DependencyService service = new DependencyService(memory, runner, osv, events);

    private static final DependencyRef JSON = new DependencyRef("NUGET", "Newtonsoft.Json", "13.0.3");

    private static DependencyFetch.FetchedPackage pkg(String name, String version, String license) {
        return new DependencyFetch.FetchedPackage(name, version, license, null, false, List.of());
    }

    // Review Focus: lo ya aprobado o de la imagen no se descarga ni se re-evalúa.
    @Test
    void alreadyApprovedOrBaselinePackagesAreNotFetchedAgain() {
        when(memory.status(JSON)).thenReturn(Optional.of("APPROVED"));

        var outcome = service.resolve("M-1", "backend", List.of(JSON, new DependencyRef("NUGET", "xunit", "2.5.3")));

        assertTrue(outcome.pending().isEmpty());
        verifyNoInteractions(runner);
    }

    @Test
    void aCleanPackageIsApprovedByPolicyAndPromoted() {
        when(memory.status(any())).thenReturn(Optional.empty());
        when(runner.fetchDependencies("NUGET", List.of(JSON))).thenReturn(Optional.of(
                new DependencyFetch("fetch-1", "PASS", "", List.of(pkg("Newtonsoft.Json", "13.0.3", "MIT")))));
        when(osv.blockingVulnerabilities(JSON)).thenReturn(Optional.of(List.of()));
        when(runner.promoteDependencies("NUGET", "fetch-1", List.of(JSON))).thenReturn(true);

        var outcome = service.resolve("M-1", "backend", List.of(JSON));

        assertTrue(outcome.pending().isEmpty(), outcome.toString());
        assertEquals(List.of(JSON), outcome.approvedNow());
        verify(memory).record(eq(JSON), eq("APPROVED"), eq("policy"), eq(List.of()), eq("MIT"), eq("backend"), eq("M-1"), eq("fetch-1"));
        verify(events).publish(eq("EMPRESA_DEPENDENCY_APPROVED"), eq("M-1"), any(), eq("backend"), anyMap());
    }

    @Test
    void aVulnerableTransitivePackageLeavesItPending() {
        var transitive = new DependencyRef("NUGET", "Old.Lib", "1.0.0");
        when(memory.status(any())).thenReturn(Optional.empty());
        when(runner.fetchDependencies("NUGET", List.of(JSON))).thenReturn(Optional.of(new DependencyFetch("fetch-2", "PASS", "",
                List.of(pkg("Newtonsoft.Json", "13.0.3", "MIT"), pkg("Old.Lib", "1.0.0", "MIT")))));
        when(osv.blockingVulnerabilities(JSON)).thenReturn(Optional.of(List.of()));
        when(osv.blockingVulnerabilities(transitive)).thenReturn(Optional.of(List.of("GHSA-9")));
        when(runner.promoteDependencies(anyString(), anyString(), anyList())).thenReturn(true);

        var outcome = service.resolve("M-1", "backend", List.of(JSON));

        assertEquals(List.of(transitive), outcome.pending());
        verify(memory).record(eq(transitive), eq("PENDING_APPROVAL"), isNull(), argThat(r -> r.get(0).contains("GHSA-9")),
                eq("MIT"), eq("backend"), eq("M-1"), eq("fetch-2"));
        verify(events).publish(eq("EMPRESA_DEPENDENCY_REQUESTED"), eq("M-1"), any(), eq("backend"), anyMap());
    }

    @Test
    void aFailedFetchIsAPendingOutcomeWithTheError() {
        when(memory.status(any())).thenReturn(Optional.empty());
        when(runner.fetchDependencies("NUGET", List.of(JSON))).thenReturn(Optional.of(
                new DependencyFetch("fetch-3", "FAIL", "NU1101: Unable to find package", List.of())));

        var outcome = service.resolve("M-1", "backend", List.of(JSON));

        assertEquals(List.of(JSON), outcome.pending());
        assertTrue(outcome.error().contains("NU1101"), outcome.error());
        verify(runner, never()).promoteDependencies(anyString(), anyString(), anyList());
    }
}
