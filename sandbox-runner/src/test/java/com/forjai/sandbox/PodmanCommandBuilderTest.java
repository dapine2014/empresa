package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PodmanCommandBuilderTest {

    private final PodmanCommandBuilder builder = new PodmanCommandBuilder("unix:///run/podman/podman.sock");

    @Test
    void everyRunCarriesTheMandatoryIsolation() {
        var step = ExecutionProfile.GODOT_DOTNET_GAME.steps().get(1);
        var args = builder.run(ExecutionProfile.GODOT_DOTNET_GAME, step, Path.of("/home/alex/forjai-sandbox-work/job-1"));

        assertEquals("podman", args.get(0));
        assertTrue(args.containsAll(java.util.List.of("--network=none", "--read-only", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--userns=keep-id", "--memory=4g", "--cpus=4",
                "--pids-limit=512", "--rm")), args.toString());
        assertTrue(args.contains("--timeout=" + step.timeoutSeconds()));
        assertTrue(args.contains("/home/alex/forjai-sandbox-work/job-1:/work:Z"));
        assertEquals(java.util.List.of(ExecutionProfile.GODOT_DOTNET_GAME.image(), "/forjai/run.sh", "build"),
                args.subList(args.size() - 3, args.size()));
    }

    @Test
    void theStepNameNeverComesFromOutsideTheCatalog() {
        var args = builder.run(ExecutionProfile.DOTNET_APP, ExecutionProfile.DOTNET_APP.steps().get(0),
                Path.of("/w"));
        assertEquals("restore", args.get(args.size() - 1));
    }
}
