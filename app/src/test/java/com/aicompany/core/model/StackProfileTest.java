package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StackProfileTest {

    @Test
    void parsesOnlyCatalogIds() {
        assertEquals(Optional.of(StackProfile.GODOT_DOTNET_GAME), StackProfile.parse("GODOT_DOTNET_GAME"));
        assertEquals(Optional.empty(), StackProfile.parse("UNITY_GAME"));
        assertEquals(Optional.empty(), StackProfile.parse(""));
        assertEquals(Optional.empty(), StackProfile.parse(null));
    }

    @Test
    void contextNameRulesDependOnTheLanguage() {
        assertTrue(StackProfile.DOTNET_APP.isValidContextName("Combate"));
        assertFalse(StackProfile.DOTNET_APP.isValidContextName("combate"));
        assertTrue(StackProfile.FLUTTER_WEB_APP.isValidContextName("combate_naval"));
        assertFalse(StackProfile.FLUTTER_WEB_APP.isValidContextName("Combate"));
    }

    @Test
    void locatesFilesByContextAndLayer() {
        var contexts = List.of("Combate", "Inventario");
        assertEquals(Optional.of(new StackProfile.Location("Combate", StackProfile.Layer.DOMAIN)),
                StackProfile.GODOT_DOTNET_GAME.locate("src/Combate.Domain/Unidad.cs", contexts));
        assertEquals(Optional.of(new StackProfile.Location(null, StackProfile.Layer.GAME)),
                StackProfile.GODOT_DOTNET_GAME.locate("game/Main.cs", contexts));
        assertEquals(Optional.of(new StackProfile.Location("pedidos", StackProfile.Layer.PRESENTATION)),
                StackProfile.FLUTTER_WEB_APP.locate("lib/pedidos/presentation/home.dart", List.of("pedidos")));
        assertEquals(Optional.empty(), StackProfile.DOTNET_APP.locate("utils/Helper.cs", contexts));
    }

    @Test
    void structureAcceptsLayerRootsEntryFilesAndExtrasOnly() {
        var contexts = List.of("Combate");
        var godot = StackProfile.GODOT_DOTNET_GAME;
        assertTrue(godot.isWithinStructure("src/Combate.Domain/Unidad.cs", contexts));
        assertTrue(godot.isWithinStructure("Juego.sln", contexts));
        assertTrue(godot.isWithinStructure("game/project.godot", contexts));
        assertTrue(godot.isWithinStructure("README.md", contexts));
        assertFalse(godot.isWithinStructure("src", contexts));
        assertFalse(godot.isWithinStructure("src/Otro.Domain/X.cs", contexts));
        assertFalse(godot.isWithinStructure("utils/Helper.cs", contexts));
    }

    @Test
    void reportsMissingEntryFiles() {
        assertEquals(List.of(), StackProfile.GODOT_DOTNET_GAME.missingEntryFiles(List.of("game/project.godot", "Juego.sln")));
        assertEquals(List.of("game/project.godot"),
                StackProfile.GODOT_DOTNET_GAME.missingEntryFiles(List.of("Juego.sln")));
        assertEquals(List.of("pubspec.yaml", "lib/main.dart"), StackProfile.FLUTTER_WEB_APP.missingEntryFiles(List.of()));
    }

    @Test
    void theCatalogDescriptionShowsEveryProfileAndItsStructure() {
        var all = StackProfile.describeAll();
        assertTrue(all.contains("DOTNET_APP") && all.contains("GODOT_DOTNET_GAME") && all.contains("FLUTTER_WEB_APP"));
        assertTrue(all.contains("src/<Ctx>.Domain"));
        assertTrue(all.contains("lib/<ctx>/domain"));
    }

    // Verificado en vivo (sandbox parte 2): sin red, solo existen las versiones precargadas en la imagen, y el paso
    // de arranque espera cosas concretas. Los agentes lo tienen que saber o el sandbox falla con código correcto.
    @Test
    void everyProfileDescribesItsExecutionContract() {
        assertTrue(StackProfile.DOTNET_APP.describe().contains("xunit 2.5.3"));
        assertTrue(StackProfile.DOTNET_APP.describe().contains("GET /health"));
        assertTrue(StackProfile.GODOT_DOTNET_GAME.describe().contains("Godot.NET.Sdk/4.3.0"));
        assertTrue(StackProfile.GODOT_DOTNET_GAME.describe().contains("run/main_scene"));
        assertTrue(StackProfile.FLUTTER_WEB_APP.describe().contains("cupertino_icons"));
        for (var profile : StackProfile.values()) {
            assertTrue(profile.describe().contains("SANDBOX (sin red)"), profile.name());
        }
    }

    @Test
    void projectFilesArePerLayerForDotnetAndNoneForFlutter() {
        assertEquals(List.of("src/Tareas.Domain/Tareas.Domain.csproj", "src/Tareas.Application/Tareas.Application.csproj",
                        "src/Tareas.Infrastructure/Tareas.Infrastructure.csproj", "src/Tareas.Api/Tareas.Api.csproj",
                        "tests/Tareas.Tests/Tareas.Tests.csproj"),
                StackProfile.DOTNET_APP.projectFiles(List.of("Tareas")));
        assertTrue(StackProfile.GODOT_DOTNET_GAME.projectFiles(List.of("Combate")).contains("game/Game.csproj"));
        assertTrue(StackProfile.FLUTTER_WEB_APP.projectFiles(List.of("pedidos")).isEmpty());
    }

    @Test
    void theDotnetContractExplainsHowToRequestAPackage() {
        assertTrue(StackProfile.DOTNET_APP.executionContract().contains("\"packages\""));
        assertTrue(StackProfile.FLUTTER_WEB_APP.executionContract().contains("versión exacta"));
    }
}
