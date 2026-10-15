// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
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

        /// A genuine PASSIVE edge supersedes an entry parked behind a closed guard: the node must not enter on opening, because the quorum it was
        /// to enter is gone (v-2204 V08).
        @Test
        void aPassiveEdge_replacesAParkedEntry_soTheNodeDoesNotEnterWhenTheGuardOpens() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false, false).assembled()) {
                harness.router.route(ClusterStateNotification.active());
                harness.router.route(ClusterStateNotification.passive());

                harness.markerCommitted.set(true);
                harness.guard.evaluate();

                await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(3)).until(() -> !activeNode(harness));
                assertThat(harness.count("QuorumEstablished", "applied", "node")).as("the parked entry was replaced, never stepped").isZero();
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

        /// Review M1 wired: every membership input is followed by a fresh sample, so a sample that already lacks a node is appended behind that
        /// node's removal. One sample per input, for a join and for a removal.
        @Test
        void aJoin_andARemoval_areEachFollowedByOneSample() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                harness.awaitThat(() -> harness.samples.get() >= 1);
                var before = harness.samples.get();

                harness.router.route(new MembershipDecision.NodeJoined(Fsm2Harness.PEER, List.of(Fsm2Harness.SELF, Fsm2Harness.PEER), 1L, HlcTimestamp.ZERO));
                assertThat(harness.samples.get()).as("a sample after the join").isEqualTo(before + 1);

                harness.router.route(new MembershipDecision.NodeRemoved(Fsm2Harness.PEER, List.of(Fsm2Harness.SELF), 2L, HlcTimestamp.ZERO));
                assertThat(harness.samples.get()).as("a sample after the removal").isEqualTo(before + 2);
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

        /// W-14: the 5 s sampling cadence stops with the system; a tick armed before the stop never samples after it.
        @Test
        void stop_cancelsTheSeamCadence() {
            var clock = new ManualClock();

            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withClock(clock).assembled()) {
                harness.awaitThat(() -> harness.samples.get() >= 1);
                harness.system.onPresent(Fsm2System::stop);
                var before = harness.samples.get();

                clock.advance(SeamPoller.INTERVAL_MS * 3);

                assertThat(harness.samples.get()).as("no sample after stop").isEqualTo(before);
            }
        }

        /// Stopping the system stops the local-op executor with it: a step offered afterwards is neither run nor settled (v-2204 V16).
        @Test
        void stop_closesTheLocalOpExecutor_soALaterStepIsNeitherRunNorSettled() throws InterruptedException {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var system = harness.system.unwrap();
                var settled = new CopyOnWriteArrayList<NodeFsmInput.LocalOpSettled>();

                system.stop();
                system.localOps().run(new NodeFsmEffect.LocalOp(1L, 10L, Option.some(ORDERS), LocalStep.LOAD, 5_000L, Option.none()), () -> 1L, settled::add);
                Thread.sleep(400);

                assertThat(settled).as("a closed executor runs nothing").isEmpty();
            }
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

    // -- the wiring's own bindings -------------------------------------------------------------------------------------------
    @Nested
    class Bindings {
        /// B17: the apply record is written under FSM2 and not under LEGACY; the answer is the engine's, not a constant.
        @Test
        void theApplyRecord_isOnUnderFsm2_andOffUnderLegacy() {
            try (var fsm2 = new Fsm2Harness(DeploymentEngine.FSM2, false); var legacy = new Fsm2Harness(DeploymentEngine.LEGACY, false)) {
                assertThat(fsm2.wiring.applyRecords()).isTrue();
                assertThat(legacy.wiring.applyRecords()).isFalse();
            }
        }

        /// B6: a one-time legacy registration runs under LEGACY and not under FSM2 (where the `SelfReady` effect drives the signal instead).
        @Test
        void aLegacyRegistration_runsUnderLegacyOnly() {
            var legacyRuns = new AtomicInteger();
            var fsm2Runs = new AtomicInteger();

            try (var fsm2 = new Fsm2Harness(DeploymentEngine.FSM2, false); var legacy = new Fsm2Harness(DeploymentEngine.LEGACY, false)) {
                legacy.wiring.legacyBinding("B6.test", legacyRuns::incrementAndGet);
                fsm2.wiring.legacyBinding("B6.test", fsm2Runs::incrementAndGet);
            }

            assertThat(legacyRuns).hasValue(1);
            assertThat(fsm2Runs).as("MT-14: the legacy registration never runs under FSM2").hasValue(0);
        }

        /// B2, B3, B4, B7: a legacy activation hook runs only while the engine marker guard is open. A LEGACY node whose cluster committed an FSM2
        /// marker is mismatched, so the hook parks and runs, once and with the latest event, when the guard opens.
        @Test
        void aGatedLegacyHook_isParkedWhileTheGuardIsClosed_andRunsWithTheLatestEventWhenItOpens() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false)) {
                var seen = new CopyOnWriteArrayList<String>();
                var hook = harness.wiring.legacyGated("B2.test", (String event) -> seen.add(event));
                var runnableRuns = new AtomicInteger();
                var runnable = harness.wiring.legacyGated("B3.test", runnableRuns::incrementAndGet);

                harness.markerEngine = DeploymentEngine.FSM2;
                harness.guard.evaluate();
                hook.accept("first");
                hook.accept("second");
                runnable.run();

                assertThat(seen).as("closed: nothing ran").isEmpty();
                assertThat(runnableRuns).hasValue(0);

                harness.markerEngine = DeploymentEngine.LEGACY;
                harness.guard.evaluate();

                assertThat(seen).as("level-triggered: the latest event, once").containsExactly("second");
                assertThat(runnableRuns).hasValue(1);
            }
        }

        /// B20: the guard re-reads the marker when the marker key commits.
        @Test
        void theCommittedMarkerKey_triggersTheGuard() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false, false).assembled()) {
                harness.commitLeader();
                assertThat(harness.guard.isOpen()).as("both keys absent: waiting").isFalse();

                harness.markerCommitted.set(true);
                harness.leaderPut(AetherKey.DeploymentEngineKey.deploymentEngineKey(), new AetherValue.DeploymentEngineValue(DeploymentEngine.FSM2, Fsm2Harness.SELF, 1L));

                assertThat(harness.guard.isOpen()).as("the marker key's commit opened it").isTrue();
            }
        }

        /// B20: the guard re-reads when the incarnation key commits (an incarnation with no marker is a pre-phase-3 cluster, v-2204 V17).
        @Test
        void theCommittedIncarnationKey_triggersTheGuard() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false, false).assembled()) {
                harness.commitLeader();
                assertThat(harness.guard.state()).isEqualTo(EngineMarkerGuard.State.WAITING);

                harness.incarnationOnly = true;
                harness.leaderPut(AetherKey.ClusterIncarnationKey.clusterIncarnationKey(), AetherValue.ClusterIncarnationValue.genesis("lineage", "id"));

                assertThat(harness.guard.state()).as("the incarnation's commit moved it off WAITING").isIn(EngineMarkerGuard.State.MISMATCH_UNTIMED, EngineMarkerGuard.State.MISMATCH_WINDOW);
            }
        }

        /// B20: the guard re-reads the marker on a leader edge.
        @Test
        void aLeaderEdge_triggersTheGuard() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false, false).assembled()) {
                assertThat(harness.guard.isOpen()).isFalse();

                harness.markerCommitted.set(true);
                harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.PEER), false));

                assertThat(harness.guard.isOpen()).isTrue();
            }
        }

        /// B20: the wiring reads the marker once when it starts, so a marker committed before the routes existed is not missed.
        @Test
        void startingTheWiring_readsAnAlreadyCommittedMarker() {
            var harness = new Fsm2Harness(DeploymentEngine.FSM2, false, false);

            try (harness) {
                assertThat(harness.guard.isOpen()).isFalse();
                harness.markerCommitted.set(true);

                harness.assembled();

                assertThat(harness.guard.isOpen()).as("the boot read").isTrue();
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
