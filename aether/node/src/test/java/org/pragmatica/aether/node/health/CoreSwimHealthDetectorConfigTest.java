// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.health;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.config.TimeoutsConfig.SwimTimeouts;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthSignalSink;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.SwimConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Theme A — Fix 1: regression test that the toml-parsed `[timeouts.swim]` config flows
/// through `SwimConfig.fromTimeouts(...)` and into `CoreSwimHealthDetector` instead of being
/// silently overwritten by `SwimConfig.DEFAULT`.
///
/// Before the fix `CoreSwimHealthDetector` hardcoded `SwimConfig.DEFAULT`
/// (suspectTimeout=10s) regardless of the parsed config — pushing single-kill detection
/// latency to ~11-13s. After the fix the detector honors caller-supplied timeouts.
class CoreSwimHealthDetectorConfigTest {

    private static final NodeId SELF = new NodeId("self-1");
    private static final NodeId PEER = new NodeId("peer-1");

    @Test
    void fromTimeouts_swimTimeoutsDefaults_areMappedDirectly() {
        var defaults = SwimTimeouts.swimTimeouts();
        var built = SwimConfig.fromTimeouts(defaults.period(), defaults.probeTimeout(), defaults.suspectTimeout());

        assertThat(built.period()).isEqualTo(defaults.period());
        assertThat(built.probeTimeout()).isEqualTo(defaults.probeTimeout());
        assertThat(built.suspectTimeout()).isEqualTo(defaults.suspectTimeout());
    }

    @Test
    void fromTimeouts_preservesNonExposedConstantsFromDefault() {
        var built = SwimConfig.fromTimeouts(timeSpan(2).seconds(), timeSpan(750).millis(), timeSpan(4).seconds());

        // The four fields that are NOT exposed at the SwimTimeouts boundary must equal
        // SwimConfig.DEFAULT's tuning constants — preserves indirect probing, piggyback,
        // revival grace and startup delay.
        assertThat(built.indirectProbes()).isEqualTo(SwimConfig.DEFAULT.indirectProbes());
        assertThat(built.maxPiggyback()).isEqualTo(SwimConfig.DEFAULT.maxPiggyback());
        assertThat(built.revivalGrace()).isEqualTo(SwimConfig.DEFAULT.revivalGrace());
        assertThat(built.startupDelay()).isEqualTo(SwimConfig.DEFAULT.startupDelay());
    }

    @Test
    void fromTimeouts_swimTimeoutsDefaults_matchLegacySwimConfigDefault() {
        // The toml default suspectTimeout matches `SwimConfig.DEFAULT` (10s). The wiring
        // win is that operators can lower it via `[timeouts.swim] suspect_timeout = "5s"`
        // for faster detection on stable hardware, without code change.
        var defaults = SwimTimeouts.swimTimeouts();

        assertThat(defaults.suspectTimeout().millis())
                .isEqualTo(SwimConfig.DEFAULT.suspectTimeout().millis());
    }

    @Test
    void coreSwimHealthDetector_customSwimConfig_isHonoredEndToEnd() {
        // Build a detector with a non-default SwimConfig (suspectTimeout=3s) and verify it
        // is the value visible at the FSM boundary (no static override path remains).
        var custom = SwimConfig.fromTimeouts(timeSpan(1).seconds(), timeSpan(400).millis(), timeSpan(3).seconds());

        var detector = buildDetector(custom);
        var ctxConfig = detector.contextForTest().swimConfig();

        assertThat(ctxConfig).isEqualTo(custom);
        assertThat(ctxConfig.suspectTimeout()).isEqualTo(timeSpan(3).seconds());
    }

    @Test
    void coreSwimHealthDetector_defaultFactory_fallsBackToSwimConfigDefault() {
        // Backward-compat: factories that don't take a SwimConfig still produce the
        // legacy SwimConfig.DEFAULT — guards against silent regression for callers that
        // have not yet been migrated.
        var router = MessageRouter.mutable();
        Serializer serializer = Mockito.mock(Serializer.class);
        Deserializer deserializer = Mockito.mock(Deserializer.class);
        var topology = topologyConfig();

        var detector = CoreSwimHealthDetector.coreSwimHealthDetector(router, topology, serializer, deserializer);

        assertThat(detector.contextForTest().swimConfig()).isEqualTo(SwimConfig.DEFAULT);
    }

    private static CoreSwimHealthDetector buildDetector(SwimConfig swimConfig) {
        var router = MessageRouter.mutable();
        Serializer serializer = Mockito.mock(Serializer.class);
        Deserializer deserializer = Mockito.mock(Deserializer.class);
        return CoreSwimHealthDetector.coreSwimHealthDetector(router,
                                                              topologyConfig(),
                                                              serializer,
                                                              deserializer,
                                                              HealthSignalSink.noop(),
                                                              () -> Epoch.ZERO,
                                                              () -> true,
                                                              PeerObservationStore.peerObservationStore(),
                                                              swimConfig);
    }

    private static TopologyConfig topologyConfig() {
        var nodeSelf = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var nodePeer = NodeInfo.nodeInfo(PEER, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        return new TopologyConfig(SELF, 2, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                   List.of(nodeSelf, nodePeer));
    }
}
