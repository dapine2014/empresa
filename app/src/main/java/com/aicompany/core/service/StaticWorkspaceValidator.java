package com.aicompany.core.service;

import com.aicompany.core.agent.validation.DddLayerChecker;
import com.aicompany.core.agent.validation.OwnedPaths;
import com.aicompany.core.agent.validation.ProfileStructureChecker;
import com.aicompany.core.agent.validation.ComponentStructureChecker;
import com.aicompany.core.model.AppComponent;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            String missionId, List<CommittedWork> work, StackProfile profile, List<String> contexts,
            List<String> allowedPaths) {
        return validate(missionId, work, profile == null ? List.of() : List.of(new AppComponent(profile, "")),
                contexts, allowedPaths, List.of());
    }

    /** Spec 2026-10-02 B1 §3: chequeos por componente. forjaiFiles = archivos que escribe Java (api_routes.dart). */
    public List<StaticCheck> validate(
            String missionId, List<CommittedWork> work, List<AppComponent> components, List<String> contexts,
            List<String> allowedPaths, List<String> forjaiFiles) {

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

        validateHead(dir, components, contexts, allowedPaths, forjaiFiles, checks);

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

    private void validateHead(
            Path dir, List<AppComponent> components, List<String> contexts, List<String> allowedPaths,
            List<String> forjaiFiles, List<StaticCheck> checks) {

        List<String> entries;

        try {
            entries = DevelopmentWorkspaceService.lines(git.run(dir, "ls-tree", "-r", "HEAD"));
        } catch (IOException ex) {
            checks.add(StaticCheck.fail("HEAD_READABLE", "No se pudo leer HEAD: " + ex.getMessage(), null, List.of()));
            return;
        }

        var symlinks = new ArrayList<String>();
        var outside = new ArrayList<String>();
        var paths = new ArrayList<String>();

        for (var entry : entries) {

            var tab = entry.indexOf('\t');
            var space = entry.indexOf(' ');

            if (tab < 0 || space < 0) {
                continue;
            }

            var mode = entry.substring(0, space);
            var path = entry.substring(tab + 1);
            paths.add(path);

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

        if (components.isEmpty()) {
            checks.add(StaticCheck.fail("STACK_PROFILE", "El plan no declaró un stackProfile del catálogo.",
                    null, List.of()));
            return;
        }

        var contents = new LinkedHashMap<String, String>();
        try {
            for (var path : paths) {
                var source = forjaiFiles.contains(path) || components.stream().anyMatch(c -> c.owns(path)
                        && (path.endsWith(c.profile().sourceExtension()) || path.equals(c.prefix("pubspec.yaml"))));
                if (source) {
                    contents.put(path, git.run(dir, "show", "HEAD:" + path));
                }
            }
        } catch (IOException ex) {
            checks.add(StaticCheck.fail("DDD_LAYERS", "No se pudo leer el código para el chequeo DDD: "
                    + ex.getMessage(), null, List.of()));
            return;
        }

        checks.addAll(ComponentStructureChecker.check(components, contexts, paths, contents, forjaiFiles));
    }
}
