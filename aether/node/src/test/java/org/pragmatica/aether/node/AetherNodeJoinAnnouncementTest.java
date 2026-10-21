// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.config.WorkerConfig;
import org.pragmatica.aether.node.assembly.SwimAssembly;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1840: which announcement a node runs at transport-ready. A worker holds its ANNOUNCE to the seeds until
/// its activation directive is committed; a core announces at once. The trigger does nothing until run.
class AetherNodeJoinAnnouncementTest {
    private static final NodeInfo SELF = NodeInfo.nodeInfo(new NodeId("node-1"), NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
    private static final List<InetSocketAddress> SEEDS = List.of(new InetSocketAddress("127.0.0.2", 9101));

    private static final TimeSpan ABSENCE = TimeoutsConfig.ClusterTimeouts.clusterTimeouts().communityAbsence();

    private final CoreSwimHealthDetector detector = Mockito.mock(CoreSwimHealthDetector.class);

    private static AetherNodeConfig config(Option<WorkerConfig> workerConfig) {
        return config(workerConfig, SELF);
    }

    private static AetherNodeConfig config(Option<WorkerConfig> workerConfig, NodeInfo self) {
        var config = Mockito.mock(AetherNodeConfig.class);

        when(config.self()).thenReturn(SELF.id());
        when(config.workerConfig()).thenReturn(workerConfig);
        when(config.timeouts()).thenReturn(TimeoutsConfig.timeoutsConfig());
        when(config.topology()).thenReturn(new TopologyConfig(SELF.id(), 5, timeSpan(1).seconds(), timeSpan(10).seconds(), List.of(self)));

        return config;
    }

    @Test
    void joinAnnouncement_configuredWorker_holdsTheAnnounceUntilAssigned() {
        var trigger = SwimAssembly.joinAnnouncement(config(Option.some(Mockito.mock(WorkerConfig.class))), detector, SELF, "c", 5L, 7L, SEEDS);

        verifyNoInteractions(detector);
        trigger.run();

        verify(detector).announceJoinWhenAssigned(SELF, "c", 5L, 7L, SEEDS, ABSENCE);
        verify(detector, never()).announceJoin(SELF, "c", 5L, 7L, SEEDS);
    }

    @Test
    void joinAnnouncement_workerLabelledInTheTopology_holdsTheAnnounceUntilAssigned() {
        var labelled = NodeInfo.nodeInfo(SELF.id(), SELF.address(), Map.of(NodeInfo.LABEL_ROLE, "worker"));
        var trigger = SwimAssembly.joinAnnouncement(config(Option.none(), labelled), detector, SELF, "c", 5L, 7L, SEEDS);

        trigger.run();

        verify(detector).announceJoinWhenAssigned(SELF, "c", 5L, 7L, SEEDS, ABSENCE);
        verify(detector, never()).announceJoin(SELF, "c", 5L, 7L, SEEDS);
    }

    @Test
    void joinAnnouncement_core_announcesAtOnce() {
        var trigger = SwimAssembly.joinAnnouncement(config(Option.none()), detector, SELF, "c", 5L, 7L, SEEDS);

        verifyNoInteractions(detector);
        trigger.run();

        verify(detector).announceJoin(SELF, "c", 5L, 7L, SEEDS);
        verify(detector, never()).announceJoinWhenAssigned(SELF, "c", 5L, 7L, SEEDS, ABSENCE);
    }
}
