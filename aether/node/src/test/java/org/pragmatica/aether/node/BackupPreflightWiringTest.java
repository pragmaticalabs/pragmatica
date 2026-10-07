// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2007: the boot path runs the `[backup]` git preflight BEFORE anything else is built, and only when `[backup]` is enabled. A
/// source pin like the other wiring tests: an unreadable file fails loudly, because a pin that scans nothing always passes.
class BackupPreflightWiringTest {
    @Test
    void createNode_refusesTheBootWhenBackupIsEnabledAndGitCannotRun_beforeBuildingAnything() {
        var code = assemblyCode();

        assertThat(code).contains("returnrequireBackupGit(config).flatMap(_->ClusterEventsLimits.clusterEventsLimits(environment)).flatMap(limits->createNodeWithBootToken(config,");
        assertThat(code).as("only an enabled [backup] is probed")
                        .contains("privatestaticResult<Unit>requireBackupGit(AetherNodeConfigconfig){returnenabledBackup(config).fold(()->Result.success(Unit.unit()),_->BackupPreflight.requireGit());}");
    }

    private static String assemblyCode() {
        var file = sourceRoot().resolve("org/pragmatica/aether/node/AetherNode.java");

        assertThat(file).exists();

        return readFile(file).lines()
                             .map(line -> line.replaceFirst("//.*$", ""))
                             .collect(Collectors.joining())
                             .replaceAll("\\s+", "");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(BackupPreflightWiringTest.class.getProtectionDomain()
                                                                      .getCodeSource()
                                                                      .getLocation()
                                                                      .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }
}
