// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.swim.SwimConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// W-35, the **wired** arm for a core (phase-3 design 12 item 5, P3-Q27, G-6, MQ-7): the node FSM's write-attempt bound and the cluster FSM's lost-write
/// backstop are derived from the consensus `applyTimeout` the node is configured with, so each outlasts the engine's own verdict by a margin and
/// that verdict always arrives first. PR B proved the factory with the real constants; this proves the configuration reaches it. The worker arm
/// (`FORWARD_APPLY_TIMEOUT`, MT-31) is not carried: a worker has no FSM runtime in this PR (N-3).
class Fsm2DerivationsTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1L, 7L);

    private static AetherNodeConfig configuredWith(TimeSpan applyTimeout) {
        var defaults = ProtocolConfig.defaultConfig();
        var protocol = new ProtocolConfig(defaults.cleanupInterval(),
                                          defaults.syncRetryInterval(),
                                          defaults.removeOlderThanPhases(),
                                          defaults.maxPendingBatches(),
                                          applyTimeout,
                                          defaults.decisionGapTimeout(),
                                          defaults.participationMarker());

        return AetherNodeConfig.builder()
                               .self(SELF)
                               .coreNodes(List.of())
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(3)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(Map.of())
                               .backupConfig(Option.none())
                               .membership(Option.none())
                               .streaming(StreamingConfig.streamingConfig())
                               .protocol(protocol)
                               .build();
    }

    @Test
    void theNodeFsmWriteAttempt_outlastsTheConfiguredApplyTimeout() {
        var config = Fsm2System.nodeFsmConfig(configuredWith(TimeSpan.timeSpan(45).seconds()), PROCESS);

        assertThat(config.timings().writeAttemptMs()).as("MT: sourced from the default 30 s instead of the configured 45 s").isEqualTo(50_000L);
        assertThat(config.process()).isEqualTo(PROCESS);
        assertThat(config.self()).isEqualTo(SELF);
    }

    @Test
    void theClusterFsmLostWriteBackstop_isAfterTheConfiguredApplyTimeout() {
        var settings = Fsm2System.clusterSettings(configuredWith(TimeSpan.timeSpan(45).seconds()), PROCESS, SwimConfig.DEFAULT, TimeSpan.timeSpan(15).seconds());

        assertThat(settings.submissionExpiry()).as("applyTimeout + 5 s").isEqualTo(50_000L);
        assertThat(settings.process()).isEqualTo(PROCESS);
        assertThat(settings.self()).isEqualTo(SELF);
    }

    @Test
    void theClusterFsmTick_isTheConfiguredReconciliationInterval() {
        var settings = Fsm2System.clusterSettings(configuredWith(TimeSpan.timeSpan(30).seconds()), PROCESS, SwimConfig.DEFAULT, TimeSpan.timeSpan(15).seconds());

        assertThat(settings.timing().tick()).isEqualTo(30_000L);
    }

    /// H-2 (design 5.3.1, ruled with `coreMembers()` = MEMBER + SUSPECT): a SUSPECT core stays in the core view, so D-4 (ii)(b) is safe only if the
    /// window a core must stay continuously listed for, before it counts as alive again, is at least the longest suspicion window SWIM can arm. SWIM
    /// scales the window by its local health multiplier, saturating at `lhmMaxScore` (not the base timeout): `suspectTimeout x lhmMaxScore`.
    /// Pinned from the real SWIM configurations the node can run with, so a change to either side that breaks the inequality is red.
    @Test
    void theCoreSuspicionWindow_isAtLeastTheLongestScaledSwimSuspicion_forTheRealSwimConfigs() {
        var configs = List.of(SwimConfig.DEFAULT, SwimConfig.fromTimeouts(TimeSpan.timeSpan(1).seconds(), TimeSpan.timeSpan(500).millis(), TimeSpan.timeSpan(3).seconds()));

        for (var swim : configs) {
            var window = Fsm2System.clusterSettings(configuredWith(TimeSpan.timeSpan(30).seconds()), PROCESS, swim, TimeSpan.timeSpan(15).seconds()).bounds().coreSuspicion();
            var longestScaledSuspicion = swim.suspectTimeout().nanos() * swim.lhmMaxScore();

            assertThat(window).as("window (ns) vs suspectTimeout x lhmMaxScore for " + swim).isGreaterThanOrEqualTo(longestScaledSuspicion);
        }
    }

    @Test
    void theEngineMismatchWindow_isTwiceTheWorstCaseElection() {
        var config = configuredWith(TimeSpan.timeSpan(30).seconds());

        // no core nodes in this fixture: base 2 s + retry 0.5 s, doubled
        assertThat(DeploymentWiring.engineMismatchWindowMs(config)).isEqualTo(5_000L);
    }
}
