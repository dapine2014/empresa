package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectFileGateTest {

    private static final List<String> EXPECTED = List.of(
            "src/Tareas.Domain/Tareas.Domain.csproj", "src/Tareas.Application/Tareas.Application.csproj",
            "tests/Tareas.Tests/Tareas.Tests.csproj");

    private static GeneratedFile csproj(String path, String content) {
        return new GeneratedFile(path, content);
    }

    @Test
    void aWellFormedProjectWithValidReferencesPasses() {
        var file = csproj("tests/Tareas.Tests/Tareas.Tests.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup>
                    <ProjectReference Include="..\\..\\src\\Tareas.Domain\\Tareas.Domain.csproj" />
                    <ProjectReference Include="../../src/Tareas.Application/Tareas.Application.csproj" />
                  </ItemGroup>
                </Project>""");
        assertEquals(List.of(), ProjectFileGate.check(List.of(file), EXPECTED));
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-3): "// Tareas.Domain.csproj" antes del XML.
    @Test
    void aCommentBeforeTheXmlIsRejected() {
        var file = csproj("src/Tareas.Domain/Tareas.Domain.csproj",
                "// Tareas.Domain.csproj\n<Project Sdk=\"Microsoft.NET.Sdk\"></Project>");
        var errors = ProjectFileGate.check(List.of(file), EXPECTED);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("src/Tareas.Domain/Tareas.Domain.csproj"), errors.get(0));
        assertTrue(errors.get(0).contains("XML"), errors.get(0));
    }

    // Verificado en vivo: el .csproj de la API quedó cortado a mitad de un atributo.
    @Test
    void aTruncatedProjectIsRejected() {
        var file = csproj("src/Tareas.Api/Tareas.Api.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\">\n<ItemGroup>\n<ProjectReference Include=\"..\\");
        assertEquals(1, ProjectFileGate.check(List.of(file), EXPECTED).size());
    }

    // Verificado en vivo: "..\..\Tareas.Domain\Tareas.Domain.cs,proj" desde tests/ (ruta y extensión mal).
    @Test
    void aReferenceToAProjectOutsideTheExpectedOnesIsRejectedListingThem() {
        var file = csproj("tests/Tareas.Tests/Tareas.Tests.csproj", """
                <Project Sdk="Microsoft.NET.Sdk">
                  <ItemGroup><ProjectReference Include="..\\..\\Tareas.Domain\\Tareas.Domain.cs,proj" /></ItemGroup>
                </Project>""");
        var errors = ProjectFileGate.check(List.of(file), EXPECTED);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("Tareas.Domain/Tareas.Domain.cs,proj"), errors.get(0));
        assertTrue(errors.get(0).contains("../../src/Tareas.Domain/Tareas.Domain.csproj"), errors.get(0));
    }

    @Test
    void doctypeIsNotProcessed() {
        var file = csproj("src/Tareas.Domain/Tareas.Domain.csproj",
                "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Project>&e;</Project>");
        assertEquals(1, ProjectFileGate.check(List.of(file), EXPECTED).size());
    }

    @Test
    void nonProjectFilesAreIgnored() {
        assertEquals(List.of(), ProjectFileGate.check(List.of(new GeneratedFile("src/Tareas.Domain/Tarea.cs", "// x")),
                EXPECTED));
    }
}
