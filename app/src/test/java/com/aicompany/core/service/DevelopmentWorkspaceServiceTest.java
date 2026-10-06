package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    // Rondas de evidencia (revisión 2026-09-27): un agente puede devolver sus archivos sin cambios; el commit no debe fallar.
    @Test
    void committingTheSameFilesAgainIsAllowedAndKeepsThemInTheCommit() throws Exception {
        var ws = workspace();
        var same = result(new GeneratedFile("src/A.cs", "class A {}"));
        ws.commitAgentWork("MISSION-9", "MISSION-9-BACKEND", "backend", "Iris", same);

        var second = ws.commitAgentWork("MISSION-9", "MISSION-9-BACKEND-R1", "backend", "Iris", same);

        assertEquals(second.sha(), ws.headSha("MISSION-9"));
        assertTrue(ws.filesAtCommit("MISSION-9", second.sha()).contains("src/A.cs"));
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

    // Las rutas que escribe un agente son literales: git no debe interpretar magia de pathspec (":...").
    @Test
    void pathsAreCommittedLiterallyWithoutPathspecMagic() throws Exception {
        var ws = workspace();

        var record = ws.commitAgentWork("MISSION-5", "T", "backend", "Iris", result(
                new GeneratedFile(":notas.md", "literal")));

        assertEquals(List.of(":notas.md"), ws.filesAtCommit("MISSION-5", record.sha()));
    }

    // Spec misiones durables §2: el workspace se limpia antes de retomar.
    @Test
    void resumingDiscardsWhatWasNotCommittedAndReadsTheLastTask() throws Exception {
        var service = workspace();
        service.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
                new DevelopmentResult("primero", List.of(new GeneratedFile("src/A.cs", "class A {}"))));
        var dir = service.missionWorkspace("M-1");
        Files.writeString(dir.resolve("src/A.cs"), "a medias");
        Files.writeString(dir.resolve("src/B.cs"), "sin commit");

        service.discardUncommitted("M-1");

        assertEquals("class A {}", Files.readString(dir.resolve("src/A.cs")));
        assertFalse(Files.exists(dir.resolve("src/B.cs")));
        assertEquals(Optional.of("M-1-BACKEND"), service.headTask("M-1"));
    }

    @Test
    void anUnrecordedLastCommitCanBeDropped() throws Exception {
        var service = workspace();
        var first = service.commitAgentWork("M-1", "M-1-SCAFFOLD", "forjai", "Forjai",
                new DevelopmentResult("scaffold", List.of(new GeneratedFile("a.txt", "1"))));
        service.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
                new DevelopmentResult("backend", List.of(new GeneratedFile("b.txt", "2"))));

        service.dropHeadCommit("M-1");

        assertEquals(Optional.of(first.sha()), service.headIfAny("M-1"));
    }

    @Test
    void aMissionWithoutWorkspaceHasNoHead() {
        assertEquals(Optional.empty(), workspace().headIfAny("M-NUEVA"));
    }

    // Spec misiones durables §2: el commit raíz no se puede retroceder; hay que saber si HEAD tiene padre.
    @Test
    void knowsWhetherHeadHasAParent() throws Exception {
        var service = workspace();
        service.commitAgentWork("M-1", "M-1-SCAFFOLD", "forjai", "Forjai",
                new DevelopmentResult("base", List.of(new GeneratedFile("a.txt", "1"))));
        assertFalse(service.headHasParent("M-1"));

        service.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
                new DevelopmentResult("backend", List.of(new GeneratedFile("b.txt", "2"))));
        assertTrue(service.headHasParent("M-1"));
    }
}
