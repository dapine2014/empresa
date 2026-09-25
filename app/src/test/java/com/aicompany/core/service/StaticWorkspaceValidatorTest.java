package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.model.StaticCheck;
import com.aicompany.core.service.StaticWorkspaceValidator.CommittedWork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StaticWorkspaceValidatorTest {

    @TempDir
    Path tempRoot;

    private final GitCommandRunner git = new GitCommandRunner();
    private DevelopmentWorkspaceService workspace;
    private StaticWorkspaceValidator validator;
    private final List<CommittedWork> work = new ArrayList<>();
    private static final List<String> ALLOWED = List.of("web/index.html", "web/game");

    @BeforeEach
    void setUp() throws Exception {
        workspace = new DevelopmentWorkspaceService(tempRoot.toString(), git);
        validator = new StaticWorkspaceValidator(workspace, git);

        var neo = workspace.commitAgentWork("M-1", "M-1-ENGINEERING", "engineering", "Neo",
                new DevelopmentResult("base", List.of(new GeneratedFile("web/index.html", "<html></html>"))));
        work.add(new CommittedWork("M-1-ENGINEERING", "engineering", neo.sha(), neo.files()));

        var iris = workspace.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
                new DevelopmentResult("lógica", List.of(new GeneratedFile("web/game/main.js", "export const x = 1;"))));
        work.add(new CommittedWork("M-1-BACKEND", "backend", iris.sha(), iris.files()));
    }

    private static StaticCheck find(List<StaticCheck> checks, String name) {
        return checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void allChecksPassForARealWellFormedRepository() {
        var checks = validator.validate("M-1", work, "web/index.html", ALLOWED);
        assertTrue(checks.stream().allMatch(StaticCheck::passed), checks.toString());
    }

    @Test
    void failsWhenTheCommitAuthorIsNotTheAgent() {
        var wrong = new CommittedWork("M-1-BACKEND", "frontend-ui", work.get(1).commitSha(), work.get(1).files());
        var checks = validator.validate("M-1", List.of(work.get(0), wrong), "web/index.html", ALLOWED);
        assertTrue(checks.stream().anyMatch(c -> c.check().equals("COMMIT_AUTHOR") && !c.passed()), checks.toString());
    }

    @Test
    void failsWhenTheTrailerDoesNotMatchTheTask() {
        var wrong = new CommittedWork("M-1-OTRA", "backend", work.get(1).commitSha(), work.get(1).files());
        var checks = validator.validate("M-1", List.of(wrong), "web/index.html", ALLOWED);
        assertFalse(find(checks, "COMMIT_TASK_TRAILER").passed());
    }

    @Test
    void failsWhenADeclaredFileIsNotInTheCommit() {
        var wrong = new CommittedWork("M-1-BACKEND", "backend", work.get(1).commitSha(), List.of("web/game/fantasma.js"));
        var checks = validator.validate("M-1", List.of(wrong), "web/index.html", ALLOWED);
        assertFalse(find(checks, "FILES_IN_COMMIT").passed());
    }

    @Test
    void failsForAShaThatDoesNotExist() {
        var wrong = new CommittedWork("M-1-BACKEND", "backend", "d".repeat(40), List.of("web/game/main.js"));
        var checks = validator.validate("M-1", List.of(wrong), "web/index.html", ALLOWED);
        assertFalse(find(checks, "COMMIT_EXISTS").passed());
    }

    @Test
    void failsWhenTheEntryPointIsMissing() {
        var checks = validator.validate("M-1", work, "web/main.html", List.of("web"));
        assertFalse(find(checks, "ENTRY_POINT").passed());
    }

    @Test
    void failsWhenFilesAreOutsideTheOwnedPaths() {
        var checks = validator.validate("M-1", work, "web/index.html", List.of("web/index.html"));
        assertFalse(find(checks, "PATHS_WITHIN_OWNED").passed());
    }

    @Test
    void failsWhenTheRepositoryContainsASymlink() throws Exception {
        var dir = workspace.missionWorkspace("M-1");
        Files.createSymbolicLink(dir.resolve("web/game/link.js"), Path.of("/etc/passwd"));
        git.run(dir, "add", "web/game/link.js");
        git.run(dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", "symlink");

        var checks = validator.validate("M-1", work, "web/index.html", ALLOWED);
        assertFalse(find(checks, "NO_SYMLINKS").passed());
    }

    @Test
    void failsWhenThereIsNoRepository() {
        var checks = validator.validate("M-SIN-REPO", work, "web/index.html", ALLOWED);
        assertEquals(1, checks.size());
        assertFalse(checks.get(0).passed());
    }
}
