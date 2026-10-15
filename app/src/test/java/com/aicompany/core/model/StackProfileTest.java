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
    void godotIsEnabledAndListedInTheUsages() {
        assertTrue(StackProfile.GODOT_DOTNET_GAME.enabledNow());
        assertTrue(StackProfile.enabledUsages().contains("Juegos Godot con C#"), StackProfile.enabledUsages());
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
    void theCatalogDescriptionShowsOnlyTheEnabledProfilesAndTheirStructure() {
        var all = StackProfile.describeAll();
        assertTrue(all.contains("DOTNET_APP") && all.contains("FLUTTER_WEB_APP"));
        assertTrue(all.contains("GODOT_DOTNET_GAME"), "Godot es fase 1 (spec 2026-10-14 Kael)");
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
        // Verificado en vivo (MISSION-DBA-1790955204): Mvc.Testing trajo 35 paquetes pendientes y System.Text.Json
        // vulnerable; el sandbox no corrió.
        assertTrue(StackProfile.DOTNET_APP.executionContract().contains("NO usan Microsoft.AspNetCore.Mvc.Testing"));
        assertTrue(StackProfile.FLUTTER_WEB_APP.executionContract().contains("versión exacta"));
    }

    @Test
    void flutterContractOffersHttpAndForbidsWebOnlyLibraries() {
        var contract = StackProfile.FLUTTER_WEB_APP.executionContract();
        assertTrue(contract.contains("http 1.2.2"), contract);
        assertTrue(contract.contains("dart:html"), contract);
        assertTrue(StackProfile.FULLSTACK_API_RULE.contains("MapGet"), StackProfile.FULLSTACK_API_RULE);
        assertTrue(StackProfile.FULLSTACK_API_RULE.contains("CORS_ORIGINS"), StackProfile.FULLSTACK_API_RULE);
        assertTrue(StackProfile.FULLSTACK_APP_RULE.contains("API_BASE_URL"), StackProfile.FULLSTACK_APP_RULE);
    }

    // Revisión final I5: en el sandbox no hay red ni API_BASE_URL; la app tiene que arrancar y renderizar igual.
    @Test
    void fullstackAppMustStartWithoutTheApi() {
        assertTrue(StackProfile.FULLSTACK_APP_RULE.contains("sin red"), StackProfile.FULLSTACK_APP_RULE);
        assertTrue(StackProfile.FULLSTACK_APP_RULE.contains("captura"), StackProfile.FULLSTACK_APP_RULE);
    }

    // Verificado en vivo (MISSION-B1-CRM-1790978221, MISSION-B1-AGENDA-1790981861): dos veces la capa application de la
    // app importó el cliente HTTP de infrastructure. La regla dice dónde va cada pieza.
    @Test
    void fullstackAppRuleSaysWhereTheHttpClientGoes() {
        var rule = StackProfile.FULLSTACK_APP_RULE;
        assertTrue(rule.contains("interfaz del repositorio"), rule);
        assertTrue(rule.contains("nunca lo importa application"), rule);
        assertTrue(rule.contains("lib/main.dart"), rule);
    }

    // Diagnóstico 2026-10-02: el modelo escribía APIs de Flutter más nuevas (Color.withValues) que el SDK 3.24 del
    // sandbox no tenía. El contrato dice la versión real de la imagen.
    @Test
    void flutterContractMatchesTheSandboxImage() {
        var contract = StackProfile.FLUTTER_WEB_APP.executionContract();
        assertTrue(contract.contains("Flutter 3.41.9 (Dart 3.11)"), contract);
        assertTrue(contract.contains("cupertino_icons 1.0.9 y flutter_lints 6.0.0"), contract);
        assertTrue(BaselineDependencies.contains(new DependencyRef("PUB", "cupertino_icons", "1.0.9")));
        assertTrue(BaselineDependencies.contains(new DependencyRef("PUB", "flutter_lints", "6.0.0")));
    }

    // Verificado en vivo (MISSION-B1-CRM-1790971185 y -1790975402): Diego empezó con EF Core, que el sandbox no trae,
    // y gastó una ronda de corrección cada vez.
    @Test
    void dotnetContractSaysEfCoreIsNotAvailable() {
        var contract = StackProfile.DOTNET_APP.executionContract();
        assertTrue(contract.contains("Entity Framework Core NO está disponible"), contract);
    }

    // FALTANTES (Bloque 1, m-6): lo que el modelo lee del Development Group sale de los perfiles habilitados (con juegos
    // Godot desde la spec 2026-10-14 Kael).
    @Test
    void enabledUsagesListOnlyTheProfilesOfTheCurrentPhase() {
        var usages = StackProfile.enabledUsages();
        assertTrue(usages.contains(StackProfile.DOTNET_APP.usage()), usages);
        assertTrue(usages.contains(StackProfile.FLUTTER_WEB_APP.usage()), usages);
        assertTrue(usages.contains(StackProfile.GODOT_DOTNET_GAME.usage()), usages);
    }
}

