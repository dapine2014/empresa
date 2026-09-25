package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Contra un repo Git REAL en un directorio temporal (no se mockea ProcessBuilder). */
class DevelopmentWorkspaceServiceTest {

    @TempDir
    Path tempRoot;

    private final GitCommandRunner git = new GitCommandRunner();

    private DevelopmentWorkspaceService workspace() {
        return new DevelopmentWorkspaceService(tempRoot.toString(), git);
    }

    private static DevelopmentResult result(GeneratedFile... files) {
        return new DevelopmentResult("Estructura base del juego", List.of(files));
    }

    @Test
    void createsOneCommitPerAgentWithTheAgentAsAuthorAndTaskTrailers() throws Exception {
        var ws = workspace();

        var neo = ws.commitAgentWork("MISSION-1", "MISSION-1-ENGINEERING", "engineering", "Neo",
                result(new GeneratedFile("web/index.html", "<html></html>")));
        var mila = ws.commitAgentWork("MISSION-1", "MISSION-1-FRONTEND-UI", "frontend-ui", "Mila",
                result(new GeneratedFile("web/ui/hud.js", "export const hud = 1;")));

        var dir = ws.missionWorkspace("MISSION-1");
        assertEquals("Neo <engineering@agents.forjai.local>",
                git.run(dir, "show", "-s", "--format=%an <%ae>", neo.sha()).trim());
        assertEquals("MISSION-1-FRONTEND-UI",
                git.run(dir, "show", "-s", "--format=%(trailers:key=Forjai-Task,valueonly)", mila.sha()).trim());
        assertEquals("web/ui/hud.js",
                git.run(dir, "show", "--name-only", "--format=", mila.sha()).trim());
        assertEquals(List.of("web/ui/hud.js"), mila.files());
        assertEquals(40, neo.sha().length());
    }

    // Review Focus: la misma ruta dos veces no debe duplicar archivos ni entradas en AgentTask.files.
    @Test
    void duplicatedPathsKeepTheLastContentAndAppearOnce() throws Exception {
        var ws = workspace();

        var record = ws.commitAgentWork("MISSION-2", "MISSION-2-BACKEND", "backend", "Iris", result(
                new GeneratedFile("web/game/main.js", "v1"),
                new GeneratedFile("./web/game/main.js", "v2")));

        assertEquals(List.of("web/game/main.js"), record.files());
        assertEquals("v2", ws.readFileAtCommit("MISSION-2", record.sha(), "web/game/main.js"));
    }

    @Test
    void neverWritesInsideGitInternalsEvenIfTheGateWasBypassed() {
        var ws = workspace();

        assertThrows(IllegalStateException.class, () -> ws.commitAgentWork("MISSION-3", "T", "backend", "Iris",
                result(new GeneratedFile(".git/config", "[core]"))));
    }

    @Test
    void listsFilesAtACommitAndDeletesTheWorkspace() throws Exception {
        var ws = workspace();
        var record = ws.commitAgentWork("MISSION-4", "T", "engineering", "Neo",
                result(new GeneratedFile("web/index.html", "<html></html>")));

        assertEquals(List.of("web/index.html"), ws.filesAtCommit("MISSION-4", record.sha()));

        ws.deleteWorkspace("MISSION-4");

        assertFalse(Files.exists(ws.missionWorkspace("MISSION-4")));
        assertDoesNotThrow(() -> ws.deleteWorkspace("MISSION-4"));
    }

    @Test
    void rejectsMissionIdsThatEscapeTheWorkspaceRoot() {
        assertThrows(IllegalArgumentException.class, () -> workspace().missionWorkspace("../fuera"));
    }
}
