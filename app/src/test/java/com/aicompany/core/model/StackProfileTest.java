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
}
