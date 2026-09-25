package com.aicompany.core.service;

import com.aicompany.core.agent.validation.OwnedPaths;
import com.aicompany.core.model.StaticCheck;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Capa 1 de la validación estática (spec §7): chequeos deterministas
 * contra el repo Git real de la misión. Sin LLM, sin ejecutar código.
 */
@Component
public class StaticWorkspaceValidator {

    public record CommittedWork(String taskId, String agentId, String commitSha, List<String> files) {
    }

    private final DevelopmentWorkspaceService workspace;
    private final GitCommandRunner git;

    public StaticWorkspaceValidator(DevelopmentWorkspaceService workspace, GitCommandRunner git) {
        this.workspace = workspace;
        this.git = git;
    }

    public List<StaticCheck> validate(
            String missionId, List<CommittedWork> work, String entryPoint, List<String> allowedPaths) {

        var checks = new ArrayList<StaticCheck>();
        var dir = workspace.missionWorkspace(missionId);

        if (!Files.isDirectory(dir.resolve(".git"))) {
            checks.add(StaticCheck.fail("REPOSITORY_EXISTS", "No existe un repositorio Git en " + dir, null, List.of()));
            return checks;
        }

        checks.add(StaticCheck.pass("REPOSITORY_EXISTS", dir.toString(), null, List.of()));

        for (var item : work) {
            validateCommit(dir, item, checks);
        }

        validateHead(dir, entryPoint, allowedPaths, checks);

        return checks;
    }

    private void validateCommit(Path dir, CommittedWork item, List<StaticCheck> checks) {

        var sha = item.commitSha();
        String type;

        try {
            type = git.run(dir, "cat-file", "-t", sha).trim();
        } catch (IOException ex) {
            type = "";
        }

        if (!"commit".equals(type)) {
            checks.add(StaticCheck.fail("COMMIT_EXISTS",
                    "El commit de " + item.agentId() + " no existe en el repositorio.", sha, List.of()));
            return;
        }

        checks.add(StaticCheck.pass("COMMIT_EXISTS", "Commit de " + item.agentId(), sha, List.of()));

        try {

            var email = git.run(dir, "show", "-s", "--format=%ae", sha).trim();
            var expectedEmail = DevelopmentWorkspaceService.authorEmail(item.agentId());
            checks.add(email.equals(expectedEmail)
                    ? StaticCheck.pass("COMMIT_AUTHOR", "Autor " + email, sha, List.of())
                    : StaticCheck.fail("COMMIT_AUTHOR", "Autor " + email + ", esperado " + expectedEmail, sha, List.of()));

            var trailer = git.run(dir, "show", "-s", "--format=%(trailers:key=Forjai-Task,valueonly)", sha).trim();
            checks.add(trailer.equals(item.taskId())
                    ? StaticCheck.pass("COMMIT_TASK_TRAILER", "Forjai-Task=" + trailer, sha, List.of())
                    : StaticCheck.fail("COMMIT_TASK_TRAILER",
                            "Forjai-Task=" + trailer + ", esperado " + item.taskId(), sha, List.of()));

            var inCommit = Set.copyOf(DevelopmentWorkspaceService.lines(
                    git.run(dir, "ls-tree", "-r", "--name-only", sha)));
            var declared = item.files() == null ? List.<String>of() : item.files();
            var missing = declared.stream().filter(f -> !inCommit.contains(f)).toList();

            if (declared.isEmpty()) {
                checks.add(StaticCheck.fail("FILES_IN_COMMIT",
                        "La tarea de " + item.agentId() + " no declaró archivos.", sha, List.of()));
            } else if (!missing.isEmpty()) {
                checks.add(StaticCheck.fail("FILES_IN_COMMIT",
                        "Archivos declarados que no están en el commit: " + missing, sha, missing));
            } else {
                checks.add(StaticCheck.pass("FILES_IN_COMMIT",
                        declared.size() + " archivo(s) presentes en el commit", sha, declared));
            }

        } catch (IOException ex) {
            checks.add(StaticCheck.fail("COMMIT_READABLE",
                    "No se pudo leer el commit de " + item.agentId() + ": " + ex.getMessage(), sha, List.of()));
        }
    }

    private void validateHead(Path dir, String entryPoint, List<String> allowedPaths, List<StaticCheck> checks) {

        List<String> entries;

        try {
            entries = DevelopmentWorkspaceService.lines(git.run(dir, "ls-tree", "-r", "HEAD"));
        } catch (IOException ex) {
            checks.add(StaticCheck.fail("HEAD_READABLE", "No se pudo leer HEAD: " + ex.getMessage(), null, List.of()));
            return;
        }

        var symlinks = new ArrayList<String>();
        var outside = new ArrayList<String>();

        for (var entry : entries) {

            var tab = entry.indexOf('\t');
            var space = entry.indexOf(' ');

            if (tab < 0 || space < 0) {
                continue;
            }

            var mode = entry.substring(0, space);
            var path = entry.substring(tab + 1);

            if ("120000".equals(mode)) {
                symlinks.add(path);
            }

            if (!OwnedPaths.coveredByAny(allowedPaths, path)) {
                outside.add(path);
            }
        }

        checks.add(symlinks.isEmpty()
                ? StaticCheck.pass("NO_SYMLINKS", "Sin symlinks en HEAD", null, List.of())
                : StaticCheck.fail("NO_SYMLINKS", "Symlinks en el repositorio: " + symlinks, null, symlinks));

        checks.add(outside.isEmpty()
                ? StaticCheck.pass("PATHS_WITHIN_OWNED",
                        entries.size() + " archivo(s) dentro de los ownedPaths del plan", null, List.of())
                : StaticCheck.fail("PATHS_WITHIN_OWNED",
                        "Archivos fuera de los ownedPaths del plan: " + outside, null, outside));

        if (entryPoint == null || entryPoint.isBlank()) {
            checks.add(StaticCheck.fail("ENTRY_POINT", "El plan no declaró entryPoint.", null, List.of()));
            return;
        }

        var normalizedEntryPoint = OwnedPaths.normalize(entryPoint);

        try {
            var content = git.run(dir, "show", "HEAD:" + normalizedEntryPoint);
            checks.add(content.isBlank()
                    ? StaticCheck.fail("ENTRY_POINT", "El entryPoint " + normalizedEntryPoint + " está vacío.",
                            null, List.of(normalizedEntryPoint))
                    : StaticCheck.pass("ENTRY_POINT", "entryPoint " + normalizedEntryPoint + " presente",
                            null, List.of(normalizedEntryPoint)));
        } catch (IOException ex) {
            checks.add(StaticCheck.fail("ENTRY_POINT", "El entryPoint " + normalizedEntryPoint + " no existe en HEAD.",
                    null, List.of(normalizedEntryPoint)));
        }
    }
}
