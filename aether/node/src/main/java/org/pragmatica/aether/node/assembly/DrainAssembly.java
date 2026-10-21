// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.fsm.MembershipTransitionRecord;
import org.pragmatica.aether.deployment.membership.ntt.DrainProcedure;
import org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossDetector;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossIntent;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.ClusterSyncPongSignalFan;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.DhtDeparturePush;
import org.pragmatica.aether.node.StorageFactory;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.worker.governor.CommunityDrainCoordinator;
import org.pragmatica.aether.worker.isolation.CoreAbsenceDetector;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTRebalancer;
import org.pragmatica.dht.DHTTopologyListener;
import org.pragmatica.dht.DeparturePushObserver;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.buildQuorumCoConfirmation;
import static org.pragmatica.aether.node.AetherNode.configuredWorker;
import static org.pragmatica.aether.node.AetherNode.installedVoterIds;
import static org.pragmatica.aether.node.AetherNode.selfPlacementOperation;
import static org.pragmatica.aether.node.AetherNode.stopStorageMaintenance;
import static org.pragmatica.aether.node.AetherNode.wireQuorumCountTriggers;


/// The drain unit of the node assembly: everything that decides WHEN this node drains (quorum loss, core absence,
/// an operator command) and WHAT a drain does (stop scheduled fires and storage maintenance, announce, push DHT chunks,
/// exit). Moved out of `AetherNode` without change; the root calls the phases in the order it always ran them.
public sealed interface DrainAssembly {
    /// Everything the drain infrastructure reads from the assembly built so far.
    record InfrastructureInputs(AetherNodeConfig config,
                                KVStore<AetherKey, AetherValue> kvStore,
                                RabiaNode<KVCommand<AetherKey>> clusterNode,
                                InvocationHandler invocationHandler,
                                AtomicReference<BooleanSupplier> workerProjectionFreshRef,
                                AtomicReference<Predicate<NodeId>> departingSendersRef,
                                ClusterSyncCollector metricsCollector,
                                DHTRebalancer dhtRebalancer,
                                DHTTopologyListener dhtTopologyListener,
                                NodeReportedStateHolder nodeReportedStateHolder,
                                Map<String, StorageFactory.StorageSetup> storageSetups,
                                Runnable jvmExit) {}

    /// The drain infrastructure the rest of the assembly binds to. The three forward-declared refs are bound later by their
    /// owners (the cluster-event aggregator, the scheduled-task manager).
    record Infrastructure(InFlightRequestTracker inFlightTrackerForDrain,
                          AtomicReference<Consumer<DrainReason>> clusterEventDrainEmitterRef,
                          AtomicReference<DeparturePushObserver> departurePushObserverRef,
                          AtomicReference<Runnable> scheduledTaskDrainHookRef,
                          DrainProcedure drainProcedure,
                          CommunityDrainCoordinator communityDrain) {}

    /// Everything the quorum-loss and core-absence drain triggers read from the assembly built so far.
    /// `defer` is `PeriodicTasks::defer`, passed in because the arming list is package-private to the node package.
    record QuorumLossInputs(AetherNodeConfig config,
                            RabiaNode<KVCommand<AetherKey>> clusterNode,
                            LeaderReconciler leaderReconciler,
                            QuorumLossDetector quorumLossDetector,
                            MembershipFsm membershipFsm,
                            CoreSwimHealthDetector swimHealthDetector,
                            BooleanSupplier swimIsBootingSupplier,
                            ClusterSyncCollector metricsCollector,
                            NodeReportedStateHolder nodeReportedStateHolder,
                            DrainProcedure drainProcedure,
                            AlertManager alertManager,
                            BooleanSupplier isLeaderSupplier,
                            DrainCommandRegistry drainCommandRegistry,
                            OperatorWarningSink operatorWarningSink,
                            Consumer<Supplier<ScheduledFuture<?>>> defer) {}

