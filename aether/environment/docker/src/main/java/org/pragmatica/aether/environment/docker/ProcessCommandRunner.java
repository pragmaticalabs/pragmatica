// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.List;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;

import static org.pragmatica.aether.environment.docker.DockerError.COMMAND_EXECUTION_FAILED;


@Contract
public record ProcessCommandRunner() implements DockerCommandRunner {
    public static ProcessCommandRunner processCommandRunner() {
        return new ProcessCommandRunner();
    }

    @Override
    public Promise<String> execute(List<String> command) {
        return Promise.lift(COMMAND_EXECUTION_FAILED, () -> runProcess(command));
    }

    @Override
    public Promise<String> execute(List<String> command, byte[] stdin) {
        return Promise.lift(COMMAND_EXECUTION_FAILED, () -> runProcess(command, stdin));
    }

    private static String runProcess(List<String> command) throws Exception {
        return runProcess(command, new byte[0]);
    }

    private static String runProcess(List<String> command, byte[] stdin) throws Exception {
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();

        try (var in = process.getOutputStream()) {
            in.write(stdin);
        }

        var output = new String(process.getInputStream().readAllBytes()).trim();
        var exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Docker command failed (exit " + exitCode + "): " + output);
        }

        return output;
    }
}
