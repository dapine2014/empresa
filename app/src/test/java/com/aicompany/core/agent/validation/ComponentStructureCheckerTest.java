package com.aicompany.core.agent.validation;

import com.aicompany.core.model.AppComponent;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentStructureCheckerTest {

    private static final List<AppComponent> FULLSTACK =
            AppComponent.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));

    private static final List<String> FILES = List.of(
            "backend/Solution.sln",
            "backend/src/Clientes.Domain/Clientes.Domain.csproj", "backend/src/Clientes.Domain/Cliente.cs",
            "backend/src/Clientes.Application/Clientes.Application.csproj",
            "backend/src/Clientes.Infrastructure/Clientes.Infrastructure.csproj",
            "backend/src/Clientes.Api/Clientes.Api.csproj", "backend/src/Clientes.Api/Program.cs",
            "backend/tests/Clientes.Tests/Clientes.Tests.csproj",
            "app/pubspec.yaml", "app/lib/main.dart", "app/lib/clientes/domain/cliente.dart",
            "app/lib/api/api_routes.dart",
            "db/postgres/migrations/V1__clientes.sql", ".env.example");

    private static StaticCheck named(List<StaticCheck> checks, String name) {
        return checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void fullstackRepositoryPassesStructureChecks() {
        var checks = ComponentStructureChecker.check(FULLSTACK, List.of("Clientes"), FILES, Map.of(
                "backend/src/Clientes.Domain/Cliente.cs", "namespace Clientes.Domain; public class Cliente {}",
                "app/pubspec.yaml", "name: app\n",
                "app/lib/main.dart", "import 'package:flutter/material.dart';",
                "app/lib/clientes/domain/cliente.dart", "class Cliente {}"),
                List.of("app/lib/api/api_routes.dart"));
        assertTrue(named(checks, "ENTRY_FILES").passed(), named(checks, "ENTRY_FILES").detail());
        assertTrue(named(checks, "PROFILE_STRUCTURE").passed(), named(checks, "PROFILE_STRUCTURE").detail());
        assertTrue(named(checks, "DDD_LAYERS").passed(), named(checks, "DDD_LAYERS").detail());
        assertTrue(named(checks, "PORTABILITY").passed());
    }

    @Test
    void aFileOutsideEveryComponentFailsWithItsRealPath() {
        var files = new java.util.ArrayList<>(FILES);
        files.add("frontend/index.html");
        var structure = named(ComponentStructureChecker.check(FULLSTACK, List.of("Clientes"), files, Map.of(),
                List.of("app/lib/api/api_routes.dart")), "PROFILE_STRUCTURE");
        assertFalse(structure.passed());
        assertTrue(structure.paths().contains("frontend/index.html"), structure.paths().toString());
    }

    @Test
    void domainViolationReportsThePrefixedFile() {
        var checks = ComponentStructureChecker.check(FULLSTACK, List.of("Clientes"), FILES, Map.of(
                "backend/src/Clientes.Domain/Cliente.cs", "using Microsoft.AspNetCore.Http;\nnamespace Clientes.Domain;"),
                List.of());
        var ddd = named(checks, "DDD_LAYERS");
        assertFalse(ddd.passed());
        assertTrue(ddd.paths().contains("backend/src/Clientes.Domain/Cliente.cs"), ddd.paths().toString());
    }

    @Test
    void oneComponentGivesTheSameChecksAsToday() {
        var files = List.of("pubspec.yaml", "lib/main.dart", "lib/saludo/domain/saludo.dart");
        var root = List.of(new AppComponent(StackProfile.FLUTTER_WEB_APP, ""));
        var checks = ComponentStructureChecker.check(root, List.of("saludo"), files,
                Map.of("lib/main.dart", "void main() {}"), List.of());
        assertEquals(ProfileStructureChecker.check(StackProfile.FLUTTER_WEB_APP, List.of("saludo"), files),
                checks.subList(0, 2));
    }
}
