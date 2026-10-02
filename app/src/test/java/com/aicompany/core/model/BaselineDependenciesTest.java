package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaselineDependenciesTest {

    // Spec 2026-10-02 §2.5: Npgsql es la pieza central del rol de Diego; va preaprobado y precargado.
    @Test
    void npgsqlIsPreapprovedForTheDataArchitect() {
        assertTrue(BaselineDependencies.contains(new DependencyRef("NUGET", "Npgsql", "8.0.5")));
    }
}