    /// The quorum-loss listener chain and the core-absence detector, which the assembly later reads again.
    record QuorumLossDrain(Consumer<QuorumLossIntent> quorumLossChain, CoreAbsenceDetector coreAbsenceDetector) {}

    /// Drain infrastructure: the in-flight tracker, the invocation admission gate, the DHT departure push and the `DrainProcedure`.
    static Infrastructure drainInfrastructure(InfrastructureInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var invocationHandler = in.invocationHandler();
        var workerProjectionFreshRef = in.workerProjectionFreshRef();
        var departingSendersRef = in.departingSendersRef();
        var metricsCollector = in.metricsCollector();
        var dhtRebalancer = in.dhtRebalancer();
        var dhtTopologyListener = in.dhtTopologyListener();
        var nodeReportedStateHolder = in.nodeReportedStateHolder();
        var storageSetups = in.storageSetups();
        var jvmExit = in.jvmExit();
        // Drain infrastructure: tracker is shared with NodeLifecycleRoutes (/api/node/inflight).
        // The DRAINING-atom-writing ConsensusDrainCoordinator was removed in Phase 2b — the
        // FSM-integrated DrainCoordinator wired into MembershipFsm/CTM is now a no-op stub.
        // The §8.2 unified drain procedure is owned by DrainProcedure (constructed below).
        var inFlightTrackerForDrain = InFlightRequestTracker.inFlightRequestTracker();

        invocationHandler.setInvocationAdmission(org.pragmatica.aether.invoke.InvocationAdmission.gated(inFlightTrackerForDrain,
                                                                                                        () -> workerProjectionFreshRef.get()
                                                                                                                                      .getAsBoolean()));
        // E2 Phase 2b (2026-05-28): DrainProcedure replaces SelfDrainCoordinator's execution
        // surface. It is a *pure procedure* — no triggers, no periodic ticks, no orphan
        // checker. The single caller in Phase 2b is the QuorumLossDetector quorum-loss
        // listener wired further down. Operator/CTM-initiated drain is delivered as a `DRAIN`
        // command on the leader↔node heartbeat (membership v2 §7.5.4) — there is no KV drain
        // record. Drain is heartbeat-reported and leader-cached, never stored in the KV-Store.
        //
        // `jvmExit` is threaded in from the factory: production is
        // `Runtime.getRuntime().halt(2)`, single-JVM hosts (Forge/Ember) pass a per-node
        // callback that stops the node gracefully without taking down the whole test JVM.
        //
        // `swimLeaveEmitter` is a no-op for Phase 2b — the SWIM `LEAVE` acceleration wiring
        // (spec §8.2 step 3) lands in Phase 6 once the SwimHealthDetector exposes a
        // `voluntaryLeave` API. The drain still halts the node; LEAVE is purely an
        // acceleration of peer-side suspect aging, not a correctness requirement.
        // Item-8 graft (stream-namespaces rebuild): forward-declared best-effort emitter resolved
        // once the ClusterEventAggregator is constructed below. DrainProcedure invokes it once at
        // the INACTIVE→DRAINING transition (single-shot via its CAS gate); the lambda no-ops until
        // the aggregator is bound. Kept as a Consumer<DrainReason> so aether-deployment stays free
        // of any ClusterEvent dependency.
        var clusterEventDrainEmitterRef = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<org.pragmatica.aether.deployment.cluster.DrainReason>>(reason -> {});
        // #427 graceful-departure push: the leaving node pushes its held DHT chunks to their new
        // replicas, ack-gated, before it halts. The DrainProcedure invokes this supplier once at
        // DRAINING and its quiesced-fork exit waits for it (bounded by the push's own budget; the
        // grace fork never does). The observer — resolved once the ClusterEventAggregator is bound
        // below — turns a budget overrun into a DeparturePushIncomplete event; it stays a no-op until
        // then, keeping aether-deployment free of any ClusterEvent / DHT-event dependency.
        // #1818: the push excludes every node the leader commanded to drain alongside this one (the
        // ping's global drain set), so it never lands on, or counts as a survivor, a co-drainer.
        var departurePushObserverRef = new java.util.concurrent.atomic.AtomicReference<>(DeparturePushObserver.noop());
        var movementDrain = new AtomicReference<Option<CommunityDrainCoordinator>>(Option.none());

        departingSendersRef.set(DhtDeparturePush.departingSenders(metricsCollector, dhtTopologyListener));
        var dhtDeparturePush = DhtDeparturePush.dhtDeparturePush(dhtRebalancer,
                                                                 metricsCollector,
                                                                 departurePushObserverRef::get);
        Supplier<Promise<Unit>> departurePush = () -> dhtDeparturePush.get()
                                                                      .flatMap(_ -> movementDrain.get()
                                                                                                 .fold(Promise::unitPromise,
                                                                                                       CommunityDrainCoordinator::onQuiesced));
        // #273 item 1: forward-declared hook resolved once the ScheduledTaskManager is built below. The
        // drain edge for scheduled tasks is THIS emitter, not a MembershipDecision — `NodeDraining` has
        // no producer since the membership-v2 finale removed the per-node lifecycle projection.
        var scheduledTaskDrainHookRef = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> {});
        var drainProcedure = DrainProcedure.drainProcedure(inFlightTrackerForDrain,
                                                           () -> {},
                                                           drainInitiatedEmitter(scheduledTaskDrainHookRef,
                                                                                 () -> stopStorageMaintenance(storageSetups),
                                                                                 clusterEventDrainEmitterRef),
                                                           departurePush,
                                                           jvmExit);
        var communityDrain = CommunityDrainCoordinator.communityDrainCoordinator(config.self(),
                                                                                 () -> kvStore.getTyped(LeaderKey.INSTANCE,
                                                                                                        LeaderValue.class)
                                                                                              .map(LeaderValue::leader),
                                                                                 () -> selfPlacementOperation(kvStore,
                                                                                                              config.self()),
                                                                                 clusterNode.network()::send,
                                                                                 () -> commandedDrain(drainProcedure,
                                                                                                      nodeReportedStateHolder));

