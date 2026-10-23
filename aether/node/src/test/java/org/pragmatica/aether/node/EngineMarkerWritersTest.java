// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// W-29, "any marker write outside the lineage mint's transaction or the restore batch" (phase-3 design 8.2): a source scan of `aether/node`
/// main. `DeploymentEngineKey` may be named only by the mint (`ClusterIncarnation`, the one writer), by the guard that reads it and by the
/// worker scope switch that exhausts the key types, and by the restore batch (#1533), whose finish sets the marker to what the backup
/// carries (W-34 restore arm). MT-24: a marker written on first activation by the cluster
/// interpreter would name the key in another file and turn this red. The scan reports the file count it covered and a planted file is its
/// positive control.
class EngineMarkerWritersTest {
    private static final Path MAIN = Path.of("src", "main", "java");
    /// The mint writes it, the guard reads it, and `WorkerMetadataIndex` names it in its exhaustive scope switch (it reaches no worker scope).
    /// `DeploymentWiring` names the key type only to recognise its notification as a trigger for the guard's re-read. The restore's finish
    /// (`BackupRestoreCoordinator`) is the restore batch the design names as the marker's only other writer.
    private static final Set<String> ALLOWED = Set.of("ClusterIncarnation.java",
                                                      "EngineMarkerGuard.java",
                                                      "WorkerMetadataIndex.java",
                                                      "DeploymentWiring.java",
                                                      "BackupRestoreCoordinator.java");

    private static List<String> namingTheKey(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                        .filter(EngineMarkerWritersTest::names)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean names(Path file) {
        try {
            return Files.readString(file).contains("DeploymentEngineKey");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long scanned(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void onlyTheMintAndTheGuard_nameTheMarkerKey() {
        assertThat(scanned(MAIN)).as("the scan covered the module's main sources").isGreaterThan(100);
        assertThat(namingTheKey(MAIN)).as("files naming DeploymentEngineKey").isNotEmpty().allMatch(ALLOWED::contains);
    }

    @Test
    void aPlantedWriter_isFlagged(@TempDir Path temp) throws IOException {
        Files.writeString(temp.resolve("Planted.java"), "class Planted { Object key = DeploymentEngineKey.deploymentEngineKey(); }");

        assertThat(namingTheKey(temp)).containsExactly("Planted.java");
        assertThat(namingTheKey(temp)).as("and it would fail the allow-list").doesNotContainAnyElementsOf(ALLOWED);
    }
}
