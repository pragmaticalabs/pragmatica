// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AssemblySource;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #1968: a leader without `[backup]` raised `backup-config-missing`, whose recovery only its own event layer can raise. The
/// coordinator's behaviour is pinned by `BackupRestoreCoordinatorTest`; whether a STOPPING node reaches it is decided by three
/// places in `AetherNode`. A node cannot be assembled without a cluster and a lone node never leads, so this is a SOURCE
/// tripwire, honest about being one (like `AetherNodeArtifactStoreWiringTest`): the coordinator's stop is handed to both node
/// constructions, and `stop()` runs it before the cluster-event layer and the backup service go down.
class AetherNodeBackupCoordinatorStopWiringTest {
    @Test
    void stop_endsTheBackupCoordinatorsTerm_beforeTheBackupServiceStops() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("backupCoordinatorStop.run();")
                          .containsOnlyOnce("service.stopFlushed(BACKUP_STOP_FLUSH_BOUND, backupReplica())");

        // Offsets are comparable because `stop()` is one method in one file; the joined text puts `AetherNode.java` first.
        var stopsCoordinator = source.indexOf("backupCoordinatorStop.run();");
        var stopsService = source.indexOf("service.stopFlushed(BACKUP_STOP_FLUSH_BOUND, backupReplica())");

        assertThat(stopsCoordinator).as("stop() runs the coordinator's stop").isPositive();
        assertThat(stopsCoordinator).as("and does so before the backup service stops").isLessThan(stopsService);
    }

    /// Know 3b01e624f1: the flush is started before the teardown and the stop sequence runs only after it resolves, so a graceful stop
    /// completes after the backup flush (bounded by the deadline), not beside it.
    @Test
    void stop_runsTheStopSequence_onlyAfterTheBackupFlush() {
        var source = AssemblySource.spaced();

        assertThat(source).containsOnlyOnce("backupFlushed.flatMap(() -> NodeStopSequence.run(");
    }

    /// Know 6ee3dc3f0d (P-C): a worker holds a partial replica and must not stop-flush; the role is the node's own worker role (configuration or label,
    /// pinned by `AetherNodeBackupReplicaRoleTest`).
    @Test
    void stop_flushesAsAPartialReplica_onlyForAWorker() {
        assertThat(AetherNode.backupReplica(true)).isEqualTo(KvBackupService.Replica.PARTIAL);
        assertThat(AetherNode.backupReplica(false)).isEqualTo(KvBackupService.Replica.FULL);
        assertThat(AssemblySource.spaced()).containsOnlyOnce("AetherNode.backupReplica(configuredWorker(config))");
    }

    @Test
    void theBackupFlushBound_isTenSeconds_asTheConfigurationReferenceStates() {
        assertThat(AetherNode.BACKUP_STOP_FLUSH_BOUND).isEqualTo(TimeSpan.timeSpan(10).seconds());
    }

    @Test
    void bothNodeConstructions_handTheCoordinatorsStop() {
        var source = AssemblySource.spaced();

        assertThat(source.split("backupRestoreCoordinator::onNodeStopping", -1).length - 1)
            .as("the primary and the delegating construction each pass the coordinator's stop")
            .isEqualTo(2);
    }
}
