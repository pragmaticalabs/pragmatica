// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/// #1968: a leader without `[backup]` raised `backup-config-missing`, whose recovery only its own event layer can raise. The
/// coordinator's behaviour is pinned by `BackupRestoreCoordinatorTest`; whether a STOPPING node reaches it is decided by three
/// places in `AetherNode`. A node cannot be assembled without a cluster and a lone node never leads, so this is a SOURCE
/// tripwire, honest about being one (like `AetherNodeArtifactStoreWiringTest`): the coordinator's stop is handed to both node
/// constructions, and `stop()` runs it before the cluster-event layer and the backup service go down.
class AetherNodeBackupCoordinatorStopWiringTest {
    private static final Path NODE = Path.of("src", "main", "java", "org", "pragmatica", "aether", "node", "AetherNode.java");

    @Test
    void stop_endsTheBackupCoordinatorsTerm_beforeTheBackupServiceStops() throws IOException {
        var source = Files.readString(NODE).replaceAll("\\s+", " ");
        var stopsCoordinator = source.indexOf("backupCoordinatorStop.run();");
        var stopsService = source.indexOf("kvBackupService.onPresent(KvBackupService::stop);");

        assertThat(stopsCoordinator).as("stop() runs the coordinator's stop").isPositive();
        assertThat(stopsCoordinator).as("and does so before the backup service stops").isLessThan(stopsService);
    }

    @Test
    void bothNodeConstructions_handTheCoordinatorsStop() throws IOException {
        var source = Files.readString(NODE).replaceAll("\\s+", " ");

        assertThat(source.split("backupRestoreCoordinator::onNodeStopping", -1).length - 1)
            .as("the primary and the delegating construction each pass the coordinator's stop")
            .isEqualTo(2);
    }
}
