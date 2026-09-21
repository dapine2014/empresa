package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contra un directorio temporal REAL (no un mock de ProcessBuilder) —
 * probar que `git init`/`add`/`commit` realmente ocurren es más simple y
 * realista así que mockeando la ejecución de un proceso externo.
 */
class DevelopmentWorkspaceServiceTest {

    private final Path tempRoot = Path.of(
            System.getProperty("java.io.tmpdir"),
            "forjai-dev-workspace-test-" + UUID.randomUUID()
    );

    private final DevelopmentWorkspaceService workspace =
            new DevelopmentWorkspaceService(tempRoot.toString());

    @AfterEach
    void cleanup() throws IOException {
        if (Files.exists(tempRoot)) {
            try (var walk = Files.walk(tempRoot)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException ignored) {
                                // best-effort cleanup de un directorio temporal de test
                            }
                        });
            }
        }
    }

    @Test
    void writesRealFilesUnderTheAgentSubdirectory() throws IOException {

        var result = new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile("src/Program.cs", "// contenido real"))
        );

        workspace.writeFiles("MISSION-1", "architecture", result);

        var written = tempRoot.resolve("MISSION-1").resolve("architecture").resolve("src/Program.cs");

        assertTrue(Files.exists(written));
        assertEquals("// contenido real", Files.readString(written));
    }

    @Test
    void commitsARealGitRepositoryAfterWritingFiles() throws IOException, InterruptedException {

        var result = new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile("README.md", "# Proyecto generado"))
        );

        workspace.writeFiles("MISSION-2", "architecture", result);
        workspace.commitWorkspace("MISSION-2", "Desarrollo inicial generado");

        var missionDir = tempRoot.resolve("MISSION-2");

        assertTrue(Files.exists(missionDir.resolve(".git")));

        var log = new ProcessBuilder("git", "log", "--oneline")
                .directory(missionDir.toFile())
                .start();
        log.waitFor();
        var output = new String(log.getInputStream().readAllBytes());

        assertTrue(output.contains("Desarrollo inicial generado"));
    }

    @Test
    void commitWorkspaceNeverThrowsEvenIfCalledWithoutAnyFilesWritten() {
        assertDoesNotThrow(() -> workspace.commitWorkspace("MISSION-EMPTY", "commit vacío"));
    }

    @Test
    void writeFilesRejectsAMissionIdThatEscapesTheWorkspaceRoot() {

        var result = new DevelopmentResult(
                "resumen",
                List.of(new DevelopmentResult.GeneratedFile("pwned.txt", "contenido"))
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> workspace.writeFiles("../../../etc", "architecture", result)
        );
    }

    @Test
    void commitWorkspaceNeverThrowsEvenWithAMissionIdThatEscapesTheWorkspaceRoot() {
        // Mismo invariante "nunca lanza" que el resto de commitWorkspace
        // (ver commit 281c35d) — el IllegalArgumentException de
        // missionWorkspace también queda contenido acá, solo logueado.
        assertDoesNotThrow(() -> workspace.commitWorkspace("../../../etc", "commit malicioso"));
    }
}
