package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.validation.DependencyPolicy.PackageFacts;
import com.aicompany.core.model.DependencyRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DependencyPolicyTest {

    private static final DependencyRef REF = new DependencyRef("NUGET", "A", "1.0.0");

    @Test
    void approvesWhenEverythingPasses() {
        assertEquals("APPROVED", DependencyPolicy.decide(new PackageFacts(REF, "MIT", false, List.of(), Optional.of(List.of()))).status());
    }

    @Test
    void eachFailingCheckLeavesItPendingWithTheExactReason() {
        var d = DependencyPolicy.decide(new PackageFacts(REF, null, true, List.of("build/a.targets"), Optional.of(List.of("GHSA-1"))));
        assertEquals("PENDING_APPROVAL", d.status());
        assertEquals(3, d.reasons().size(), d.reasons().toString());
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("GHSA-1")));
        assertTrue(d.reasons().stream().anyMatch(r -> r.contains("build/a.targets")));
    }

    // Review Focus: OSV caído → pendiente, nunca aprobado a ciegas.
    @Test
    void anUnreachableOsvIsNotAnApproval() {
        var d = DependencyPolicy.decide(new PackageFacts(REF, "MIT", false, List.of(), Optional.empty()));
        assertEquals("PENDING_APPROVAL", d.status());
        assertTrue(d.reasons().get(0).contains("OSV"));
    }
}
