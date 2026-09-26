package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompilerErrorParserTest {

    // Salida real de MISSION-SANDBOX-VERIFY-10 (MSBuild repite cada error en el resumen final).
    private static final String DOTNET = """
            /work/src/Tareas.Domain/TareaPendiente.cs(32,24): error CS1002: ; expected [/work/src/Tareas.Domain/Tareas.Domain.csproj]
            /work/src/Tareas.Domain/TareaPendiente.cs(32,24): error CS1513: } expected [/work/src/Tareas.Domain/Tareas.Domain.csproj]

            Build FAILED.

            /work/src/Tareas.Domain/TareaPendiente.cs(32,24): error CS1002: ; expected [/work/src/Tareas.Domain/Tareas.Domain.csproj]
            /work/src/Tareas.Api/Program.cs(3,1): warning CS8618: Non-nullable [/work/src/Tareas.Api/Tareas.Api.csproj]
                0 Warning(s)
                2 Error(s)
            """;

    @Test
    void parsesDotnetErrorsWithoutDuplicatesOrWarnings() {
        var errors = CompilerErrorParser.parse(DOTNET);
        assertEquals(List.of(
                new CompilerErrorParser.CompilerError("src/Tareas.Domain/TareaPendiente.cs", 32, 24, "CS1002", "; expected"),
                new CompilerErrorParser.CompilerError("src/Tareas.Domain/TareaPendiente.cs", 32, 24, "CS1513", "} expected")),
                errors);
    }

    @Test
    void parsesDartErrors() {
        var errors = CompilerErrorParser.parse("""
                lib/pedidos/domain/pedido.dart:5:3: Error: Expected ';' after this.
                  final String id
                  ^^^^^^
                Error: Compilation failed.
                """);
        assertEquals(List.of(new CompilerErrorParser.CompilerError("lib/pedidos/domain/pedido.dart", 5, 3, "Error",
                "Expected ';' after this.")), errors);
    }

    @Test
    void outputWithoutErrorsGivesNothing() {
        assertEquals(List.of(), CompilerErrorParser.parse("Build succeeded.\n    0 Error(s)"));
        assertEquals(List.of(), CompilerErrorParser.parse(null));
    }
}
