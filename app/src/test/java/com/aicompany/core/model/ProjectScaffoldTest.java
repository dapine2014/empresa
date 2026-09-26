package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.validation.ProjectFileGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ProjectScaffoldTest {

    private static Map<String, String> byPath(StackProfile profile, List<String> contexts) {
        return ProjectScaffold.generate(profile, contexts).stream()
                .collect(Collectors.toMap(GeneratedFile::path, GeneratedFile::content));
    }

    @Test
    void generatesExactlyTheProjectFilesTheProfileRequires() {
        for (var profile : StackProfile.values()) {
            var contexts = profile == StackProfile.FLUTTER_WEB_APP ? List.of("tareas") : List.of("Tareas", "Usuarios");
            var expected = new java.util.ArrayList<>(profile.projectFiles(contexts));
            if (profile.ecosystem() == StackProfile.Ecosystem.NUGET) {
                expected.add("Solution.sln");
            }
            assertEquals(expected.stream().sorted().toList(),
                    byPath(profile, contexts).keySet().stream().sorted().toList(), profile.name());
        }
    }

    // Las referencias siguen las reglas DDD: domain no depende de nada; application de domain; etc.
    @Test
    void dotnetReferencesFollowTheLayerRules() {
        var files = byPath(StackProfile.DOTNET_APP, List.of("Tareas"));
        assertFalse(files.get("src/Tareas.Domain/Tareas.Domain.csproj").contains("ProjectReference"));
        assertTrue(files.get("src/Tareas.Application/Tareas.Application.csproj")
                .contains("<ProjectReference Include=\"../Tareas.Domain/Tareas.Domain.csproj\" />"));
        assertTrue(files.get("src/Tareas.Api/Tareas.Api.csproj").contains("Microsoft.NET.Sdk.Web"));
        assertTrue(files.get("src/Tareas.Api/Tareas.Api.csproj")
                .contains("../Tareas.Infrastructure/Tareas.Infrastructure.csproj"));
        var tests = files.get("tests/Tareas.Tests/Tareas.Tests.csproj");
        assertTrue(tests.contains("\"xunit\" Version=\"2.5.3\""));
        assertTrue(tests.contains("../../src/Tareas.Domain/Tareas.Domain.csproj"));
    }

    @Test
    void godotGameProjectUsesTheGodotSdkAndReferencesEveryContext() {
        var game = byPath(StackProfile.GODOT_DOTNET_GAME, List.of("Combate", "Mapa")).get("game/Game.csproj");
        assertTrue(game.contains("Godot.NET.Sdk/4.3.0"));
        assertTrue(game.contains("../src/Combate.Domain/Combate.Domain.csproj"));
        assertTrue(game.contains("../src/Mapa.Application/Mapa.Application.csproj"));
    }

    // Lo generado por Forjai tiene que pasar su propio gate (XML y referencias a proyectos del plan).
    @Test
    void everyGeneratedProjectPassesTheProjectFileGateAsXml() {
        for (var profile : List.of(StackProfile.DOTNET_APP, StackProfile.GODOT_DOTNET_GAME)) {
            var contexts = List.of("Tareas", "Usuarios");
            assertEquals(List.of(), ProjectFileGate.check(ProjectScaffold.generate(profile, contexts),
                    profile.projectFiles(contexts), List.of()), profile.name());
        }
    }

    // Revisión 3: el líder nunca escribía el .sln; lo genera Forjai con todos los proyectos (ENTRY_FILES lo exige).
    @Test
    void theSolutionListsEveryProject() {
        var sln = byPath(StackProfile.DOTNET_APP, List.of("Tareas")).get("Solution.sln");
        assertTrue(sln.startsWith("\nMicrosoft Visual Studio Solution File, Format Version 12.00"), sln);
        assertTrue(sln.contains("\"Tareas.Domain\", \"src\\Tareas.Domain\\Tareas.Domain.csproj\""), sln);
        assertTrue(sln.contains("\"Tareas.Tests\", \"tests\\Tareas.Tests\\Tareas.Tests.csproj\""), sln);
        assertTrue(StackProfile.DOTNET_APP.isEntryFile("Solution.sln"));
    }
}
