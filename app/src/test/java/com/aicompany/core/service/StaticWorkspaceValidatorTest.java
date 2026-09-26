package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.model.StackProfile;
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

    private static final List<String> ALLOWED = List.of("Juego.sln", "game", "src/Combate.Domain", "src/Combate.Application");
    private static final List<String> CONTEXTS = List.of("Combate");
    private static final StackProfile PROFILE = StackProfile.GODOT_DOTNET_GAME;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new DevelopmentWorkspaceService(tempRoot.toString(), git);
        validator = new StaticWorkspaceValidator(workspace, git);

        var neo = workspace.commitAgentWork("M-1", "M-1-ENGINEERING", "engineering", "Neo",
                new DevelopmentResult("base", List.of(
                        new GeneratedFile("Juego.sln", "Microsoft Visual Studio Solution File"),
                        new GeneratedFile("game/project.godot", "config_version=5"),
                        new GeneratedFile("game/Main.cs", "using Godot;\nusing Combate.Application;"))));
        work.add(new CommittedWork("M-1-ENGINEERING", "engineering", neo.sha(), neo.files()));

        var iris = workspace.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
                new DevelopmentResult("dominio", List.of(
                        new GeneratedFile("src/Combate.Domain/Unidad.cs", "namespace Combate.Domain;\npublic class Unidad {}"))));
        work.add(new CommittedWork("M-1-BACKEND", "backend", iris.sha(), iris.files()));
    }

    private List<StaticCheck> validate(List<CommittedWork> items, List<String> allowed) {
        return validator.validate("M-1", items, PROFILE, CONTEXTS, allowed);
    }

    private static StaticCheck find(List<StaticCheck> checks, String name) {
        return checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void allChecksPassForARealWellFormedRepository() {
        var checks = validate(work, ALLOWED);
        assertTrue(checks.stream().allMatch(StaticCheck::passed), checks.toString());
    }

    @Test
    void theHappyPathIncludesTheNewChecks() {
        var names = validate(work, ALLOWED).stream().map(StaticCheck::check).toList();
        assertTrue(names.containsAll(List.of("ENTRY_FILES", "PROFILE_STRUCTURE", "DDD_LAYERS")), names.toString());
    }

    @Test
    void failsWhenTheCommitAuthorIsNotTheAgent() {
        var wrong = new CommittedWork("M-1-BACKEND", "frontend-ui", work.get(1).commitSha(), work.get(1).files());
        var checks = validate(List.of(work.get(0), wrong), ALLOWED);
        assertTrue(checks.stream().anyMatch(c -> c.check().equals("COMMIT_AUTHOR") && !c.passed()), checks.toString());
    }

    @Test
    void failsWhenTheTrailerDoesNotMatchTheTask() {
        var wrong = new CommittedWork("M-1-OTRA", "backend", work.get(1).commitSha(), work.get(1).files());
        assertFalse(find(validate(List.of(wrong), ALLOWED), "COMMIT_TASK_TRAILER").passed());
    }

    @Test
    void failsWhenADeclaredFileIsNotInTheCommit() {
        var wrong = new CommittedWork("M-1-BACKEND", "backend", work.get(1).commitSha(), List.of("src/Combate.Domain/Fantasma.cs"));
        assertFalse(find(validate(List.of(wrong), ALLOWED), "FILES_IN_COMMIT").passed());
    }

    @Test
    void failsForAShaThatDoesNotExist() {
        var wrong = new CommittedWork("M-1-BACKEND", "backend", "d".repeat(40), List.of("src/Combate.Domain/Unidad.cs"));
        assertFalse(find(validate(List.of(wrong), ALLOWED), "COMMIT_EXISTS").passed());
    }

    @Test
    void failsWhenFilesAreOutsideTheOwnedPaths() {
        assertFalse(find(validate(work, List.of("Juego.sln")), "PATHS_WITHIN_OWNED").passed());
    }

    @Test
    void failsWhenTheRepositoryContainsASymlink() throws Exception {
        var dir = workspace.missionWorkspace("M-1");
        Files.createSymbolicLink(dir.resolve("game/link.cs"), Path.of("/etc/passwd"));
        git.run(dir, "add", "game/link.cs");
        git.run(dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", "symlink");

        assertFalse(find(validate(work, ALLOWED), "NO_SYMLINKS").passed());
    }

    @Test
    void failsWhenThereIsNoRepository() {
        var checks = validator.validate("M-SIN-REPO", work, PROFILE, CONTEXTS, ALLOWED);
        assertEquals(1, checks.size());
        assertFalse(checks.get(0).passed());
    }

    @Test
    void aDomainFileImportingGodotFailsDddLayers() throws Exception {
        var bad = workspace.commitAgentWork("M-1", "M-1-DEVOPS", "devops", "Diego",
                new DevelopmentResult("mal", List.of(
                        new GeneratedFile("src/Combate.Domain/Mala.cs", "using Godot;\nnamespace Combate.Domain;"))));
        var items = new ArrayList<>(work);
        items.add(new CommittedWork("M-1-DEVOPS", "devops", bad.sha(), bad.files()));

        var ddd = find(validate(items, ALLOWED), "DDD_LAYERS");
        assertFalse(ddd.passed());
        assertTrue(ddd.detail().contains("src/Combate.Domain/Mala.cs"));
    }

    @Test
    void aMissingProjectGodotFailsEntryFiles() throws Exception {
        var dir = workspace.missionWorkspace("M-1");
        git.run(dir, "rm", "-q", "game/project.godot");
        git.run(dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", "rm");
        assertFalse(find(validate(work, ALLOWED), "ENTRY_FILES").passed());
    }

    @Test
    void aFileOutsideTheProfileStructureFails() throws Exception {
        var extra = workspace.commitAgentWork("M-1", "M-1-DEVOPS", "devops", "Diego",
                new DevelopmentResult("utils", List.of(new GeneratedFile("utils/Helper.cs", "class Helper {}"))));
        var items = new ArrayList<>(work);
        items.add(new CommittedWork("M-1-DEVOPS", "devops", extra.sha(), extra.files()));

        assertFalse(find(validate(items, List.of("Juego.sln", "game", "src/Combate.Domain", "utils")), "PROFILE_STRUCTURE").passed());
    }

    @Test
    void aPlanWithoutProfileFailsStackProfile() {
        var checks = validator.validate("M-1", work, null, CONTEXTS, ALLOWED);
        assertFalse(find(checks, "STACK_PROFILE").passed());
    }

    // Verificado en vivo (MISSION-DDD-VERIFY-6): git devolvía "L\303\263gicaCombate.cs" (entrecomillado) para
    // nombres con tildes y los chequeos fallaban sin motivo real.
    @Test
    void fileNamesWithAccentsAreComparedLiterally() throws Exception {
        var accents = workspace.commitAgentWork("M-1", "M-1-DEVOPS", "devops", "Diego",
                new DevelopmentResult("tildes", List.of(
                        new GeneratedFile("src/Combate.Application/LógicaCombate.cs", "namespace Combate.Application;"))));
        var items = new ArrayList<>(work);
        items.add(new CommittedWork("M-1-DEVOPS", "devops", accents.sha(), accents.files()));

        var checks = validate(items, ALLOWED);

        assertTrue(checks.stream().allMatch(StaticCheck::passed), checks.toString());
    }
}
