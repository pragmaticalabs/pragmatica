// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// T18a (drain D1 r9 §3.6, §7): `drainTenure()` on a real `ClusterTopologyManagerRecord` is the activation epoch
/// while the manager is active and 0 otherwise. Each arm pins one edge of that rule:
/// activate yields a nonzero tenure, a second activate while active leaves it unchanged (the `active` guard),
/// deactivate yields 0, and a re-activation yields a new value (the epoch moved, so a stale holder cannot match).
class CtmTenureActivationTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();

    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        var snapshotSource = new GenerationSnapshotSource() {
            @Override
            public Option<MembershipView> currentMembershipView() {
                return Option.none();
            }

            @Override
            public long observedRabiaTerm() {
                return 0L;
            }
        };
        var info = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var config = new TopologyConfig(SELF, 1, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(info));
        var observer = TopologyObserver.topologyObserver(config, MessageRouter.mutable(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), timeSpan(1).hours()).unwrap();

        ctm = ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                        new IdleLifecycleManager(),
                                                                        autoHeal,
                                                                        DeploymentMap.deploymentMap(),
                                                                        snapshotSource,
                                                                        Option::none,
                                                                        _ -> Promise.success(List.of()),
                                                                        () -> ClusterPhase.NORMAL,
                                                                        System::currentTimeMillis);
    }

    @AfterEach
    void tearDown() {
        ctm.deactivate();
    }

    @Test
    void drainTenure_beforeAnyActivation_isZero() {
        assertThat(ctm.drainTenure()).isZero();
    }

    @Test
    void drainTenure_afterActivate_isNonZero() {
        ctm.activate();

        assertThat(ctm.drainTenure()).isPositive();
    }

    @Test
    void drainTenure_activateWhileActive_isUnchanged() {
        ctm.activate();
        var first = ctm.drainTenure();

        ctm.activate();

        assertThat(ctm.drainTenure()).isEqualTo(first);
    }

    @Test
    void drainTenure_afterDeactivate_isZero() {
        ctm.activate();

        ctm.deactivate();

        assertThat(ctm.drainTenure()).isZero();
    }

    @Test
    void drainTenure_reactivate_isANewValue() {
        ctm.activate();
        var first = ctm.drainTenure();
        ctm.deactivate();

        ctm.activate();

        assertThat(ctm.drainTenure()).isPositive()
                                     .isNotEqualTo(first);
    }

    @Test
    void drainTenure_whileAnotherThreadHoldsTheMonitor_returnsWithinDeadline() {
        ctm.activate();

        synchronized (ctm) {
            assertThat(CompletableFuture.supplyAsync(ctm::drainTenure)).succeedsWithin(Duration.ofSeconds(5));
        }
    }

    /// Never provisions or terminates: activation only lists inventory through the interface defaults.
    private static final class IdleLifecycleManager implements NodeLifecycleManager {
        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return EnvironmentError.operationNotSupported("executeAction").promise();
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return EnvironmentError.operationNotSupported("provisionNode").promise();
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public boolean isCloudManaged() {
            return false;
        }
    }
}
