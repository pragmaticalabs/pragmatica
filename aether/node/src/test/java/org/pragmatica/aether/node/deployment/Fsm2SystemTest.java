// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.membership.fsm.WorkerLeaveDecision;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.wiring.BacklogSampler;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.LeaderDeactivation;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ForwardingClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/// The FSM2 system over the production route table, a real `KVStore` applier and real interpreters (phase-3 design 4.1, 4.4, 5.1, 8.3): the
/// route-level gates of the quorum edge (W-04, MT-05), the self-ready signal (W-22), the activation level-heal (W-21, B3), the config hook (W-20,
/// N-2), core membership (W-23), the worker's node half (F3), the stop (B13) and the seams (W-14, W-33 wired), and the stamp read inside the
/// dispatch across a real snapshot (PR C obligation 1).
class Fsm2SystemTest {
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final Artifact PAYMENTS = Artifact.artifact("org.example:payments:1.0.0").unwrap();
    private static final int ROUNDS = 300;
    private static final TimeSpan LEFT_BOUND = TimeSpan.timeSpan(10).seconds();

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

        /// W-20 / MT-14d (B5): a committed config change reaches the node interpreter as `ConfigChanged`, and never the NDM. The adapter now runs
        /// `NOTIFY_CONFIG` (N-2 is discharged: the hook was a counted drop only while that step was unwired), so the hook is bound.
        @Test
        void theConfigHook_isBoundToTheNodeInterpreter_underFsm2() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var legacy = new AtomicInteger();
                var hook = harness.wiring.configApplied(_ -> legacy.incrementAndGet());

                enterQuorum(harness);
                hook.accept("orders.banner");

