// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.wiring.SliceRuntimeAdapter;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The FSM2 system over the production route table, a real `KVStore` applier and real interpreters (phase-3 design 4.1, 4.4, 5.1, 8.3): the
/// route-level gates of the quorum edge (W-04, MT-05), the self-ready signal (W-22), the activation level-heal (W-21, B3), the config hook (W-20,
/// N-2), core membership (W-23), the worker exclusion (N-3), the stop (B13) and the seams (W-14, W-33 wired), and the stamp read inside the
/// dispatch across a real snapshot (PR C obligation 1).
class Fsm2SystemTest {
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();

    private static boolean activeNode(Fsm2Harness harness) {
        return harness.system.map(system -> system.nodeInterpreter().model().active()).or(false);
    }

    private static void enterQuorum(Fsm2Harness harness) {
        harness.router.route(ClusterStateNotification.active());
        harness.awaitThat(() -> activeNode(harness));
    }

    @Nested
    class QuorumEdge {
        /// W-22: the self-ready signal is driven by the `SelfReady` effect of the Active entry, not before it.
        @Test
        void selfReady_isDrivenByTheActiveEntry_notBefore() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                assertThat(harness.selfReady).hasValue(0);

                enterQuorum(harness);

                assertThat(harness.selfReady.get()).as("MT-14e: the effect drives the signal").isGreaterThanOrEqualTo(1);
            }
        }

        /// W-04 / MT-05: an out-of-sequence notification is dropped by the route, counted; the FSM never sees it.
        @Test
        void anOutOfSequenceNotification_isDropped_andTheNodeStaysActive() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var stale = ClusterStateNotification.passive();

                enterQuorum(harness);
                harness.router.route(ClusterStateNotification.active());
                harness.router.route(stale);
                harness.awaitThat(() -> harness.registry.find("aether_deployment_fsm_inputs_total").tags("input", "QuorumEstablished", "result", "dropped_stale_sequence").counters().size() == 1);

                assertThat(activeNode(harness)).as("the stale PASSIVE never reached the FSM").isTrue();
            }
        }

        /// W-04 / MT-05: a demotion is not a quorum loss (#1790): the route drops it, counted, and the node keeps serving.
        @Test
        void aDemotion_isDropped_butAGenuinePassive_suspendsTheNode() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                enterQuorum(harness);

                harness.router.route(ClusterStateNotification.demotion());
                harness.awaitThat(() -> harness.count("QuorumDisappeared", "dropped_demotion", "node") == 1.0);
                assertThat(activeNode(harness)).as("B-105/106: the node keeps its slices serving").isTrue();

                harness.router.route(ClusterStateNotification.passive());
                harness.awaitThat(() -> !activeNode(harness));
            }
        }

        /// B3 / W-21: the 5 s level-heal re-requests entry while consensus is live and the node is still Dormant; a dead consensus does nothing.
        @Test
        void theActivationDriver_entersADormantNode_onlyWhileConsensusIsLive() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var legacy = new AtomicInteger();
                var driver = harness.wiring.activationDriver(() -> false, legacy::incrementAndGet);

                driver.run();
                await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> !activeNode(harness));

                harness.wiring.activationDriver(() -> true, legacy::incrementAndGet).run();
                harness.awaitThat(() -> activeNode(harness));
                assertThat(legacy).as("MT-14b, the H1 hazard: the legacy driver never runs under FSM2").hasValue(0);
            }
        }

        /// Level B finding (PR C): a genuine quorum entry and the level-heal parked behind a closed guard are two different actions. Under one shared
        /// name the heal replaced the genuine entry, so the node entered through the heal and `QuorumEstablished` was never stepped.
        @Test
        void aGenuineEntry_andTheHeal_parkedBehindAClosedGuard_bothSurviveTheOpening() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false, false).assembled()) {
                harness.router.route(ClusterStateNotification.active());
                harness.wiring.activationDriver(() -> true, () -> {}).run();
                assertThat(activeNode(harness)).as("closed: nothing ran").isFalse();

                harness.markerCommitted.set(true);
                harness.guard.evaluate();
                harness.awaitThat(() -> activeNode(harness));

                assertThat(harness.count("QuorumEstablished", "applied", "node")).as("the genuine entry was stepped, not replaced by the heal").isEqualTo(1.0);
            }
        }

        /// N-2 (W-20): the config hook reaches the node interpreter only if the adapter runs the step it triggers. Today it does not, so it is a
        /// counted drop; this ties the two so that wiring `NOTIFY_CONFIG` without binding the hook (or the reverse) is red.
        @Test
        void theConfigHook_isBoundIffNotifyConfigIsWired() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var legacy = new AtomicInteger();
                var hook = harness.wiring.configApplied(_ -> legacy.incrementAndGet());

                enterQuorum(harness);
                hook.accept("orders.banner");

                assertThat(legacy).as("never the NDM under FSM2").hasValue(0);
                assertThat(SliceRuntimeAdapter.wired(LocalStep.NOTIFY_CONFIG)).as("the tripwire: wiring NOTIFY_CONFIG means binding the hook").isFalse();
                assertThat(harness.registry.counter("aether_deployment_fsm2_dropped_total", "point", "B5.ConfigApplied").count()).isEqualTo(1.0);
                assertThat(harness.count("ConfigChanged", "applied", "node")).isZero();
            }
        }
    }

    @Nested
    class Membership {
        /// W-23 (core arms): a core join reaches the cluster interpreter with the admission membership attaches.
        @Test
        void aCoreJoin_reachesTheClusterFsm_andARemovalToo() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                harness.bootTokens.admit(Fsm2Harness.PEER, 55L);

                harness.router.route(new MembershipDecision.NodeJoined(Fsm2Harness.PEER, List.of(Fsm2Harness.SELF, Fsm2Harness.PEER), 1L, HlcTimestamp.ZERO));
                harness.router.route(new MembershipDecision.NodeRemoved(Fsm2Harness.PEER, List.of(Fsm2Harness.SELF), 2L, HlcTimestamp.ZERO));

                harness.awaitThat(() -> stepped(harness, "cluster", "NodeJoined") == 1 && stepped(harness, "cluster", "NodeRemoved") == 1);
            }
        }

        /// W-14 / W-33 wired: the seam poller's samples reach the cluster interpreter, coalesced, and a token learned later is reported once.
        @Test
        void theSeamPoller_deliversSamples_andProcessKnownOnce() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                harness.awaitThat(() -> stepped(harness, "cluster", "SeamsRefreshed") >= 1);

                harness.bootTokens.admit(Fsm2Harness.PEER, 91L);
                harness.system.onPresent(system -> system.seamPoller().poll());
                harness.awaitThat(() -> stepped(harness, "cluster", "ProcessKnown") == 1);
                harness.system.onPresent(system -> system.seamPoller().poll());

                await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> stepped(harness, "cluster", "ProcessKnown") == 1);
            }
        }
    }

    @Nested
    class Leadership {
        /// B7 / MT-14f: the leader toggle activates and deactivates the cluster FSM; B8: the committed leader is observed.
        @Test
        void theLeaderToggle_activatesTheClusterFsm_andALeaderKeyIsObserved() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                harness.commitLeader();
                harness.awaitThat(() -> stepped(harness, "cluster", "LeaderObserved") == 1);

                harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
                harness.awaitThat(() -> stepped(harness, "cluster", "Rebuild") == 1);

                harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.PEER), false));
                harness.awaitThat(() -> stepped(harness, "cluster", "Deactivate") == 1);
            }
        }
    }

    @Nested
    class Workers {
        /// N-3: a worker never gets an FSM2 system, and nothing is registered for one; its hooks are counted drops.
        @Test
        void aWorker_getsNoSystem_noRoutes_andAttachIsRefused() {
            try (var donor = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled();
                 var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                assertThat(worker.system.isPresent()).isFalse();

                worker.wiring.attach(donor.system.unwrap());
                assertThat(worker.wiring.system().isPresent()).as("MT: a runtime handed to a worker").isFalse();

                worker.router.route(ClusterStateNotification.active());
                worker.put(SliceObservedKey.sliceObservedKey(Fsm2Harness.PEER, ORDERS), observed());
                assertThat(worker.registry.find("aether_deployment_fsm_step_seconds").timers()).isEmpty();
                assertThat(worker.wiring.recorded()).noneMatch(row -> row.startsWith("B1.cluster") || row.contains("fsm2:"));
            }
        }
    }

    @Nested
    class Stop {
        @Test
        void stop_closesBothInterpreters_andTheClusterDeactivationIsTheFirstStep() {
            var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled();
            var legacy = new AtomicInteger();

            enterQuorum(harness);
            var system = harness.system.unwrap();

            harness.wiring.deactivateCluster(() -> {
                legacy.incrementAndGet();

                return Promise.unitPromise();
            });
            assertThat(legacy).as("B13: the FSM2 stop does not run the legacy deactivation").hasValue(0);
            assertThat(system.clusterInterpreter().deliver(new ClusterInput.Flush())).as("closed").isFalse();

            harness.close();

            assertThat(system.nodeInterpreter().deliver(new NodeFsmInput.QuorumDisappeared())).isFalse();
        }

        @Test
        void underLegacy_theLegacyDeactivationRuns() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                var legacy = new AtomicInteger();

                harness.wiring.deactivateCluster(() -> {
                    legacy.incrementAndGet();

                    return Promise.unitPromise();
                });

                assertThat(legacy).hasValue(1);
                assertThat(harness.system.isPresent()).isFalse();
            }
        }
    }

    // -- obligation 1: the stamp across a real snapshot -----------------------------------------------------------------
    @Nested
    class SnapshotStamp {
        /// The consumer snapshots at revision r; an input committed afterwards (revision above r) must be stepped, and one at or below must
        /// be dropped. The stamp is read inside the dispatch by the production routes over the real `processCommitted`: a handler that moved the
        /// read to another thread would stamp every input `0`, and the later input would be dropped as already in the snapshot.
        @Test
        void aFactCommittedAfterTheSnapshot_isStepped_notDroppedAsInsideIt() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                harness.put(SliceObservedKey.sliceObservedKey(Fsm2Harness.PEER, ORDERS), observed());
                harness.awaitThat(() -> stepped(harness, "node", "ObservedPut") + dropped(harness, "ObservedPut") >= 1);
                var before = stepped(harness, "node", "ObservedPut");

                enterQuorum(harness);
                harness.put(SliceObservedKey.sliceObservedKey(Fsm2Harness.PEER, ORDERS), observed2());
                harness.awaitThat(() -> stepped(harness, "node", "ObservedPut") > before);

                assertThat(dropped(harness, "ObservedPut")).as("the later fact was not mistaken for one inside the snapshot").isZero();
            }
        }
    }

    // -- W-18: the observers are bound to the live interpreters and executor -----------------------------------------------
    @Nested
    class Metrics {
        /// W-18: every step the live interpreters take is timed (`step_seconds{fsm,input}`), and the mailbox depth of each is published as a gauge
        /// read from the live mailbox (`bindMailboxDepth`), not a constant.
        @Test
        void theStepTimer_andTheMailboxDepthGauge_areBoundToTheLiveInterpreters() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                enterQuorum(harness);

                harness.awaitThat(() -> stepped(harness, "node", "QuorumEstablished") == 1);
                harness.awaitThat(() -> stepped(harness, "cluster", "SeamsRefreshed") >= 1);
                assertThat(harness.registry.find("aether_deployment_fsm_mailbox_depth").tag("fsm", "node").gauge()).as("node depth gauge").isNotNull();
                assertThat(harness.registry.find("aether_deployment_fsm_mailbox_depth").tag("fsm", "cluster").gauge()).as("cluster depth gauge").isNotNull();
                harness.system.onPresent(system -> assertThat(harness.registry.find("aether_deployment_fsm_mailbox_depth")
                                                                                   .tag("fsm", "node")
                                                                                   .gauge()
                                                                                   .value()).isEqualTo((double) system.nodeInterpreter().mailboxDepth()));
            }
        }

        /// W-18: a local op the executor runs is counted `localops_total{step,result}`. The slice store here is absent, so the step fails, which is
        /// itself a counted result: the counter is the live one.
        @Test
        void aLocalOp_isCountedByTheLiveExecutor() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                harness.commitLeader();
                enterQuorum(harness);

                harness.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()),
                            AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
                harness.leaderPut(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, ORDERS),
                                  new AetherValue.SliceDesiredValue(AetherValue.SliceGoal.ACTIVE,
                                                                                                        new AetherValue.Generation(3L, 1L),
                                                                                                        1L));

                harness.awaitThat(() -> !harness.registry.find("aether_deployment_fsm_localops_total").counters().isEmpty());
            }
        }
    }

    // -- helpers -------------------------------------------------------------------------------------------------------------
    private static long stepped(Fsm2Harness harness, String fsm, String input) {
        var timer = harness.registry.find("aether_deployment_fsm_step_seconds").tags("fsm", fsm, "input", input).timer();

        return timer == null
               ? 0L
               : timer.count();
    }

    private static double dropped(Fsm2Harness harness, String input) {
        return harness.count(input, "dropped_revision", "node");
    }

    private static AetherValue.SliceObservedValue observed() {
        return observed(1L);
    }

    private static AetherValue.SliceObservedValue observed2() {
        return observed(2L);
    }

    private static AetherValue.SliceObservedValue observed(long version) {
        return new AetherValue.SliceObservedValue(AetherValue.SliceObservedState.ACTIVE,
                                                                                      version,
                                                                                      new AetherValue.Generation(1L, 1L),
                                                                                      new AetherValue.ProcessIncarnation(1L, 5L),
                                                                                      Option.none(),
                                                                                      false,
                                                                                      0,
                                                                                      List.of(),
                                                                                      1L);
    }
}