        movementDrain.set(Option.some(communityDrain));

        return new Infrastructure(inFlightTrackerForDrain,
                                  clusterEventDrainEmitterRef,
                                  departurePushObserverRef,
                                  scheduledTaskDrainHookRef,
                                  drainProcedure,
                                  communityDrain);
    }

    /// DRAINING reports from the pong fan reach the membership FSM and the cluster deployment manager.
    static Unit bindDrainReport(ClusterSyncPongSignalFan pongSignalFan,
                                MembershipFsm membershipFsm,
                                ClusterDeploymentManager clusterDeploymentManager) {
        // #1054: every DRAINING pong the leader records is a drain acknowledgement, latched by the FSM for the
        // member's drain episode. It is latched as the pong lands because the readiness sweep forgets a halted
        // drainee within three pings, long before the DEPARTING timeout. An acknowledged drain terminalizes at
        // expiry; an undelivered DRAIN to a live target is withdrawn to MEMBER instead of reaped.
        pongSignalFan.onDrainingReported(drainReportListener(membershipFsm, clusterDeploymentManager));

        return Unit.unit();
    }

    /// Quorum loss and core absence drive the drain procedure; the drain set observer marks announced departures.
    static QuorumLossDrain quorumLossDrain(QuorumLossInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var leaderReconciler = in.leaderReconciler();
        var quorumLossDetector = in.quorumLossDetector();
        var membershipFsm = in.membershipFsm();
        var swimHealthDetector = in.swimHealthDetector();
        var swimIsBootingSupplier = in.swimIsBootingSupplier();
        var metricsCollector = in.metricsCollector();
        var nodeReportedStateHolder = in.nodeReportedStateHolder();
        var drainProcedure = in.drainProcedure();
        var alertManager = in.alertManager();
        var isLeaderSupplier = in.isLeaderSupplier();
        var drainCommandRegistry = in.drainCommandRegistry();
        var operatorWarningSink = in.operatorWarningSink();
        var defer = in.defer();
        // P3 (membership unification): quorum-loss is detected by QuorumLossDetector from the
        // unified tracker's stable membership (armed after first quorum; grace =
        // quorumLossDrainThreshold) and drives the §8.2 unified `DrainProcedure` directly.
        // Trigger detection (QuorumLossDetector) and procedure execution (DrainProcedure)
        // stay separated.
        Consumer<QuorumLossIntent> quorumLossChain = ((Consumer<QuorumLossIntent>) leaderReconciler::onQuorumLossIntent).andThen(intent -> drainProcedure.initiate(DrainReason.QUORUM_LOSS))
                                                                                                                        .andThen(intent -> nodeReportedStateHolder.onDrainStarted());

        quorumLossDetector.setQuorumLossListener(quorumLossChain);
        wireQuorumCountTriggers(quorumLossDetector, membershipFsm, clusterNode::onVoterConfiguration);
        // Fix C (split-brain self-fence co-confirmation): before the QUORUM_LOSS drain commits, the
        // detector consults this snapshot of the FSM's counted (MEMBER+SUSPECT) set vs raw-SWIM
        // liveness of the members missing from the STRICT (MEMBER-only) count. The strict count is
        // the detector's numerator; a false-FAULTY storm can wedge a still-present, SWIM-alive peer
        // pre-MEMBER (SUSPECT), dropping strict below quorum while the cluster is intact. Suppression
        // requires counted-still-quorate AND every stuck member SWIM-alive — a genuine partition
        // fails both (unreachable members are NOT SWIM-alive and age out of SUSPECT), so the real
        // self-fence is never masked.
        quorumLossDetector.setCoConfirmationSupplier(() -> buildQuorumCoConfirmation(membershipFsm,
                                                                                     swimHealthDetector::healthOf,
                                                                                     installedVoterIds(clusterNode)));
        // A6: gate the quorum-loss self-drain with the SAME cold-boot window the SWIM FAULTY-suppression
        // uses. On a simultaneous full-cluster restart SWIM's first probe-acks lag the QUIC attach, so the
        // detector's SWIM-alive count momentarily decays below threshold and healthy nodes would self-fence
        // before convergence (the 3/5-wedge root cause). Deferring the fence during the bounded cold-boot
        // window lets all nodes reform; a genuine minority still self-fences once the window elapses.
        quorumLossDetector.setColdBootSupplier(swimIsBootingSupplier);
        metricsCollector.setDrainCommandHandler(() -> commandedDrain(drainProcedure, nodeReportedStateHolder));
        // #2014: the leader is the only node that saw DrainRequested, so its FSM edge is the only place the
        // announced-departure mark was set. The leader's broadcast ping carries the global drain set to every
        // peer: mark each commanded node here so a follower's DEAD edge reads a planned departure as one.
        var nodeReadyAgain = nodeReadyAgain(metricsCollector::reportedStates);

        metricsCollector.setDrainSetObserver(drainSetObserver(alertManager, config.self(), nodeReadyAgain));
        // The leader never processes its own ping, so it re-derives its mark from its own registry each interval. Only
        // the LEADER: an operator drain never leaves the registry, so a node that was leader once and is not now would
        // keep re-marking a node that restarted under the new leader, silencing that node's next real crash (v-2043 N1).
        var ownDrainRecord = leaderOnlyDrainRecordTick(isLeaderSupplier,
                                                       drainCommandRegistry::drainTargets,
                                                       drainSetObserver(alertManager, config.self(), nodeReadyAgain));

        defer.accept(() -> SharedScheduler.scheduleAtFixedRate(ownDrainRecord,
                                                               config.timeouts().cluster().pingInterval()));
        // Workers renew core contact from identified core ping OR pong responses. This is
        // reachability evidence only; all mutations still require committed core authority.
        var coreAbsenceDetector = CoreAbsenceDetector.coreAbsenceDetector(config.timeouts().cluster().coreAbsence(),
                                                                          config.timeouts().cluster().pingInterval());

        coreAbsenceDetector.setCoreAbsenceListener(_ -> initiateDrain(drainProcedure,
                                                                      nodeReportedStateHolder,
                                                                      DrainReason.CORE_ABSENCE));
        coreAbsenceDetector.setFenceSuppressor(() -> !configuredWorker(config));
        coreAbsenceDetector.setOperatorWarningSink(operatorWarningSink);
        metricsCollector.setCorePingObserver(coreAbsenceDetector::recordCorePing);
        coreAbsenceDetector.start();

        return new QuorumLossDrain(quorumLossChain, coreAbsenceDetector);
    }

    /// Operator/controller drain sink (M10, cluster-topology-overhaul Wave 7): every drain command
    /// is BOTH enqueued into the `DrainCommandRegistry` (the leader's cluster-sync ping carries it;
    /// the target self-drains via its `DrainProcedure`, unchanged) AND routed through the
    /// authoritative `MembershipFsm` as a `DrainRequested` event — the target transitions to
    /// DEPARTING (stops counting toward effective, H2 timeout terminalizes it if it goes silent,
    /// higher-incarnation `SwimHealthy` recovers it mid-drain) instead of the drain bypassing the
    /// FSM entirely. The FSM holder is deref'd lazily: the registry-backed sinks are wired before
    /// the FSM exists; pre-FSM the registry path alone applies (boot window only).
    @Contract
    static void requestDrainThroughFsm(DrainCommandRegistry drainCommandRegistry,
                                       AtomicReference<MembershipFsm> membershipFsmRef,
                                       NodeId target) {
        drainCommandRegistry.requestDrain(target);
        Option.option(membershipFsmRef.get()).onPresent(fsm -> fsm.onDrainRequested(target));
    }

    /// #2063: an operator drain target stays in the leader's `DrainCommandRegistry` until something clears it, and only the
    /// drains the topology manager started itself had a removal path. A node that was drained, left and came back under the
    /// SAME id (ids come from config) was then told to drain again by the same leader. The entry ends with the node's
    /// departure, on either of two edges: into DEAD (the departure the drain was for), and out of DEAD (a drain commanded on
    /// an already-DEAD node is ignored by the FSM, so it leaves no edge of its own and would otherwise be inherited by the
    /// rejoin). It deliberately does NOT end on DEPARTING back to MEMBER: that edge is the FSM's VIEW refuting or withdrawing
    /// the drain (a higher-incarnation `SwimHealthy` from a live SWIM refutation, or the #1054 undelivered-drain withdrawal),
    /// while the command is still in flight and the target is still to be told to drain. Clearing there stops the leader
    /// pinging the DRAIN and the node never leaves.
    @Contract
    static void clearOperatorDrainOnDeath(DrainCommandRegistry drainCommandRegistry,
                                          MembershipTransitionRecord record) {
        if (isDrainEpisodeEnd(record)) {
            drainCommandRegistry.clearDrain(record.nodeId());
        }
    }

    private static boolean isDrainEpisodeEnd(MembershipTransitionRecord record) {
        return "Dead".equals(record.toState()) || "Dead".equals(record.fromState());
    }

    @Contract
    private static void commandedDrain(DrainProcedure drainProcedure, NodeReportedStateHolder holder) {
        initiateDrain(drainProcedure, holder, DrainReason.COMMANDED);
    }

    private static void initiateDrain(DrainProcedure drainProcedure,
                                      NodeReportedStateHolder holder,
                                      DrainReason reason) {
        holder.onDrainStarted();
        drainProcedure.initiate(reason);
    }

    /// `DrainProcedure`'s single-shot `drainInitiatedEmitter`, invoked once inside the INACTIVE->DRAINING
    /// CAS and therefore the node's one drain edge, shared by all three triggers (QUORUM_LOSS,
    /// CORE_ABSENCE, COMMANDED). Three consumers, in falling order of consequence:
    ///
    ///   1. #273 item 1 — stop this node's ALL-mode scheduled fires. A correctness action.
    ///   2. Stop this node's storage promotion and per-node demotion.
    ///   3. #565 — emit `SelfDrainInitiated`, the one event that explains why a node left.
    ///
    /// The two hooks run FIRST but each inside its own no-throw guard, so neither can delay nor
    /// suppress (3). `DrainProcedure.emitDrainInitiatedSafely` already wraps the whole consumer, but that
    /// outer guard would let a throw from (1) or (2) swallow (3) — the exact loss #565 was filed for.
    ///
    /// Package-private and named rather than an inline lambda so the composition itself is testable:
    /// `ScheduledTaskDrainWiringBootTest` drives a real drain through it. Both arguments are
    /// forward-declared refs — the manager and the event aggregator are both constructed after
    /// `DrainProcedure`.
    static Consumer<DrainReason> drainInitiatedEmitter(AtomicReference<Runnable> scheduledTaskDrainHookRef,
                                                       Runnable storageMaintenanceStop,
                                                       AtomicReference<Consumer<DrainReason>> clusterEventDrainEmitterRef) {
        return reason -> {
            runDrainHookSafely("Scheduled-task", scheduledTaskDrainHookRef.get());
            runDrainHookSafely("Storage-maintenance", storageMaintenanceStop);
            clusterEventDrainEmitterRef.get()
                                       .accept(reason);
        };
    }

    private static void runDrainHookSafely(String name, Runnable hook) {
        try {
            hook.run();
        } catch (Throwable t) {
            LOG.warn("{} drain hook failed: {} — drain proceeds", name, t.getMessage());
        }
    }

    /// The leader's own-registry tick (#2014, v-2043 N1): feeds the registry's drain targets to `record` only while this
    /// node is the leader. Package-private so the gate and the feed are pinned without booting a node.
    static Runnable leaderOnlyDrainRecordTick(BooleanSupplier isLeader,
                                              Supplier<Set<NodeId>> registryTargets,
                                              Consumer<Set<NodeId>> record) {
        return () -> {
            if (isLeader.getAsBoolean()) {
                record.accept(registryTargets.get());
            }
        };
    }

    /// #2014: the drain record (the leader's broadcast set, or the leader's own registry) marks each commanded node
    /// (never self) as an announced departure on this observer. `nodeReadyAgain` is the only thing that ends a mark
    /// early. Package-private so the composition is pinned without booting a node.
    static Consumer<Set<NodeId>> drainSetObserver(AlertManager alertManager,
                                                  NodeId self,
                                                  java.util.function.Predicate<NodeId> nodeReadyAgain) {
        return drained -> alertManager.observeDrainSet(drained, self, System.currentTimeMillis(), nodeReadyAgain);
    }

    /// The node reports itself READY in the readiness view this observer holds (the leader's pong fan, or a
    /// follower's cache of it): alive, responsive and not draining. The only evidence that ends a planned-departure
    /// mark short of the DEAD edge (#2014). Absence from the view is NOT evidence. Package-private for the pin.
    static java.util.function.Predicate<NodeId> nodeReadyAgain(java.util.function.Supplier<Map<NodeId, NodeReportedState>> reportedStates) {
        return id -> reportedStates.get()
                                   .get(id) == NodeReportedState.READY;
    }

    /// #688: one DRAINING report feeds both halves of a drain — the membership FSM's acknowledgement
    /// (#1054) and the leader-side eviction loop. The CDM's `MembershipDecision.NodeDraining` arm is
    /// never emitted (membership-v2 finale), so this listener is the ONLY production entry to
    /// `startDrainEviction`. Package-private so the composition is pinned without booting a node.
    static Consumer<NodeId> drainReportListener(MembershipFsm membershipFsm,
                                                ClusterDeploymentManager clusterDeploymentManager) {
        return drainingNode -> {
            membershipFsm.onDrainAcknowledged(drainingNode);
            clusterDeploymentManager.onNodeDraining(drainingNode);
        };
    }

    record unused() implements DrainAssembly {}
}
