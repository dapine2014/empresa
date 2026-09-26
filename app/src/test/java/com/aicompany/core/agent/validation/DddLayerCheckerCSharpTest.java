package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DddLayerCheckerCSharpTest {

    private static final List<String> CONTEXTS = List.of("Combate", "Inventario");

    private static List<DddLayerChecker.Violation> check(Map<String, String> files) {
        return DddLayerChecker.check(StackProfile.GODOT_DOTNET_GAME, CONTEXTS, files);
    }

    @Test
    void aCleanLayeredRepositoryHasNoViolations() {
        var files = new LinkedHashMap<String, String>();
        files.put("src/Combate.Domain/Unidad.cs", "using System;\nnamespace Combate.Domain;\npublic class Unidad {}");
        files.put("src/Combate.Application/AtacarHandler.cs", "using Combate.Domain;\nusing Inventario.Application;\nnamespace Combate.Application;");
        files.put("game/Main.cs", "using Godot;\nusing Combate.Application;\nusing Combate.Domain;\npublic partial class Main : Node {}");
        files.put("tests/Combate.Tests/UnidadTests.cs", "using Combate.Domain;\nusing Xunit;");
        assertEquals(List.of(), check(files));
    }

    @Test
    void domainImportingAFrameworkIsAViolation() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", "using Godot;\nnamespace Combate.Domain;"));
        assertEquals(1, violations.size());
        assertEquals("Godot", violations.get(0).dependency());
    }

    @Test
    void domainImportingApplicationIsAViolation() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", "using Combate.Application;"));
        assertEquals(1, violations.size());
    }

    @Test
    void domainUsingAnotherContextsDomainIsAViolation() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", "using Inventario.Domain;"));
        assertEquals(1, violations.size());
    }

    @Test
    void applicationUsingAnotherContextsDomainIsAViolation() {
        var violations = check(Map.of("src/Combate.Application/X.cs", "using Inventario.Domain.Items;"));
        assertEquals(1, violations.size());
    }

    // Review Focus: alias, static y global using se detectan igual que un using simple.
    @Test
    void aliasStaticAndGlobalUsingsAreDetected() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", String.join("\n",
                "using G = Godot;",
                "global using static Godot.GD;",
                "using Microsoft.AspNetCore.Mvc;")));
        assertEquals(3, violations.size(), violations.toString());
    }

    @Test
    void theCheckSummarizesViolations() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", "using Godot;"));
        var check = DddLayerChecker.toCheck(violations, 1);
        assertFalse(check.passed());
        assertTrue(check.detail().contains("src/Combate.Domain/Unidad.cs"));
        assertTrue(DddLayerChecker.toCheck(List.of(), 4).passed());
    }
}
