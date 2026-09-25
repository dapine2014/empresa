package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.validation.OwnedPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Workspace real por misión = un repo Git real (spec §6). Cada agente
 * produce exactamente un commit con solo sus archivos, con él como autor y
 * trailers que lo enlazan a su misión/tarea. Nunca ejecuta el código.
 */
@Service
public class DevelopmentWorkspaceService {

    public record CommitRecord(String sha, List<String> files) {
    }

    private final Path workspaceRoot;
    private final GitCommandRunner git;

    public DevelopmentWorkspaceService(
            @Value("${products.workspace-root}") String workspaceRoot,
            GitCommandRunner git) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.git = git;
    }

    public static String authorEmail(String agentId) {
        return agentId + "@agents.forjai.local";
    }

    public Path missionWorkspace(String missionId) {

        var dir = workspaceRoot.resolve(missionId).normalize();

        if (!dir.startsWith(workspaceRoot) || dir.equals(workspaceRoot)) {
            throw new IllegalArgumentException("missionId fuera del workspace: " + missionId);
        }

        return dir;
    }

    /**
     * Escribe los archivos (ya validados por DevelopmentPathValidationGate
     * y por ownedPaths en DevelopmentRuntime) y hace un commit solo con
     * ellos. Rutas repetidas: gana el último contenido, aparecen una vez.
     */
    public synchronized CommitRecord commitAgentWork(
            String missionId, String taskId, String agentId, String agentName, DevelopmentResult result)
            throws IOException {

        var dir = missionWorkspace(missionId);
        Files.createDirectories(dir);

        if (!Files.exists(dir.resolve(".git"))) {
            git.run(dir, "init", "-q");
        }

        var contentByPath = new LinkedHashMap<String, String>();

        for (var file : result.files()) {
            if (file != null) {
                contentByPath.put(OwnedPaths.normalize(file.path()), file.content());
            }
        }

        var gitDir = dir.resolve(".git");

        for (var entry : contentByPath.entrySet()) {

            var target = dir.resolve(entry.getKey()).normalize();

            // Defensa en profundidad: los gates ya deberían haber rechazado esto.
            if (!target.startsWith(dir) || target.startsWith(gitDir)) {
                throw new IllegalStateException("Ruta fuera del workspace permitido: " + entry.getKey());
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }

        var paths = new ArrayList<>(contentByPath.keySet());

        var addArgs = new ArrayList<String>(List.of("add", "--"));
        addArgs.addAll(paths);
        git.run(dir, addArgs.toArray(String[]::new));

        var message = result.summary()
                + "\n\nForjai-Mission: " + missionId
                + "\nForjai-Task: " + taskId;

        git.run(dir,
                "-c", "user.name=Forjai company-core",
                "-c", "user.email=company-core@forjai.local",
                "-c", "commit.gpgsign=false",
                "commit", "-q",
                "--author", agentName + " <" + authorEmail(agentId) + ">",
                "-m", message);

        var sha = git.run(dir, "rev-parse", "HEAD").trim();

        return new CommitRecord(sha, List.copyOf(paths));
    }

    public List<String> filesAtCommit(String missionId, String sha) throws IOException {
        return lines(git.run(missionWorkspace(missionId), "ls-tree", "-r", "--name-only", sha));
    }

    public String readFileAtCommit(String missionId, String sha, String path) throws IOException {
        return git.run(missionWorkspace(missionId), "show", sha + ":" + OwnedPaths.normalize(path));
    }

    public void deleteWorkspace(String missionId) throws IOException {

        var dir = missionWorkspace(missionId);

        if (!Files.exists(dir)) {
            return;
        }

        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    static List<String> lines(String output) {
        return Arrays.stream(output.split("\n"))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }
}