                harness.awaitThat(() -> harness.count("ConfigChanged", "applied", "node") == 1.0);
                assertThat(legacy).as("never the NDM under FSM2").hasValue(0);
                assertThat(harness.registry.find("aether_deployment_fsm2_dropped_total").tags("point", "B5.ConfigApplied").counters()).as("not a drop").isEmpty();
                assertThat(harness.wiring.recorded()).contains("B5.ConfigApplied -> fsm2:NodeFsmInput.ConfigChanged");
            }
        }

        /// #2757 (F3, MT-14d worker arm): a worker's config hook reaches its node interpreter's `ConfigChanged`, never the NDM and never a drop.
        @Test
        void theConfigHook_ofAWorker_reachesItsNodeFsm_underFsm2() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                var legacy = new AtomicInteger();

                harness.wiring.passiveClientSwitch(() -> {}).run();
                harness.awaitThat(() -> activeNode(harness));
                harness.wiring.configApplied(_ -> legacy.incrementAndGet()).accept("orders.banner");

                harness.awaitThat(() -> harness.count("ConfigChanged", "applied", "node") == 1.0);
                assertThat(legacy).as("never the NDM under FSM2").hasValue(0);
                assertThat(harness.registry.find("aether_deployment_fsm2_dropped_total").tags("point", "B5.ConfigApplied").counters()).as("not a drop").isEmpty();
                assertThat(harness.registry.find("aether_deployment_fsm2_dropped_total").tags("point", "B5.ConfigApplied.preAttach").counters())
                    .as("attached: not a pre-attach drop").isEmpty();
                assertThat(harness.wiring.recorded()).contains("B5.ConfigApplied -> fsm2:NodeFsmInput.ConfigChanged");
            }
        }

        /// #2757: a config change applied before the FSM2 system is attached is dropped and COUNTED. It stays a design-out (no FSM2 chain has run
        /// before attach, so no slice is registered for config, and REGISTER_CONFIG reads the current value at registration), but a reachable drop is
        /// never silent. Red when: the pre-attach arm stops counting, or reaches the NDM.
        @Test
        void theConfigHook_beforeAttach_isACountedDrop_underFsm2() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false)) {
                var legacy = new AtomicInteger();
                var hook = harness.wiring.configApplied(_ -> legacy.incrementAndGet());

                assertThat(harness.wiring.system()).as("control: nothing attached yet").isEqualTo(Option.none());
                hook.accept("orders.banner");
                hook.accept("orders.limit");

                assertThat(legacy).as("never the NDM under FSM2").hasValue(0);
                assertThat(harness.registry.counter("aether_deployment_fsm2_dropped_total", "point", "B5.ConfigApplied.preAttach").count())
                    .as("each pre-attach change is counted").isEqualTo(2.0);
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

        /// B10, B11, B9 worker arms (#2762, W-23): a worker's admission, its placement re-drive (translated to the same join) and its leave reach
        /// the cluster interpreter as `WorkerJoin` and `WorkerLeave`, never the legacy target, each followed by one sample. Without them a FSM2
        /// leader never writes a worker's activation directive (`worker-assignment-overdue`).
        @Test
        void aWorkerJoin_aPlacementRedrive_andAWorkerLeave_reachTheClusterFsm_neverTheLegacyTarget() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var legacy = new AtomicInteger();

                harness.awaitThat(() -> harness.samples.get() >= 1);
                var before = harness.samples.get();

                harness.wiring.workerJoin("B10.WorkerAdmission", _ -> legacy.incrementAndGet()).accept(workerJoined(WORKER));
                harness.wiring.placementRedrive(_ -> legacy.incrementAndGet()).accept(placementPut(OTHER_WORKER));
                harness.wiring.workerLeave(_ -> legacy.incrementAndGet()).accept(WorkerLeaveDecision.workerLeaveDecision(WORKER, HlcTimestamp.ZERO));

                harness.awaitThat(() -> stepped(harness, "cluster", "WorkerJoin") == 2 && stepped(harness, "cluster", "WorkerLeave") == 1);
                assertThat(legacy).as("never the CDM under FSM2").hasValue(0);
                assertThat(harness.samples.get()).as("a sample after each worker input").isEqualTo(before + 3);
                assertThat(harness.wiring.recorded()).contains("B10.WorkerAdmission -> fsm2:MembershipInputs.onWorkerJoin",
                                                              "B11.PlacementRedrive -> fsm2:MembershipInputs.onWorkerJoin",
                                                              "B9.cdm.WorkerLeave -> fsm2:MembershipInputs.onWorkerLeave");
            }
        }

        /// B10, B11 on the leader (#2762): the active cluster FSM records each worker's admission, the joined worker with the token the registry
        /// has now and the placement re-drive for the worker its record names, which is what its directive is written from (Joins, J5/J6).
        @Test
        void theWorkerArms_recordEachWorkersAdmission_inTheLeadersModel() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                harness.bootTokens.admit(WORKER, 77L);
                harness.commitLeader();
                harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
                harness.awaitThat(() -> activeModel(harness).isPresent());

                harness.wiring.workerJoin("B10.cdm.WorkerJoin", _ -> {}).accept(workerJoined(WORKER));
                harness.wiring.placementRedrive(_ -> {}).accept(placementPut(OTHER_WORKER));

                harness.awaitThat(() -> admitted(harness, OTHER_WORKER) && admitted(harness, WORKER));
                assertThat(activeModel(harness).flatMap(model -> model.admission(WORKER)).map(admission -> admission.token())).isEqualTo(Option.some(77L));
            }
        }

        /// A leader change while a worker's admission is in flight (v-2770 item 3): the leader's directive write for the worker is held, the
        /// leadership moves away (`Deactivate`), the held write then commits or is lost, and leadership comes back (`Rebuild`). The worker's
        /// placement record re-drives its join (B11) on the new term. Either way the worker is admitted once and exactly one directive is
        /// committed: a committed write is read back at `Rebuild` and not written again, a lost one is written once by the new term. Mutation:
        /// the re-drive broken (`DeploymentWiring.placementPut` a no-op) leaves the worker unadmitted on the new term.
        @Test
        void aLeaderChange_whileAWorkersDirectiveWriteIsInFlight_thatCommits_admitsItOnce_withOneDirective() {
            leaderChangeDuringAdmission(true);
        }

        @Test
        void aLeaderChange_whileAWorkersDirectiveWriteIsInFlight_thatIsLost_admitsItOnce_withOneDirective() {
            leaderChangeDuringAdmission(false);
        }

        private static void leaderChangeDuringAdmission(boolean heldWriteCommits) {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var held = new AtomicReference<Promise<List<Object>>>();
                var heldCommands = new AtomicReference<List<KVCommand<AetherKey>>>();
                var committed = new AtomicInteger();

                harness.bootTokens.admit(WORKER, 77L);
                harness.cluster.script = commands -> holdFirstDirective(harness, held, heldCommands, committed, commands);
                harness.commitLeader();
                harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
                harness.awaitThat(() -> activeModel(harness).isPresent());

                harness.wiring.workerJoin("B10.cdm.WorkerJoin", _ -> {}).accept(workerJoined(WORKER));
                harness.awaitThat(() -> held.get() != null);
                harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.PEER), false));
                harness.awaitThat(() -> stepped(harness, "cluster", "Deactivate") == 1);
                settleHeld(harness, held.get(), heldCommands.get(), committed, heldWriteCommits);
                harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
                harness.awaitThat(() -> stepped(harness, "cluster", "Rebuild") == 2 && activeModel(harness).isPresent());
                harness.wiring.placementRedrive(_ -> {}).accept(placementPut(WORKER));

                harness.awaitThat(() -> admitted(harness, WORKER) && harness.store.snapshot().containsKey(new AetherKey.ActivationDirectiveKey(WORKER)));
                await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).until(() -> committed.get() == 1);
                assertThat(activeModel(harness).flatMap(model -> model.admission(WORKER)).map(admission -> admission.token())).isEqualTo(Option.some(77L));
            }
        }

        /// The cluster's writes, committed to the store, except the first directive write for [#WORKER], which is held in flight.
        private static Promise<List<Object>> holdFirstDirective(Fsm2Harness harness,
                                                                AtomicReference<Promise<List<Object>>> held,
                                                                AtomicReference<List<KVCommand<AetherKey>>> heldCommands,
                                                                AtomicInteger committed,
                                                                List<KVCommand<AetherKey>> commands) {
            if (writesDirective(commands) && heldCommands.compareAndSet(null, commands)) {
                held.set(Promise.promise());

                return held.get();
            }
            if (writesDirective(commands)) {
                committed.incrementAndGet();
            }

            return Promise.success(harness.commitAll(commands));
        }

        private static void settleHeld(Fsm2Harness harness,
                                       Promise<List<Object>> held,
                                       List<KVCommand<AetherKey>> commands,
                                       AtomicInteger committed,
                                       boolean commits) {
            if (commits) {
                committed.incrementAndGet();
                held.succeed(harness.commitAll(commands));
            } else {
                held.fail(Causes.cause("leadership moved: the held directive write was not committed"));
            }
        }

        private static boolean writesDirective(List<KVCommand<AetherKey>> commands) {
            var key = new AetherKey.ActivationDirectiveKey(WORKER);

            return commands.stream()
                           .anyMatch(command -> command instanceof KVCommand.LeaderTransaction<?, ?> tx && tx.mutations()
                                                                                                         .stream()
                                                                                                         .anyMatch(mutation -> mutation.key().equals(key)));
        }

        /// Under LEGACY the worker arms keep their legacy target: the CDM's join, re-drive and leave.
        @Test
        void underLegacy_theWorkerArms_reachTheLegacyTarget() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                var legacy = new AtomicInteger();

                harness.wiring.workerJoin("B10.cdm.WorkerJoin", _ -> legacy.incrementAndGet()).accept(workerJoined(WORKER));
                harness.wiring.placementRedrive(_ -> legacy.incrementAndGet()).accept(placementPut(OTHER_WORKER));
                harness.wiring.workerLeave(_ -> legacy.incrementAndGet()).accept(WorkerLeaveDecision.workerLeaveDecision(WORKER, HlcTimestamp.ZERO));

                assertThat(legacy).hasValue(3);
                assertThat(harness.wiring.recorded()).contains("B10.cdm.WorkerJoin -> legacy", "B11.PlacementRedrive -> legacy", "B9.cdm.WorkerLeave -> legacy");
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

    /// F3 (P3-D): a FSM2 worker runs the node half of the system over the replica feed (know `23ca0fd65f` D.2): the node FSM's routes, the quorum
    /// edge, the passive-client switch as its entry edge, the config hook and the stop drain. Nothing of the cluster half is bound or started.
    @Nested
    class Workers {
        private static void enterWorker(Fsm2Harness worker) {
            worker.wiring.passiveClientSwitch(() -> {}).run();
            worker.awaitThat(() -> activeNode(worker));
        }

        private static AetherValue.SliceDesiredValue desired() {
            return new AetherValue.SliceDesiredValue(AetherValue.SliceGoal.ACTIVE, new AetherValue.Generation(3L, 1L), 1L);
        }

        private static void target(Fsm2Harness worker, int instances) {
            worker.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()), AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), instances, instances));
        }

        /// The worker's record: the node routes, B2, B4, B5 and the stop drain are FSM2 targets; no cluster route, no leader toggle, no
        /// membership arm. MT: `registerKv`/`registerEntries` taking the core branch on a worker puts cluster rows here.
        @Test
        void aWorker_getsTheNodeHalf_andNoClusterBinding() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                worker.wiring.passiveClientSwitch(() -> {});
                worker.wiring.configApplied(_ -> {});

                assertThat(worker.system.map(Fsm2System::worker)).as("the node half is attached").isEqualTo(Option.some(true));
                assertThat(worker.wiring.recorded()).contains("B1.node.SliceDesiredKey -> fsm2:Fsm2Routes",
                                                             "B1.node.SliceObservedKey -> fsm2:Fsm2Routes",
                                                             "B1.node.SliceTargetKey -> fsm2:Fsm2Routes",
                                                             "B1.node.VersionRoutingKey -> fsm2:Fsm2Routes",
                                                             "B2.ClusterStateNotification -> fsm2:Fsm2System.onClusterStateNotification",
                                                             "B4.PassiveClientSwitch -> fsm2:Fsm2System.onPassiveClientReady",
                                                             "B5.ConfigApplied -> fsm2:NodeFsmInput.ConfigChanged",
                                                             "B13.StopDrain -> fsm2:Fsm2System.drainNode",
                                                             "B15.construction -> fsm2:Fsm2System",
                                                             "B16.Seams -> dropped");
                assertThat(worker.wiring.recorded()).noneMatch(row -> row.startsWith("B1.cluster") || row.startsWith("B7.") || row.startsWith("B8.") || row.startsWith("B9."));
            }
        }

        /// The role is checked at attach: a core's system handed to a worker, or a worker's to a core, is refused.
        @Test
        void aSystemOfTheOtherRole_isRefusedAtAttach() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true); var core = new Fsm2Harness(DeploymentEngine.FSM2, false)) {
                worker.wiring.attach(worker.foreignRoleSystem());
                core.wiring.attach(core.foreignRoleSystem());

                assertThat(worker.wiring.system().isPresent()).as("MT: a core's runtime handed to a worker").isFalse();
                assertThat(core.wiring.system().isPresent()).as("MT: a worker's runtime handed to a core").isFalse();
            }
        }

        /// B4 is the worker's entry edge: the passive-client switch enters the node FSM (never the legacy reconcile), and then the worker's own
        /// goal, delivered as a committed `SliceDesiredKey`, is stepped. A peer's goal is not (the own-node filter, NI-2).
        @Test
        void aWorker_entersOnThePassiveClientSwitch_andStepsOnlyItsOwnGoal() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                var legacy = new AtomicInteger();

                worker.commitLeader();
                worker.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()), AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
                worker.wiring.passiveClientSwitch(legacy::incrementAndGet).run();
                worker.awaitThat(() -> activeNode(worker));
                worker.leaderPut(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.PEER, ORDERS), desired());
                worker.leaderPut(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, ORDERS), desired());

                worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 1.0);
                assertThat(legacy).as("never the NDM under FSM2").hasValue(0);
                assertThat(worker.count("QuorumEstablished", "applied", "node")).as("the genuine entry, not the heal").isEqualTo(1.0);
            }
        }

        /// B finding 1 of v-2770: the worker's entry edge (B4) is gated like the core's quorum edge. Behind a closed engine-marker guard the
        /// passive-client switch enters nothing; the parked entry is the genuine one, stepped when the guard opens. Mutation s-entry-ungated (a
        /// bare `requestEntry` in `Fsm2System.onPassiveClientReady`): the worker enters while the guard is closed.
        @Test
        void thePassiveClientSwitch_behindAClosedGuard_entersOnlyWhenTheGuardOpens() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true, false).assembled()) {
                worker.wiring.passiveClientSwitch(() -> {}).run();
                await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(3)).until(() -> !activeNode(worker));

                worker.markerCommitted.set(true);
                worker.guard.evaluate();
                worker.awaitThat(() -> activeNode(worker));

                assertThat(worker.count("QuorumEstablished", "applied", "node")).as("the parked genuine entry").isEqualTo(1.0);
            }
        }

        /// B findings 2 and 3 of v-2770: under LEGACY a worker's passive-client switch runs its legacy delegate (the NDM's reconcile), through
        /// the gate, and attaches no FSM2 system. Mutations d-b4-legacy-fsm2 (`isFsm2() && worker` -> `worker`: the FSM2 binding, a no-op with no
        /// system) and d-b4-legacy-swallow (the delegate replaced by a no-op): the delegate is never invoked.
        @Test
        void underLegacy_aWorkersPassiveClientSwitch_invokesTheLegacyDelegate() {
            try (var worker = new Fsm2Harness(DeploymentEngine.LEGACY, true).assembled()) {
                var legacy = new AtomicInteger();

                worker.wiring.passiveClientSwitch(legacy::incrementAndGet).run();

                worker.awaitThat(() -> legacy.get() == 1);
                assertThat(worker.wiring.recorded()).contains("B4.PassiveClientSwitch -> legacy+gate");
                assertThat(worker.system.isPresent()).as("no FSM2 system under LEGACY").isFalse();
            }
        }

        /// A worker runs no cluster FSM, so the worker membership arms (B9, B10, B11) are counted drops on a FSM2 worker: no cluster step, no
        /// legacy target. MT: the core branch taken on a worker would bind `fsm2:MembershipInputs` here.
        @Test
        void aWorker_dropsTheWorkerMembershipArms_andCountsThem() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                var legacy = new AtomicInteger();

                worker.wiring.workerJoin("B10.cdm.WorkerJoin", _ -> legacy.incrementAndGet()).accept(workerJoined(WORKER));
                worker.wiring.placementRedrive(_ -> legacy.incrementAndGet()).accept(placementPut(OTHER_WORKER));
                worker.wiring.workerLeave(_ -> legacy.incrementAndGet()).accept(WorkerLeaveDecision.workerLeaveDecision(WORKER, HlcTimestamp.ZERO));

                assertThat(legacy).hasValue(0);
                assertThat(worker.wiring.recorded()).contains("B10.cdm.WorkerJoin -> dropped", "B11.PlacementRedrive -> dropped", "B9.cdm.WorkerLeave -> dropped");
                assertThat(worker.registry.counter("aether_deployment_fsm2_dropped_total", "point", "B10.cdm.WorkerJoin").count()).isEqualTo(1.0);
                assertThat(worker.registry.find("aether_deployment_fsm_step_seconds").tags("fsm", "cluster").timers()).as("no cluster step").isEmpty();
            }
        }

        /// The cluster half is never started on a worker: a leader toggle and a cluster-family fact reach no cluster step.
        @Test
        void aWorker_neverStepsTheClusterFsm() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                worker.commitLeader();
                enterWorker(worker);
                worker.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
                worker.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()), AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));

                worker.awaitThat(() -> worker.count("TargetPut", "applied", "node") == 1.0);
                assertThat(worker.registry.find("aether_deployment_fsm_step_seconds").tags("fsm", "cluster").timers()).as("no cluster step").isEmpty();
                assertThat(worker.samples).as("the seam cadence never sampled").hasValue(0);
            }
        }

        /// W-19 worker arm (D8, MT-20b): a re-subscription's install replays the difference to what was delivered while the worker's node FSM stays
        /// ACTIVE; on a worker that burst is the carrier of the goals committed during the gap, so it is stepped, never dropped as a core drops it.
        @Test
        void aReplayedInstall_isSteppedAsFacts_onAWorker() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                worker.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()), AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
                enterWorker(worker);
                var installed = new HashMap<AetherKey, AetherValue>(worker.store.snapshot());

                installed.put(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, ORDERS), desired());
                worker.installing = Map.copyOf(installed);
                assertThat(worker.store.restoreCommittedSnapshot(new byte[0], worker.slot.incrementAndGet() + 1).isSuccess()).isTrue();
                worker.store.replayNotifications();

                worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 1.0);
                assertThat(worker.count("DesiredPut", "dropped_replay", "node")).as("MT-20b: the core rule on a worker").isZero();
            }
        }

        /// CTO ruling D8 (know `5b4db3a518`, A1 of v-2770): a replica install BELOW the entry revision (a new incarnation, or `AHEAD`) is a new
        /// snapshot for the node FSM. Its burst, two goals this worker's node FSM never saw, is stepped as the authoritative diff, never dropped by the
        /// entry's revision. Mutation: the monotone `max` watermark with no reset drops both (`dropped_revision`).
        @Test
        void anInstallBelowTheEntryRevision_isSteppedAsTheAuthoritativeDiff_onAWorker() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                worker.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()), AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1));
                worker.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()), AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), 2, 2));
                enterWorker(worker);
                var installed = new HashMap<AetherKey, AetherValue>(worker.store.snapshot());

                installed.put(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, ORDERS), desired());
                installed.put(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, PAYMENTS), desired());
                worker.installing = Map.copyOf(installed);
                assertThat(worker.store.restoreCommittedSnapshot(new byte[0], 1L).isSuccess()).isTrue();
                worker.store.replayNotifications();

                worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 2.0);
                assertThat(worker.count("DesiredPut", "dropped_revision", "node")).isZero();
            }
        }

        /// P1a of v-2770 (A1 (a)): a new history installed below the watermark, then its next live entry (revision 2) and the B4 re-fire. Both
        /// goals are stepped: the burst's, and the live one above the install's revision (with a monotone watermark it stays dropped until the new
        /// history's revisions pass the old one).
        @Test
        void aNewHistoryInstalledBelowTheWatermark_stepsItsBurst_andItsLiveEntries_onAWorker() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                worker.commitLeader();
                for (int i = 1; i <= 6; i++) {
                    target(worker, i);
                }
                enterWorker(worker);
                var installed = new HashMap<AetherKey, AetherValue>(worker.store.snapshot());

                installed.put(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, ORDERS), desired());
                worker.installing = Map.copyOf(installed);
                assertThat(worker.store.restoreCommittedSnapshot(new byte[0], 1L).isSuccess()).isTrue();
                worker.store.replayNotifications();
                worker.slot.set(1);
                worker.leaderPut(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, PAYMENTS), desired());
                worker.wiring.passiveClientSwitch(() -> {}).run();

                worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 2.0);
                assertThat(worker.count("DesiredPut", "dropped_revision", "node")).isZero();
            }
        }

        /// P1b of v-2770, under ruling D8: a lagging core's same-history install (`AHEAD`), at a revision between the entry watermark and what the
        /// worker already stepped, is stepped as the authoritative diff, and the goal it lacks is restored when that core catches up (its next entry
        /// re-delivers it above the install's revision). The worker converges.
        ///
        /// Named limit #2776 (out of F3 scope, the same under LEGACY): the regressed burst is stepped, so the goal is removed until the catch-up.
        /// This test pins that as the current behaviour; when #2776 is fixed, the `DesiredRemove` assertion turns red and becomes zero.
        @Test
        void aLaggingCoresInstall_isSteppedAsTheDiff_andTheCatchUpRestoresTheGoal_onAWorker() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                worker.commitLeader();
                target(worker, 1);
                enterWorker(worker);
                target(worker, 2);
                var lagging = Map.copyOf(new HashMap<AetherKey, AetherValue>(worker.store.snapshot()));
                var laggingRevision = worker.store.committedRevision();

                worker.leaderPut(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, ORDERS), desired());
                worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 1.0);
                target(worker, 3);
                worker.installing = lagging;
                assertThat(worker.store.restoreCommittedSnapshot(new byte[0], laggingRevision).isSuccess()).isTrue();
                worker.store.replayNotifications();
                worker.awaitThat(() -> worker.count("DesiredRemove", "applied", "node") == 1.0);
                worker.slot.set(laggingRevision);
                worker.leaderPut(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, ORDERS), desired());

                worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 2.0);
                assertThat(worker.count("DesiredRemove", "applied", "node")).as("#2776: the regressed burst is stepped; zero once #2776 is fixed").isEqualTo(1.0);
            }
        }

        /// D8's dedupe after an install: a fact stamped at or below the install's revision (a mutation applied without a slot keeps the committed
        /// revision) is in the install, and dropped by revision. Mutation: a reset on every KV input instead of on an install steps it. (A goal is
        /// a leader-fenced key, so the plain mutation here is a target.)
        @Test
        void afterAnInstall_aFactAtItsRevision_isDroppedByRevision_onAWorker() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                target(worker, 1);
                enterWorker(worker);
                var installed = new HashMap<AetherKey, AetherValue>(worker.store.snapshot());

                installed.put(AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, ORDERS), desired());
                worker.installing = Map.copyOf(installed);
                assertThat(worker.store.restoreCommittedSnapshot(new byte[0], 50L).isSuccess()).isTrue();
                worker.store.replayNotifications();
                worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 1.0);
                var targets = worker.count("TargetPut", "applied", "node");

                worker.putWithoutSlot(AetherKey.SliceTargetKey.sliceTargetKey(PAYMENTS.base()), AetherValue.SliceTargetValue.sliceTargetValue(PAYMENTS.version(), 1, 1));

                worker.awaitThat(() -> worker.count("TargetPut", "dropped_revision", "node") == 1.0);
                assertThat(worker.count("TargetPut", "applied", "node")).isEqualTo(targets);
            }
        }

        /// W-35 worker arm, wired (G-6 (a), MT-31): the worker's write-attempt bound is the forwarder's timeout + 5 s, whatever the configured
        /// consensus `applyTimeout` (45 s here, so a bound sourced from it would read 50 s); a core's, built the same way, is the `applyTimeout`'s.
        @Test
        void theWriteAttemptBound_ofAWorker_isTheForwardersTimeout_notTheApplyTimeout() {
            try (var worker = new Fsm2Harness(DeploymentEngine.FSM2, true).withApplyTimeout(TimeSpan.timeSpan(45).seconds()).assembled();
                 var core = new Fsm2Harness(DeploymentEngine.FSM2, false).withApplyTimeout(TimeSpan.timeSpan(45).seconds()).assembled()) {
                var forwarder = ForwardingClusterNode.forwardApplyTimeout().millis();

                assertThat(worker.system.unwrap().nodeFsmConfig().timings().writeAttemptMs()).as("MT-31").isEqualTo(forwarder + 5_000L).isGreaterThan(forwarder);
                assertThat(core.system.unwrap().nodeFsmConfig().timings().writeAttemptMs()).as("control: the core arm reads the configured applyTimeout").isEqualTo(50_000L);
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

    // -- design 7: the stop drain (B13, W-16 (a), MT-15) -----------------------------------------------------------------------
    @Nested
    class StopDrain {
        /// W-16 (a): the PASSIVE edge is routed, the node drains (its quorum loss has been stepped, so its `SUSPEND`s are queued and run), and only then
        /// is the router quiesced. MT-15 skips the drain: the quiesce then runs while the node is still Active.
        @Test
        void leaveQuorum_routesPassive_thenDrainsTheNode_thenQuiesces() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var order = new CopyOnWriteArrayList<String>();

                enterQuorum(harness);
                var left = harness.wiring.leaveQuorum(() -> {
                                                          harness.router.route(ClusterStateNotification.passive());
                                                          order.add("passive");
                                                      },
                                                      Promise::unitPromise,
                                                      () -> order.add(activeNode(harness)
                                                                      ? "quiesce-while-active"
                                                                      : "quiesce"));

                assertThat(left.await(LEFT_BOUND).isSuccess()).as("the quorum is left").isTrue();
                assertThat(order).containsExactly("passive", "quiesce");
            }
        }

        @Test
        void leaveQuorum_underLegacy_routesPassiveThenQuiesces_withNothingToDrain() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                var order = new CopyOnWriteArrayList<String>();

                var left = harness.wiring.leaveQuorum(() -> order.add("passive"), Promise::unitPromise, () -> order.add("quiesce"));

                assertThat(left.await(LEFT_BOUND).isSuccess()).as("the quorum is left").isTrue();
                assertThat(order).containsExactly("passive", "quiesce");
            }
        }

        /// #2688: the quiesce, which would drop the queued `LeaderChange` reaction, waits for the reactions of the PASSIVE edge to have run, and the
        /// wait is taken only after PASSIVE was routed (taken before it, it could not cover the reactions PASSIVE queues).
        @Test
        void leaveQuorum_quiescesOnlyOnceTheQueuedReactionsHaveRun() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                var order = new CopyOnWriteArrayList<String>();
                var delivered = Promise.<Unit> promise();
                var left = harness.wiring.leaveQuorum(() -> order.add("passive"), () -> taken(order, delivered), () -> order.add("quiesce"));

                assertThat(order).containsExactly("passive", "delivered");
                assertThat(left.isResolved()).isFalse();

                delivered.succeed(Unit.unit());

                assertThat(left.await(LEFT_BOUND).isSuccess()).as("the quorum is left").isTrue();
                assertThat(order).containsExactly("passive", "delivered", "quiesce");
            }
        }

        /// #2688, #2806: the quiesce, and what the caller chains after it (the node's teardown, which blocks), never run on the thread that resolved the
        /// wait: in the node that is the react executor whose reactions were awaited, or the shared timeout scheduler. Ordering (b): the wait is
        /// resolved on a named executor thread after the call. Repeated, because a hop that only starts a pooled task and maps its result runs the
        /// quiesce inline whenever the task finishes first (#2806).
        @Test
        void leaveQuorum_quiescesOffTheThreadThatResolvedTheWait_whenTheWaitResolvesAfterTheCall() throws InterruptedException {
            var resolver = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "resolver-2806"));

            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                for (var round = 0; round < ROUNDS; round++) {
                    var quiescedOn = new CopyOnWriteArrayList<Thread>();
                    var resolvedOn = new AtomicReference<Thread>();
                    var delivered = Promise.<Unit> promise();
                    var left = harness.wiring.leaveQuorum(() -> {}, () -> delivered, () -> quiescedOn.add(Thread.currentThread()));

                    resolver.execute(() -> resolveOn(delivered, resolvedOn));

                    assertThat(left.await(LEFT_BOUND).isSuccess()).as("the quorum is left").isTrue();
                    assertThat(quiescedOn).as("quiesced once, off the resolver thread and off the caller, round " + round)
                                          .hasSize(1)
                                          .doesNotContain(resolvedOn.get(), Thread.currentThread());
                }
            } finally {
                resolver.shutdownNow();
            }
        }

        /// #2806, ordering (a): the wait is already resolved when the quiesce is requested; the quiesce is still pooled, not run on the caller.
        @Test
        void leaveQuorum_quiescesOffTheCallerThread_whenTheWaitIsAlreadyResolved() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                for (var round = 0; round < ROUNDS; round++) {
                    var quiescedOn = new CopyOnWriteArrayList<Thread>();
                    var left = harness.wiring.leaveQuorum(() -> {},
                                                          Promise::unitPromise,
                                                          () -> quiescedOn.add(Thread.currentThread()));

                    assertThat(left.await(LEFT_BOUND).isSuccess()).as("the quorum is left").isTrue();
                    assertThat(quiescedOn).as("quiesced once, off the caller, round " + round)
                                          .hasSize(1)
                                          .doesNotContain(Thread.currentThread());
                }
            }
        }

        /// #3002: what the caller chains on the returned promise (the node's teardown, which blocks) never runs on the thread that resolved the
        /// wait. The hop executor finishes its task before `execute` returns, so a continuation attached before the hop must run on the hop's
        /// thread; the earlier `flatMap` into the pooled quiesce resolved the returned promise inline on the resolver whenever the quiesce
        /// finished first, and without the hop the continuation runs wherever the quiesce or the resolver left it.
        @Test
        void leaveQuorum_continuationRunsOnTheHop_neverOnTheThreadThatResolvedTheWait() {
            var resolver = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "resolver-3002"));

            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                for (var round = 0; round < ROUNDS; round++) {
                    var continuedOn = new AtomicReference<Thread>();
                    var resolvedOn = new AtomicReference<Thread>();
                    var delivered = Promise.<Unit> promise();
                    var left = harness.wiring.leaveQuorum(() -> {}, () -> delivered, () -> {}, RUN_BEFORE_RETURN)
                                             .map(_ -> recordOn(continuedOn));

                    resolver.execute(() -> resolveOn(delivered, resolvedOn));

                    assertThat(left.await(LEFT_BOUND).isSuccess()).as("the quorum is left").isTrue();
                    assertThat(continuedOn.get().getName()).as("the continuation ran on the hop, round " + round).isEqualTo(HOP_THREAD);
                    assertThat(continuedOn.get()).as("never on the resolver, round " + round).isNotSameAs(resolvedOn.get());
                }
            } finally {
                resolver.shutdownNow();
            }
        }

        private static final String HOP_THREAD = "hop-3002";

        /// Runs the task on a fresh thread and joins it before `execute` returns: the hop's promise is ALREADY resolved when anything is attached.
        private static final Executor RUN_BEFORE_RETURN = StopDrain::runToCompletionOnFreshThread;

        private static void runToCompletionOnFreshThread(Runnable task) {
            var worker = Thread.ofPlatform().name(HOP_THREAD).unstarted(task);

            worker.start();
            joinUninterrupted(worker);
        }

        private static void joinUninterrupted(Thread worker) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted joining the hop task: " + e);
            }
        }

        private static Unit recordOn(AtomicReference<Thread> continuedOn) {
            continuedOn.set(Thread.currentThread());

            return Unit.unit();
        }

        private static void resolveOn(Promise<Unit> delivered, AtomicReference<Thread> resolvedOn) {
            resolvedOn.set(Thread.currentThread());
            delivered.succeed(Unit.unit());
        }

        /// A10 on the stop path, with no waiting: a leader that never steps down (its deactivation is never delivered) holds the quiesce until the
        /// node's bound is reached; the bound armed is the node's, and when it is reached the quorum is left, quiesce after PASSIVE.
        @Test
        void leaveQuorum_withALeaderThatNeverStepsDown_quiescesWhenTheNodesBoundIsReached() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                var executor = ReactExecutor.reactExecutor("LeaderManager", ReactGate.reactGate(() -> true, () -> false), ReactFailureSink.loggingOnly());
                var order = new CopyOnWriteArrayList<String>();
                var armed = new AtomicReference<Promise<Unit>>();
                var bounds = new CopyOnWriteArrayList<TimeSpan>();
                var deactivation = LeaderDeactivation.leaderDeactivation((promise, bound) -> arm(armed, bounds, promise, bound));

                deactivation.onLeaderChange(LeaderNotification.leaderChange(Option.some(NodeId.nodeId("self").unwrap()), true));
                var left = harness.wiring.leaveQuorum(() -> order.add("passive"),
                                                      () -> deactivation.delivered(executor, AetherNode.LEADER_DEACTIVATION_BOUND),
                                                      () -> order.add("quiesce"));

                assertThat(bounds).as("armed once, with the node's bound").containsExactly(AetherNode.LEADER_DEACTIVATION_BOUND);
                assertThat(left.isResolved()).as("the leader has not stepped down and the bound has not been reached").isFalse();
                assertThat(order).containsExactly("passive");

                armed.get().fail(Causes.cause("the bound was reached"));

                assertThat(left.await(LEFT_BOUND).isSuccess()).as("the quorum is left at the bound").isTrue();
                assertThat(order).containsExactly("passive", "quiesce");
                executor.close();
            }
        }

        private static Promise<Unit> arm(AtomicReference<Promise<Unit>> armed, List<TimeSpan> bounds, Promise<Unit> promise, TimeSpan bound) {
            armed.set(promise);
            bounds.add(bound);

            return promise;
        }

        private static Promise<Unit> taken(List<String> order, Promise<Unit> delivered) {
            order.add("delivered");

            return delivered;
        }

        /// 2b: a node consumer stuck in an input does not hold the stop past the bound; the stop says so and goes on.
        @Test
        void drainNode_ofAConsumerStuckInAnInput_givesUpAtTheBound_andWarns() throws InterruptedException {
            var release = new CountDownLatch(1);

            try (var logs = LogCapture.capture(Fsm2System.class, Level.ALL, false);
                 var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withStopWait(300).assembled()) {
                harness.selfReadyHook = () -> awaitRelease(release);
                harness.router.route(ClusterStateNotification.active());
                harness.awaitThat(() -> harness.selfReady.get() >= 1);
                var started = System.nanoTime();

                harness.system.unwrap().drainNode();

                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("bounded by the stop wait, not by the stuck input").isLessThan(Duration.ofSeconds(5));
                assertThat(logs.entries()).anySatisfy(line -> assertThat(line.message()).contains("deployment-fsm-stop-timeout")
                                                                                                           .contains("node did not drain within 300 ms"));
                release.countDown();
            } finally {
                release.countDown();
            }
        }

        /// The bound the system gives a stop by default is the design's ten seconds: a stop with a stuck consumer is still waiting after a second, and
        /// is released the moment the consumer is.
        @Test
        void drainNode_withTheDefaultBound_keepsWaitingForAStuckConsumer_andEndsWhenItIsFreed() throws InterruptedException {
            var release = new CountDownLatch(1);

            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withDefaultStopWait().assembled()) {
                harness.selfReadyHook = () -> awaitRelease(release);
                harness.router.route(ClusterStateNotification.active());
                harness.awaitThat(() -> harness.selfReady.get() >= 1);

                var stopper = Thread.ofPlatform().start(harness.system.unwrap()::drainNode);

                await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(5)).until(stopper::isAlive);
                release.countDown();
                stopper.join(5_000);

                assertThat(stopper.isAlive()).as("freed consumer, finished drain").isFalse();
            } finally {
                release.countDown();
            }
        }

        @Test
        void drainNode_ofAnIdleNode_returnsWithoutAWarning() {
            try (var logs = LogCapture.capture(Fsm2System.class, Level.ALL, false);
                 var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withStopWait(300).assembled()) {
                enterQuorum(harness);

                harness.system.unwrap().drainNode();

                assertThat(logs.entries()).noneSatisfy(line -> assertThat(line.message()).contains("deployment-fsm-stop-timeout"));
            }
        }

        private void awaitRelease(CountDownLatch release) {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -- N-5: the backlog sampler (design 3.1) --------------------------------------------------------------------------------
    @Nested
    class Backlog {
        private void fillAboveTheRaiseThreshold(Fsm2System system) {
            for (var i = 0; i <= BacklogSampler.RAISE_ABOVE; i++) {
                system.nodeInterpreter().deliver(new NodeFsmInput.TargetRemove(ORDERS.base()));
            }
        }

        private void awaitRelease(CountDownLatch release) {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean has(Fsm2Harness harness, OperatorWarningCode code) {
            synchronized (harness.warnings) {
                return harness.warnings.stream().anyMatch(warning -> warning.code() == code);
            }
        }

        /// A consumer that cannot keep up: past 10,000 queued inputs the sampler raises `deployment-fsm-backlog` for the node FSM (and none for the
        /// cluster FSM), and once the consumer has caught up, below 1,000, the recovery is raised on the same subject.
        @Test
        void aMailboxAboveTenThousand_raisesTheBacklogWarning_andBelowOneThousandClearsIt() {
            var clock = new ManualClock();
            var release = new CountDownLatch(1);

            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withClock(clock).assembled()) {
                harness.selfReadyHook = () -> awaitRelease(release);
                harness.router.route(ClusterStateNotification.active());
                harness.awaitThat(() -> harness.selfReady.get() >= 1);
                var system = harness.system.unwrap();

                fillAboveTheRaiseThreshold(system);
                clock.advance(BacklogSampler.INTERVAL_MS);

                await().atMost(Duration.ofSeconds(2)).until(() -> has(harness, OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG));
                assertThat(harness.warnings).filteredOn(warning -> warning.code() == OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG)
                                            .extracting(warning -> warning.subject())
                                            .containsExactly("core-1/node");

                release.countDown();
                harness.awaitThat(() -> system.nodeInterpreter().mailboxDepth() < BacklogSampler.CLEAR_BELOW);
                clock.advance(BacklogSampler.INTERVAL_MS);

                await().atMost(Duration.ofSeconds(2)).until(() -> has(harness, OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG_CLEARED));
            } finally {
                release.countDown();
            }
        }

        /// The cluster FSM's mailbox is watched too, under its own subject: held at `Rebuild` (the seams are sampled on its consumer), it backs up
        /// while the node FSM, idle, does not.
        @Test
        void aClusterMailboxAboveTenThousand_raisesTheBacklogWarning_onTheClusterSubjectOnly() {
            var clock = new ManualClock();
            var release = new CountDownLatch(1);

            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withClock(clock).assembled()) {
                harness.countedCoresHook = () -> holdTheClusterConsumer(release);
                harness.commitLeader();
                harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
                harness.awaitThat(() -> harness.system.unwrap().clusterInterpreter().mailboxDepth() == 0 && harness.samples.get() >= 2);
                var system = harness.system.unwrap();

                for (var i = 0; i <= BacklogSampler.RAISE_ABOVE; i++) {
                    system.clusterInterpreter().deliver(new ClusterInput.Flush());
                }

                clock.advance(BacklogSampler.INTERVAL_MS);

                await().atMost(Duration.ofSeconds(2)).until(() -> has(harness, OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG));
                assertThat(harness.warnings).filteredOn(warning -> warning.code() == OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG)
                                            .extracting(warning -> warning.subject())
                                            .containsExactly("core-1/cluster");
            } finally {
                release.countDown();
            }
        }

        private void holdTheClusterConsumer(CountDownLatch release) {
            if (Thread.currentThread().getName().equals("deploy-fsm-cluster")) {
                awaitRelease(release);
            }
        }

        /// The sampler stops with the system: a tick armed before the stop never samples after it, even over a mailbox that is still full.
        @Test
        void stop_cancelsTheBacklogSampler() {
            var clock = new ManualClock();
            var release = new CountDownLatch(1);

            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).withClock(clock).withStopWait(200).assembled()) {
                harness.selfReadyHook = () -> awaitRelease(release);
                harness.router.route(ClusterStateNotification.active());
                harness.awaitThat(() -> harness.selfReady.get() >= 1);
                var system = harness.system.unwrap();

                fillAboveTheRaiseThreshold(system);
                system.stop();
                clock.advance(BacklogSampler.INTERVAL_MS * 3);

                await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until(() -> !has(harness, OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG));
            } finally {
                release.countDown();
            }
        }
    }

    // -- B12, B14: the drain report and the state restore (design 4.4, 8.3) ----------------------------------------------------
    @Nested
    class DrainReportAndRestore {
        private void activateLeader(Fsm2Harness harness) {
            harness.commitLeader();
            harness.router.route(LeaderNotification.leaderChange(Option.some(Fsm2Harness.SELF), true));
            harness.awaitThat(() -> stepped(harness, "cluster", "Rebuild") == 1);
        }

        /// B12 / MT-14k: under FSM2 a drain report is the cluster FSM's `NodeDraining`, and the legacy eviction target never runs.
        @Test
        void aDrainReport_underFsm2_reachesTheClusterFsm_notTheLegacyTarget() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                var legacy = new CopyOnWriteArrayList<NodeId>();

                activateLeader(harness);
                harness.wiring.drainReport(legacy::add).accept(Fsm2Harness.PEER);

                harness.awaitThat(() -> stepped(harness, "cluster", "NodeDraining") == 1);
                assertThat(legacy).as("MT-14k: the legacy target is not bound under FSM2").isEmpty();
                assertThat(harness.wiring.recorded()).contains("B12.DrainReport -> fsm2:Fsm2System.onNodeDraining");
            }
        }

        @Test
        void aDrainReport_underLegacy_runsTheLegacyTarget_andNoClusterFsmExists() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                var legacy = new CopyOnWriteArrayList<NodeId>();

                harness.wiring.drainReport(legacy::add).accept(Fsm2Harness.PEER);

                assertThat(legacy).containsExactly(Fsm2Harness.PEER);
                assertThat(harness.system.isPresent()).isFalse();
                assertThat(harness.wiring.recorded()).contains("B12.DrainReport -> legacy");
            }
        }

        /// N-3: a worker has no cluster FSM, so its drain report is a counted drop under FSM2.
        @Test
        void aDrainReport_ofAWorker_isACountedDrop_underFsm2() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                var legacy = new CopyOnWriteArrayList<NodeId>();

                harness.wiring.drainReport(legacy::add).accept(Fsm2Harness.PEER);

                assertThat(legacy).isEmpty();
                assertThat(harness.registry.counter("aether_deployment_fsm2_dropped_total", "point", "B12.DrainReport").count()).isEqualTo(1.0);
            }
        }

        /// B14 / MT-14m / W-25: a restore under an Active leader's FSM re-incarnates it (the rule that is expected never to fire); the hook is the
        /// FSM2 one, not a legacy no-op.
        @Test
        void aStateRestore_underAnActiveLeaderFsm_reincarnatesIt() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                activateLeader(harness);

                harness.wiring.stateRestored().run();

                harness.awaitThat(() -> harness.count("StateRestored", "reincarnated", "cluster") == 1.0);
                harness.awaitThat(() -> stepped(harness, "cluster", "Deactivate") == 1);
                assertThat(harness.wiring.recorded()).contains("B14.StateRestored -> fsm2:Fsm2System.onStateRestored");
            }
        }

        /// A restore implies PASSIVE first, so the leader's FSM is ordinarily Dormant, and nothing is made up (W-25: no synthetic pair).
        @Test
        void aStateRestore_underADormantLeaderFsm_doesNothing() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, false).assembled()) {
                harness.wiring.stateRestored().run();

                harness.awaitThat(() -> harness.count("StateRestored", "skipped_dormant", "cluster") == 1.0);
                assertThat(stepped(harness, "cluster", "Rebuild")).isZero();
                assertThat(stepped(harness, "cluster", "Deactivate")).isZero();
            }
        }

        @Test
        void aStateRestore_underLegacy_addsNothing() {
            try (var harness = new Fsm2Harness(DeploymentEngine.LEGACY, false).assembled()) {
                harness.wiring.stateRestored().run();

                assertThat(harness.wiring.recorded()).contains("B14.StateRestored -> legacy");
                assertThat(harness.system.isPresent()).isFalse();
            }
        }

        @Test
        void aStateRestore_ofAWorker_isACountedDrop_underFsm2() {
            try (var harness = new Fsm2Harness(DeploymentEngine.FSM2, true).assembled()) {
                harness.wiring.stateRestored().run();

                assertThat(harness.registry.counter("aether_deployment_fsm2_dropped_total", "point", "B14.StateRestored").count()).isEqualTo(1.0);
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
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId OTHER_WORKER = NodeId.nodeId("worker-2").unwrap();

    private static Option<ClusterModel> activeModel(Fsm2Harness harness) {
        return harness.system.flatMap(system -> system.clusterInterpreter().state() instanceof ClusterState.Active active
                                                ? Option.some(active.model())
                                                : Option.none());
    }

    private static boolean admitted(Fsm2Harness harness, NodeId node) {
        return activeModel(harness).flatMap(model -> model.admission(node)).isPresent();
    }

    private static WorkerJoinDecision workerJoined(NodeId worker) {
        return WorkerJoinDecision.workerJoinDecision(worker, "worker", HlcTimestamp.ZERO);
    }

    private static ValuePut<AetherKey.NodePlacementKey, AetherValue.NodePlacementValue> placementPut(NodeId worker) {
        return new ValuePut<>(new KVCommand.Put<>(new AetherKey.NodePlacementKey(worker),
                                                  new AetherValue.NodePlacementValue("default", Option.none(), "i-1")),
                              Option.none());
    }

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
