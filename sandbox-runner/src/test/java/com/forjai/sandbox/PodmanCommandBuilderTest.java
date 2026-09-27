package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PodmanCommandBuilderTest {

    private final PodmanCommandBuilder builder = new PodmanCommandBuilder("unix:///run/podman/podman.sock", Path.of("/home/u/forjai-deps"));

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

    // Verificado en vivo: el SDK de Flutter crea bin/cache/lockfile en cada comando y falla con la raíz de solo
    // lectura. Ese perfil corre con la raíz escribible (descartada con --rm), sin perder el resto del aislamiento.
    @Test
    void flutterRunsWithAWritableRootButKeepsTheRestOfTheIsolation() {
        var flutter = builder.run(ExecutionProfile.FLUTTER_WEB_APP, ExecutionProfile.FLUTTER_WEB_APP.steps().get(0),
                Path.of("/w"));
        assertFalse(flutter.contains("--read-only"));
        assertTrue(flutter.containsAll(java.util.List.of("--network=none", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--userns=keep-id", "--memory=4g", "--rm")));

        for (var profile : java.util.List.of(ExecutionProfile.DOTNET_APP, ExecutionProfile.GODOT_DOTNET_GAME)) {
            assertTrue(builder.run(profile, profile.steps().get(0), Path.of("/w")).contains("--read-only"));
        }
    }

    // Parte 3: la caché aprobada entra a VERIFY sin red. NuGet de solo lectura; pub con overlay (:O), verificado
    // en vivo: pub get --offline escribe en su caché y falla con :ro.
    @Test
    void verifyMountsTheApprovedDependencyCaches() {
        var dotnet = builder.run(ExecutionProfile.DOTNET_APP, ExecutionProfile.DOTNET_APP.steps().get(0), Path.of("/w"));
        assertTrue(dotnet.contains("/home/u/forjai-deps/nuget:/deps/nuget:ro,z"), dotnet.toString());
        var flutter = builder.run(ExecutionProfile.FLUTTER_WEB_APP, ExecutionProfile.FLUTTER_WEB_APP.steps().get(0), Path.of("/w"));
        assertTrue(flutter.contains("/home/u/forjai-deps/pub:/opt/pub-cache:O"), flutter.toString());
        assertTrue(flutter.contains("--network=none"));
    }
}
