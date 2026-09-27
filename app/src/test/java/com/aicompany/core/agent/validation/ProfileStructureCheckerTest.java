package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStructureCheckerTest {

    private static StaticCheck find(List<StaticCheck> checks, String name) {
        return checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aWellFormedGodotRepositoryPasses() {
        var checks = ProfileStructureChecker.check(StackProfile.GODOT_DOTNET_GAME, List.of("Combate"), List.of(
                "Juego.sln", "game/project.godot", "game/Game.csproj", "game/Main.cs",
                "src/Combate.Domain/Combate.Domain.csproj", "src/Combate.Domain/Unidad.cs",
                "src/Combate.Application/Combate.Application.csproj",
                "tests/Combate.Tests/Combate.Tests.csproj", "README.md"));
        assertTrue(checks.stream().allMatch(StaticCheck::passed), checks.toString());
    }

    @Test
    void missingEntryFilesFail() {
        var checks = ProfileStructureChecker.check(StackProfile.FLUTTER_WEB_APP, List.of("pedidos"),
                List.of("lib/pedidos/domain/pedido.dart"));
        var entry = find(checks, "ENTRY_FILES");
        assertFalse(entry.passed());
        assertTrue(entry.detail().contains("pubspec.yaml"));
    }

    @Test
    void filesOutsideTheStructureFailAndAreListed() {
        var checks = ProfileStructureChecker.check(StackProfile.DOTNET_APP, List.of("Pedidos"),
                List.of("Tienda.sln", "src/Pedidos.Domain/Pedido.cs", "utils/Helper.cs"));
        var structure = find(checks, "PROFILE_STRUCTURE");
        assertFalse(structure.passed());
        assertEquals(List.of("utils/Helper.cs"), structure.paths());
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-2): nadie escribió un .csproj, la solución quedó vacía y
    // restore/build/test "pasaron" sin compilar nada. Cada capa .NET necesita su proyecto.
    @Test
    void missingDotnetProjectFilesFailEntryFiles() {
        var checks = ProfileStructureChecker.check(StackProfile.DOTNET_APP, List.of("Tareas"), List.of(
                "Solution.sln", "src/Tareas.Domain/Tarea.cs", "src/Tareas.Api/Program.cs",
                "src/Tareas.Api/Tareas.Api.csproj"));
        var entry = find(checks, "ENTRY_FILES");
        assertFalse(entry.passed());
        assertTrue(entry.paths().contains("src/Tareas.Domain/Tareas.Domain.csproj"), entry.paths().toString());
        assertTrue(entry.paths().contains("tests/Tareas.Tests/Tareas.Tests.csproj"), entry.paths().toString());
        assertFalse(entry.paths().contains("src/Tareas.Api/Tareas.Api.csproj"));
    }
}
