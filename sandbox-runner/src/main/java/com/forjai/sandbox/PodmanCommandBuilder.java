package com.forjai.sandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Argumentos de `podman run` con el aislamiento obligatorio del spec §2. Nunca recibe comandos externos. */
public class PodmanCommandBuilder {

    private final String podmanUrl;

    public PodmanCommandBuilder(String podmanUrl) {
        this.podmanUrl = podmanUrl;
    }

    public List<String> run(ExecutionProfile profile, ExecutionProfile.Step step, Path workDir) {
        var args = new ArrayList<>(List.of("podman", "--url", podmanUrl, "run", "--rm", "--network=none"));
        if (profile.readOnlyRoot()) {
            args.add("--read-only");
        }
        args.addAll(List.of(
                "--tmpfs", "/tmp:rw,exec,size=2g",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--userns=keep-id",
                "--memory=4g",
                "--cpus=4",
                "--pids-limit=512",
                "--timeout=" + step.timeoutSeconds(),
                "-e", "HOME=/tmp",
                "-e", "DOTNET_CLI_HOME=/tmp",
                "-e", "DOTNET_CLI_TELEMETRY_OPTOUT=1",
                "-v", workDir + ":/work:Z",
                "-w", "/work",
                profile.image(), "/forjai/run.sh", step.name()));
        return List.copyOf(args);
    }
}
