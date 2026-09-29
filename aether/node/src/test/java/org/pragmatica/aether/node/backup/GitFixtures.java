// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Real git for the backup tests: bare remotes in temp directories, and a direct `git` for assertions.
final class GitFixtures {
    private GitFixtures() {}

    /// Create a bare repository and return its path as a remote URL.
    static String bareRemote(Path path) {
        run(List.of("git", "init", "--quiet", "--bare", path.toString()));

        return path.toString();
    }

    /// Run `git -C dir args` and return its output; fail the test on a non-zero exit.
    static String git(Path dir, String... args) {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));

        command.addAll(List.of(args));

        return run(command);
    }

    private static String run(List<String> command) {
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true)
                                                     .start();
            var output = new String(process.getInputStream()
                                           .readAllBytes(),
                                    StandardCharsets.UTF_8);

            if (process.waitFor() != 0) {
                throw new AssertionError(String.join(" ", command) + " failed: " + output);
            }

            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();

            throw new AssertionError(e);
        }
    }
}
