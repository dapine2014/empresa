package com.forjai.sandbox;

import java.nio.file.Path;
import java.util.List;

public interface ProcessExecutor {

    record Execution(int exitCode, String output, boolean timedOut) {
    }

    Execution run(List<String> command, Path directory, long timeoutSeconds);
}
