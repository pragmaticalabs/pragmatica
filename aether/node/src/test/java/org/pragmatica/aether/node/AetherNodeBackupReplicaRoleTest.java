// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.node.backup.KvBackupService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Know 6ee3dc3f0d (P-C): the stop-flush replica class follows the node's own worker role, [AetherNode#configuredWorker] — a `[worker]`
/// configuration OR a topology label `role=worker|spot` — the same test that makes the node a passive client and a replica/projection
/// reader. A worker by label holds the same partial state as one by configuration.
class AetherNodeBackupReplicaRoleTest {
    private static final NodeInfo SELF = NodeInfo.nodeInfo(new NodeId("node-1"), NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());

    private static AetherNodeConfig config(Option<WorkerConfig> workerConfig, NodeInfo self) {
        var config = Mockito.mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF.id());
        when(config.workerConfig()).thenReturn(workerConfig);
        when(config.topology()).thenReturn(new TopologyConfig(SELF.id(), 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(self)));

        return config;
    }

    private static KvBackupService.Replica replicaOf(AetherNodeConfig config) {
        return AetherNode.backupReplica(AetherNode.configuredWorker(config));
    }

    @Test
    void aWorkerByLabel_flushesAsAPartialReplica() {
        for (var role : List.of("worker", "spot", "WORKER")) {
            var labelled = NodeInfo.nodeInfo(SELF.id(), SELF.address(), Map.of(NodeInfo.LABEL_ROLE, role));

            assertThat(replicaOf(config(Option.none(), labelled))).as(role).isEqualTo(KvBackupService.Replica.PARTIAL);
        }
    }

    @Test
    void aWorkerByConfiguration_flushesAsAPartialReplica() {
        assertThat(replicaOf(config(Option.some(Mockito.mock(WorkerConfig.class)), SELF))).isEqualTo(KvBackupService.Replica.PARTIAL);
    }

    @Test
    void aCore_flushesAsAFullReplica() {
        var labelled = NodeInfo.nodeInfo(SELF.id(), SELF.address(), Map.of(NodeInfo.LABEL_ROLE, "core"));

        assertThat(replicaOf(config(Option.none(), SELF))).isEqualTo(KvBackupService.Replica.FULL);
        assertThat(replicaOf(config(Option.none(), labelled))).isEqualTo(KvBackupService.Replica.FULL);
    }

    @Test
    void stop_readsTheReplicaClassFromTheNodesOwnWorkerRole() {
        assertThat(AssemblySource.spaced()).containsOnlyOnce("AetherNode.backupReplica(configuredWorker(config))");
    }
}
