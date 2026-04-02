package org.pragmatica.aether.environment.docker;

import org.pragmatica.lang.Promise;

import java.util.List;

import static org.pragmatica.aether.environment.docker.DockerError.COMMAND_EXECUTION_FAILED;

/// Docker command runner that delegates to the system process builder.
/// Uses ProcessBuilder to invoke Docker CLI commands and captures stdout.
public record ProcessCommandRunner() implements DockerCommandRunner {
    /// Factory method for creating a ProcessCommandRunner.
    public static ProcessCommandRunner processCommandRunner() {
        return new ProcessCommandRunner();
    }

    @Override public Promise<String> execute(List<String> command) {
        return Promise.lift(COMMAND_EXECUTION_FAILED, () -> runProcess(command));
    }

    // --- Leaf: run a system process and capture stdout ---
    private static String runProcess(List<String> command) throws Exception {
        var process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();

        var output = new String(process.getInputStream().readAllBytes()).trim();
        var exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Docker command failed (exit " + exitCode + "): " + output);
        }

        return output;
    }
}
