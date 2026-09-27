package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.validation.CompilerErrorParser.CompilerError;
import com.aicompany.core.model.StackProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MissingUsingFixerTest {

    private static final List<String> CONTEXTS = List.of("Tareas");

    private static CompilerError notFound(String path, String type) {
        return new CompilerError(path, 9, 26, "CS0246", "The type or namespace name '" + type
                + "' could not be found (are you missing a using directive or an assembly reference?)");
    }

    // Caso real de MISSION-SANDBOX-VERIFY-12: Tarea e ITareaRepository existen en Tareas.Domain.
    private static Map<String, String> repo() {
        var files = new LinkedHashMap<String, String>();
        files.put("src/Tareas.Domain/Tarea.cs", "using System;\n\nnamespace Tareas.Domain\n{\n    public class Tarea { }\n}\n");
        files.put("src/Tareas.Domain/TareaRepository.cs", "namespace Tareas.Domain;\npublic interface ITareaRepository { }\n");
        files.put("src/Tareas.Application/TareaApplicationService.cs",
                "using System;\nusing System.Linq;\n\nnamespace Tareas.Application\n{\n    public class TareaApplicationService { }\n}\n");
        files.put("src/Tareas.Api/Program.cs", "var app = 1;\n");
        return files;
    }

    @Test
    void addsTheMissingUsingForATypeDeclaredInAnAllowedLayer() {
        var fixes = MissingUsingFixer.fix(StackProfile.DOTNET_APP, CONTEXTS, repo(), List.of(
                notFound("src/Tareas.Application/TareaApplicationService.cs", "Tarea"),
                notFound("src/Tareas.Application/TareaApplicationService.cs", "ITareaRepository")));

        assertEquals(1, fixes.size());
        var fixed = fixes.get("src/Tareas.Application/TareaApplicationService.cs");
        assertEquals("using System;\nusing System.Linq;\nusing Tareas.Domain;\n\nnamespace Tareas.Application\n{\n"
                + "    public class TareaApplicationService { }\n}\n", fixed);
    }

    // Nunca agrega una dependencia que viola DDD: domain no puede usar application.
    @Test
    void neverAddsAUsingThatBreaksTheLayerRules() {
        var files = repo();
        files.put("src/Tareas.Domain/Otro.cs", "namespace Tareas.Domain;\npublic class Otro { }\n");
        files.put("src/Tareas.Application/Servicio.cs", "namespace Tareas.Application;\npublic class Servicio { }\n");
        var fixes = MissingUsingFixer.fix(StackProfile.DOTNET_APP, CONTEXTS, files,
                List.of(notFound("src/Tareas.Domain/Otro.cs", "Servicio")));
        assertEquals(Map.of(), fixes);
    }

    @Test
    void ambiguousOrUnknownTypesAreLeftToTheAgent() {
        var files = repo();
        files.put("src/Tareas.Application/Tarea.cs", "namespace Tareas.Application.Modelos;\npublic class Tarea { }\n");
        var fixes = MissingUsingFixer.fix(StackProfile.DOTNET_APP, CONTEXTS, files, List.of(
                notFound("src/Tareas.Application/TareaApplicationService.cs", "Tarea"),
                notFound("src/Tareas.Application/TareaApplicationService.cs", "NoExiste")));
        assertEquals(Map.of(), fixes);
    }

    @Test
    void anExistingUsingIsNotDuplicated() {
        var files = repo();
        files.put("src/Tareas.Application/TareaApplicationService.cs", "using Tareas.Domain;\nnamespace Tareas.Application;\n");
        assertEquals(Map.of(), MissingUsingFixer.fix(StackProfile.DOTNET_APP, CONTEXTS, files,
                List.of(notFound("src/Tareas.Application/TareaApplicationService.cs", "Tarea"))));
    }
}
