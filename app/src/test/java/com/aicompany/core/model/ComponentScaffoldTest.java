package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentScaffoldTest {

    @Test
    void oneComponentIsTheSameScaffoldAsToday() {
        var root = List.of(new AppComponent(StackProfile.DOTNET_APP, ""));
        assertEquals(ProjectScaffold.generate(StackProfile.DOTNET_APP, List.of("Clientes")),
                ComponentScaffold.generate(root, List.of("Clientes"), Map.of()));
    }

    // Spec 2026-10-02 B1 §3: los .csproj y la .sln van dentro de backend/ con referencias relativas intactas.
    @Test
    void dotnetProjectsArePrefixedAndFlutterHasNone() {
        var components = AppComponent.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));
        var files = ComponentScaffold.generate(components, List.of("Clientes"),
                Map.of("backend/src/Clientes.Infrastructure/Clientes.Infrastructure.csproj",
                        List.of(new DependencyRef("NUGET", "Npgsql", "8.0.5"))));
        var paths = files.stream().map(f -> f.path()).toList();
        assertTrue(paths.contains("backend/src/Clientes.Api/Clientes.Api.csproj"), paths.toString());
        assertTrue(paths.contains("backend/Solution.sln"), paths.toString());
        assertTrue(paths.stream().allMatch(p -> p.startsWith("backend/")), paths.toString());
        var infra = files.stream().filter(f -> f.path().endsWith("Clientes.Infrastructure.csproj")).findFirst().orElseThrow();
        assertTrue(infra.content().contains("Include=\"Npgsql\" Version=\"8.0.5\""), infra.content());
        assertTrue(infra.content().contains("../Clientes.Domain/Clientes.Domain.csproj"), infra.content());
        assertEquals(List.of("backend/src/Clientes.Domain/Clientes.Domain.csproj",
                        "backend/src/Clientes.Application/Clientes.Application.csproj",
                        "backend/src/Clientes.Infrastructure/Clientes.Infrastructure.csproj",
                        "backend/src/Clientes.Api/Clientes.Api.csproj",
                        "backend/tests/Clientes.Tests/Clientes.Tests.csproj"),
                ComponentScaffold.projectFiles(components, List.of("Clientes")));
    }
}
