package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Workspace real por misión — un repo Git real, sin ejecutar ni desplegar
 * nada (ver docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 6). Los `DevelopmentRuntime` en paralelo solo escriben
 * archivos (vía {@link #writeFiles}); el commit (vía
 * {@link #commitWorkspace}) es un paso único y secuencial, llamado
 * después de que las tareas paralelas ya asentaron — nunca durante la
 * escritura concurrente, para no correr dos `git add`/`commit` a la vez
 * sobre el mismo índice.
 */
@Service
public class DevelopmentWorkspaceService {

    private static final Logger log =
            LoggerFactory.getLogger(DevelopmentWorkspaceService.class);

    private final Path workspaceRoot;

    public DevelopmentWorkspaceService(
            @Value("${products.workspace-root}") String workspaceRoot) {
        this.workspaceRoot = Path.of(workspaceRoot);
    }

    public Path missionWorkspace(String missionId) {
        return workspaceRoot.resolve(missionId);
    }

    /**
     * Escribe los archivos reales de un {@link DevelopmentResult} ya
     * validado — nunca llamar sin pasar antes por
     * {@code DevelopmentPathValidationGate}. Cada agente escribe bajo su
     * propio subdirectorio de convención (evita que dos agentes en
     * paralelo escriban el mismo archivo).
     */
    public void writeFiles(String missionId, String subdirectory, DevelopmentResult result) throws IOException {

        var agentDir = missionWorkspace(missionId).resolve(subdirectory).normalize();

        Files.createDirectories(agentDir);

        for (var file : result.files()) {

            var target = agentDir.resolve(file.path()).normalize();

            if (!target.startsWith(agentDir)) {
                // Defensa en profundidad — DevelopmentPathValidationGate ya
                // debería haber rechazado esto antes de llegar acá.
                throw new IllegalStateException("Ruta fuera del workspace: " + file.path());
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code git init} (si hace falta) + {@code add -A} + un solo commit
     * consolidado. Best-effort: un fallo acá no debe tumbar la misión
     * (mismo criterio que {@code AlertMailService.send}) — se loguea
     * `WARN` y se sigue.
     */
    public void commitWorkspace(String missionId, String commitMessage) {

        var missionDir = missionWorkspace(missionId);

        try {

            Files.createDirectories(missionDir);

            if (!Files.exists(missionDir.resolve(".git"))) {
                runGit(missionDir, "init");
            }

            runGit(missionDir, "add", "-A");
            runGit(
                    missionDir,
                    "-c", "user.name=Forjai Engineering Team",
                    "-c", "user.email=engineering@forjai.local",
                    "commit", "-m", commitMessage, "--allow-empty"
            );

        } catch (Exception ex) {

            log.warn(
                    "DEVELOPMENT_COMMIT_FAILED missionId={} reason={}",
                    missionId,
                    ex.getMessage()
            );
        }
    }

    private void runGit(Path dir, String... args) throws IOException, InterruptedException {

        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(List.of(args));

        var process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();

        var finished = process.waitFor(30, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + String.join(" ", args) + " superó el timeout");
        }

        if (process.exitValue() != 0) {

            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            throw new IOException("git " + String.join(" ", args) + " falló: " + output);
        }
    }
}
