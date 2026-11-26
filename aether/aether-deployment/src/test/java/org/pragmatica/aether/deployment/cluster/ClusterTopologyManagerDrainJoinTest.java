// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #3754 (b1), the joins half: how the manager's one pending map reacts to a second writer while the witness of an attempt is
/// in flight. The witness is a controlled sink, so a test decides when and how it answers; every join of an id goes through
/// `onWorkerJoin` (the production path: the incarnation is forgotten, the pending attempt cancelled, the re-admission asked),
/// never through the map. The registry is a set the sink's write adds to and the manager's clear removes from.
class ClusterTopologyManagerDrainJoinTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId PEER_A = nodeId("node-a").unwrap();
    private static final NodeId PEER_B = nodeId("node-b").unwrap();
    private static final NodeId PEER_C = nodeId("node-c").unwrap();
    private static final NodeId TARGET = nodeId("node-d").unwrap();
    private static final NodeId CONTROL = nodeId("node-control").unwrap();
    private static final TimeSpan GRACE = timeSpan(300).millis();
    private static final HlcTimestamp HLC = new HlcTimestamp(HlcTimestamp.pack(1L, 0), SELF);
    private static final String CANCELLED = OperatorWarningCode.NODE_DRAIN_CANCELLED_REJOINED.code();
    private static final String EVICTED = OperatorWarningCode.NODE_RETIRED_IDENTITY_REJOINED.code();
    private static final String UNRECONCILED = OperatorWarningCode.EXTERNAL_REJOIN_UNRECONCILED.code();
    private static final String BARRIER = OperatorWarningCode.NODE_NEVER_JOINED.code();
    private static final Cause READMISSION_REFUSED = new NodeLifecycleManager.ReadmissionRefused("no capacity for the node");
    private static final Cause LEDGER_UNREADABLE = Causes.cause("the ledger could not be read");
    private static final Cause RETIRED = new NodeLifecycleManager.RetiredIdentityRejoined("rejoined", "its-replacement");
    private static final Cause SUCCEEDED = Causes.cause("the outcome succeeded");
    private static final Cause WITNESS_REFUSED = Causes.cause("witness refused");
    private static final Cause APPLY_TIMEOUT = new ConsensusError.ApplyTimeout(1000L);

    private final Set<NodeId> registry = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<NodeId> registryWrites = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> raised = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> texts = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Boolean> evictionDecisionHeldTheMonitor = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink warningSink = OperatorWarningSink.handingOffTo(this::recordWarning);
    private WitnessSink sink;
    private RecordingLifecycle lifecycle;
    private ClusterTopologyManager ctm;

    @BeforeEach
    void setUp() {
        lifecycle = new RecordingLifecycle();
        sink = new WitnessSink(this::addToRegistry);
        ctm = manager(sink);
        ctm.setOperatorWarningSink(warningSink);
        ctm.activate();
    }

    @AfterEach
    void tearDown() {
        ctm.deactivate();
    }

    private void recordWarning(OperatorWarning warning) {
        texts.add(warning.message());
        raised.add(warning.code().code());
    }

    private void addToRegistry(NodeId target) {
        registryWrites.add(target);
        registry.add(target);
    }

    private ClusterTopologyManager manager(DrainSink drainSink) {
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
        var peer = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("localhost", 5001).unwrap());
        var config = new TopologyConfig(SELF, 5, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(self, peer));
        var observer = monitorProbing(TopologyObserver.topologyObserver(config, quietRouter(), new EmptySnapshotSource()).unwrap());
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), GRACE).unwrap();
        var liveness = MembershipLiveness.membershipLiveness(() -> Set.of(),
                                                             () -> Set.of(),
                                                             _ -> false,
                                                             _ -> false,
                                                             () -> Set.of(),
                                                             () -> 5,
                                                             _ -> Option.none());
        var manager = ClusterTopologyManagerRecord.clusterTopologyManagerRecord(observer,
                                                                                lifecycle,
                                                                                autoHeal,
                                                                                DeploymentMap.deploymentMap(),
                                                                                new EmptySnapshotSource(),
                                                                                this::clusterConfig,
                                                                                _ -> Promise.success(List.of()),
                                                                                () -> ClusterPhase.NORMAL,
                                                                                System::currentTimeMillis,
                                                                                drainSink,
                                                                                registry::remove,
                                                                                Option::none,
                                                                                liveness);

        manager.setRetirementRefusal(_ -> Option.none());
        return manager;
    }

    /// The observer the manager reads its own id from inside `decideEviction`: the probe samples the monitor by the calling thread,
    /// from the decision's own frame, and the call itself goes on to the real observer.
    private TopologyObserver monitorProbing(TopologyObserver real) {
        return (TopologyObserver) Proxy.newProxyInstance(TopologyObserver.class.getClassLoader(),
                                                         new Class<?>[]{TopologyObserver.class},
                                                         new SelfProbe(real));
    }

    private void sampleEvictionDecision(Method method) {
        if (method.getName().equals("self") && insideMethod("decideEviction")) {
            evictionDecisionHeldTheMonitor.add(Thread.holdsLock(ctm));
        }
    }

    private static boolean insideMethod(String name) {
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getMethodName().equals(name)));
    }

    private Option<ClusterConfigValue> clusterConfig() {
        return Option.some(new ClusterConfigValue(Option.none(),
                                                  "",
                                                  "1.0.0",
                                                  List.of(new TopologyEntry("primary", "core", 5)),
                                                  3,
                                                  9,
                                                  "test",
                                                  1L,
                                                  System.currentTimeMillis()));
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    /// A join of `id` as the leader sees it: the incarnation is forgotten, a pending attempt of the id is cancelled and the
    /// lifecycle manager is asked to re-admit it (the answer is `lifecycle.readmission`).
    private void join(NodeId id) {
        ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(id, "worker", HLC));
    }

    /// Opens an eviction of `TARGET` whose witness stays in flight: the re-admission is refused as a retired identity.
    private void openEvictionWitness() {
        lifecycle.readmission.set(() -> RETIRED.promise());
        join(TARGET);
        assertThat(sink.requests()).as("the eviction asked the witness once").hasSize(1);
    }

    private static Cause failureOf(Result<Unit> outcome) {
        return outcome.fold(cause -> cause, _ -> SUCCEEDED);
    }

    /// The outcome of an attempt, read after a bounded wait: a resolution that never arrives fails the test instead of hanging it.
    private static Result<Unit> settled(Promise<Unit> outcome) {
        await().atMost(Duration.ofSeconds(5)).until(outcome::isResolved);
        return outcome.await();
    }

    private static void awaitResolved(Promise<Unit> promise) {
        await().atMost(Duration.ofSeconds(5)).until(promise::isResolved);
    }

    private void awaitRequests(int count) {
        await().atMost(Duration.ofSeconds(5)).until(() -> sink.requests().size() == count);
    }

    /// Resolves once every handler attached to `promise` before this call has run: the event handlers of one promise run in
    /// attach order on one thread. Attach it before the promise resolves, or it runs at once, ahead of the others.
    private static Promise<Unit> afterHandlers(Promise<Unit> promise) {
        var done = Promise.<Unit> promise();

        promise.onResultRun(() -> done.resolve(Result.unitResult()));
        return done;
    }

    private void awaitRaised(String code) {
        await().atMost(Duration.ofSeconds(5)).until(() -> raised.contains(code));
    }

    /// The positive control of an absence read: a control drain of another target is accepted after the attempt under test,
    /// with the same grace, so once the control is reaped a timer of the first target (armed earlier, same delay) would
    /// have fired already. The reap's last step is the clear of the registry (`reapOwned` clears after the terminate), so the
    /// control is reaped only once it has left the registry: a registry read after this call holds no reap in flight.
    private void awaitControlReap() {
        ctm.drainNode(CONTROL, DrainReason.OPERATOR_COMMAND);
        sink.accept(sink.requests().size() - 1);
        await().atMost(Duration.ofSeconds(5)).until(() -> lifecycle.terminated().contains(CONTROL));
        awaitLeftRegistry(CONTROL);
    }

    private void awaitLeftRegistry(NodeId target) {
        await().atMost(Duration.ofSeconds(5)).until(() -> !registry.contains(target));
    }

    /// The warning hand-off is one FIFO queue: once the barrier arrives, every warning raised before it has been delivered.
    private void awaitWarningBarrier() {
        OperatorWarnings.raise(LoggerFactory.getLogger(ClusterTopologyManagerDrainJoinTest.class),
                               warningSink,
                               OperatorWarningCode.NODE_NEVER_JOINED,
                               "barrier",
                               "barrier");
        awaitRaised(BARRIER);
    }

    /// TB6: a second drain of a target that has a pending attempt joins it. One witness request, and the second caller's
    /// outcome is the first's: still unresolved while the witness is in flight, resolved by the same accept.
    @Test
    void tb6_secondDrainJoinsThePendingAttempt_andGetsItsOutcome() {
        var first = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);
        var second = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        assertThat(sink.requests()).as("one attempt, one witness").hasSize(1);
        assertThat(second.isResolved()).as("the joined caller waits for the attempt's outcome").isFalse();

        sink.accept(0);

        assertThat(settled(first).isSuccess()).isTrue();
        assertThat(settled(second).isSuccess()).isTrue();
        assertThat(registryWrites).as("one registry write for the one attempt").containsExactly(TARGET);
    }

    /// TB6, the refusal side: a refused witness fails the opener and the joined caller with the one cause.
    @Test
    void tb6_refusedWitness_failsTheJoinedCallerWithTheOpenersCause() {
        var first = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);
        var second = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        assertThat(second.isResolved()).as("nothing answered yet").isFalse();

        sink.refuse(0, WITNESS_REFUSED);

        assertThat(failureOf(settled(first))).isSameAs(WITNESS_REFUSED);
        assertThat(failureOf(settled(second))).isSameAs(WITNESS_REFUSED);
        assertThat(sink.requests()).hasSize(1);
        assertThat(registryWrites).isEmpty();
    }

    /// TB6e: an eviction of a target whose drain is tracked joins the drain, it does not put an attempt of its own: no second
    /// witness request, and the drain's grace timer still reaps the target.
    @Test
    void tb6e_evictionOfATrackedDrainJoinsIt_andTheDrainTimerStillReaps() {
        var readmission = Promise.<Unit> promise();

        lifecycle.readmission.set(() -> readmission);
        join(TARGET);
        var drain = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);
        var evictionRan = afterHandlers(readmission);

        readmission.resolve(RETIRED.result());
        awaitResolved(evictionRan);

        assertThat(sink.requests()).as("the eviction ran and joined the drain: no witness request of its own").hasSize(1);
        assertThat(drain.isResolved()).isFalse();

        sink.accept(0);

        awaitRaised(EVICTED);
        await().atMost(Duration.ofSeconds(5)).until(() -> lifecycle.terminated().contains(TARGET));
        assertThat(registryWrites).as("the drain's single write").containsExactly(TARGET);
        assertThat(sink.requests()).hasSize(1);
    }

    /// TB6d: a drain that arrives during an eviction's witness raises the entry to DRAIN. When the witness accepts, the gate
    /// reads the kind of the entry now: the target is registered, the grace timer is armed, and its reap reads the drain's
    /// reason (an operator command is reaped as issued).
    @Test
    void tb6d_drainDuringEvictionWitness_raisesTheEntry_soAcceptArmsTheGraceReap() {
        openEvictionWitness();

        var drain = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        assertThat(sink.requests()).as("the drain joined the eviction's attempt").hasSize(1);
        assertThat(drain.isResolved()).isFalse();

        sink.accept(0);

        assertThat(settled(drain).isSuccess()).isTrue();
        assertThat(registry).containsExactly(TARGET);
        await().atMost(Duration.ofSeconds(5)).until(() -> lifecycle.terminated().contains(TARGET));
        awaitRaised(EVICTED);
        awaitLeftRegistry(TARGET);
        assertThat(registry).as("the grace reap cleared the command").isEmpty();
    }

    /// TB6c, the join case: a drain raised an eviction, then the id joins (a new incarnation, readmitted). The attempt is
    /// cancelled: no entry, an empty registry, no DRAIN afterwards, and the operator is told the drain was cancelled.
    @Test
    void tb6c_joinAfterTheRaise_cancelsTheDrain_andWarnsOfTheCancel() {
        openEvictionWitness();
        var drain = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        lifecycle.readmission.set(Promise::unitPromise);
        join(TARGET);
        awaitRaised(CANCELLED);
        sink.accept(0);

        assertThat(failureOf(settled(drain))).isInstanceOf(FenceFailure.AttemptCancelled.class);
        assertThat(registryWrites).as("no write for a cancelled attempt").isEmpty();
        assertThat(registry).isEmpty();
        awaitControlReap();
        assertThat(lifecycle.terminated()).as("no timer was armed: only the control is reaped").containsExactly(CONTROL);
        assertThat(registry).as("no DRAIN afterwards").isEmpty();
    }

    /// TB6c, the second case: a deactivation and an activation take the place of the join. The new activation holds no entry of
    /// the old attempt, so its accept writes nothing and no DRAIN exists in it.
    @Test
    void tb6c_deactivateAndActivateAfterTheRaise_leavesNoDrainInTheNewActivation() {
        openEvictionWitness();
        var drain = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        ctm.deactivate();
        ctm.activate();
        sink.accept(0);

        assertThat(failureOf(settled(drain))).isInstanceOf(FenceFailure.AttemptCancelled.class);
        assertThat(registryWrites).isEmpty();
        assertThat(registry).isEmpty();
        awaitControlReap();
        assertThat(lifecycle.terminated()).as("only the control is reaped").containsExactly(CONTROL);
        assertThat(registry).as("no DRAIN in the new activation").isEmpty();
    }

    /// TB6w: a join of the id during an eviction's witness cancels the eviction (no entry, registry cleared) and raises no
    /// drain-cancelled warning: only a cancelled DRAIN is reported. The eviction's own warning arriving is the control that
    /// the outcome was processed.
    @Test
    void tb6w_joinDuringEvictionWitness_cancelsWithoutTheDrainCancelledWarning() {
        openEvictionWitness();

        lifecycle.readmission.set(Promise::unitPromise);
        join(TARGET);
        sink.accept(0);

        awaitRaised(EVICTED);
        assertThat(registryWrites).as("the cancelled eviction wrote nothing").isEmpty();
        assertThat(registry).isEmpty();
        assertThat(raised).as("an eviction is not a drain: its cancel is not reported as one").doesNotContain(CANCELLED);
    }

    /// TB7: an eviction retries `ApplyTimeout` once, under the same attempt: the second request is answered, the outcome is
    /// the retry's, and the operator warning comes after it, not after the first answer.
    @Test
    void tb7_evictionRetriesApplyTimeoutOnce_andTheWarningFollowsTheOutcome() {
        openEvictionWitness();

        sink.refuse(0, APPLY_TIMEOUT);

        awaitRequests(2);
        awaitWarningBarrier();
        assertThat(raised).as("the outcome is not known yet: no eviction warning").doesNotContain(EVICTED);

        sink.accept(1);

        awaitRaised(EVICTED);
        assertThat(registryWrites).containsExactly(TARGET);
        assertThat(sink.requests()).as("no third request").hasSize(2);
    }

    /// TB7: the retry is once. A second `ApplyTimeout` fails the eviction, which is untracked, so the next drain opens a new
    /// attempt; the warning is still raised after the outcome (degrade forward).
    @Test
    void tb7_secondApplyTimeoutFailsTheEviction_untracksIt_andStillWarns() {
        openEvictionWitness();

        sink.refuse(0, APPLY_TIMEOUT);
        sink.refuse(1, APPLY_TIMEOUT);

        awaitRaised(EVICTED);
        assertThat(sink.requests()).as("one retry, never two").hasSize(2);
        ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);
        assertThat(sink.requests()).as("the failed attempt left no entry: a new drain opens its own").hasSize(3);
    }

    /// TB7: only `ApplyTimeout` is retried; any other refusal (here a paused quorum) is the outcome at once.
    @Test
    void tb7_otherRefusalIsNotRetried() {
        openEvictionWitness();

        sink.refuse(0, new ConsensusError.QuorumPaused(SELF));

        awaitRaised(EVICTED);
        assertThat(sink.requests()).as("not retried").hasSize(1);
        assertThat(registryWrites).isEmpty();
    }

    /// TB7r (R6-1): a retry is the same attempt. The eviction stalls, the target rejoins and is readmitted (the attempt is
    /// cancelled), and then the witness answers `ApplyTimeout`: the retry carries the cancelled attempt's gate, which answers
    /// `false`, so no DRAIN reaches the admitted incarnation.
    @Test
    void tb7r_retryAfterAReadmission_isTheSameAttempt_andWritesNothing() {
        openEvictionWitness();

        lifecycle.readmission.set(Promise::unitPromise);
        join(TARGET);
        sink.refuse(0, APPLY_TIMEOUT);

        awaitRequests(2);

        sink.accept(1);

        awaitRaised(EVICTED);
        assertThat(registryWrites).as("no DRAIN command reaches the admitted incarnation").isEmpty();
        assertThat(registry).isEmpty();
        assertThat(sink.requests()).as("the retry did not open a new attempt").hasSize(2);
    }

    /// TB10: a drain of the leader's own id is refused before any round: the witness is never asked and nothing is recorded.
    @Test
    void tb10_selfDrainIsRefusedBeforeAnyRound() {
        var outcome = settled(ctm.drainNode(SELF, DrainReason.OPERATOR_COMMAND));

        assertThat(failureOf(outcome)).isEqualTo(new ClusterTopologyManager.LeaderCannotDrainItself(SELF));
        assertThat(sink.requests()).as("no witness for a self-drain").isEmpty();
        assertThat(registryWrites).isEmpty();
    }

    /// TB10, the eviction side: the leader's own id joining is refused before any round as well; the eviction's warning
    /// arriving is the signal it ran.
    @Test
    void tb10_selfEvictionIsRefusedBeforeAnyRound() {
        lifecycle.readmission.set(() -> RETIRED.promise());

        join(SELF);

        awaitRaised(EVICTED);
        assertThat(sink.requests()).as("no witness for the leader's own id").isEmpty();
        assertThat(registryWrites).isEmpty();
    }

    /// TB5e (rule M): the decision of an eviction runs under the monitor. The probe is the manager's own-id read, made by the decision
    /// and sampled from its frame.
    @Test
    void tb5e_evictionDecisionRunsUnderTheManagersMonitor() {
        openEvictionWitness();

        assertThat(evictionDecisionHeldTheMonitor).as("decideEviction read the own id, and held the monitor doing it").containsExactly(true);
    }

    /// TB8: an eviction that lands after `deactivate` is refused by the inactive manager: it asks no witness and leaves no entry, so the
    /// next activation's drain opens its own attempt instead of joining a dead one.
    @Test
    void tb8_evictionLandingAfterDeactivate_asksNoWitness_andLeavesNoEntryForTheNextActivation() {
        var readmission = Promise.<Unit> promise();

        lifecycle.readmission.set(() -> readmission);
        join(TARGET);
        var evictionRan = afterHandlers(readmission);

        ctm.deactivate();
        readmission.resolve(RETIRED.result());
        awaitResolved(evictionRan);

        assertThat(sink.requests()).as("an inactive manager asks no witness").isEmpty();

        ctm.activate();
        var drain = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        assertThat(sink.requests()).as("no entry survived the deactivation: the drain opens its own attempt").hasSize(1);

        sink.accept(0);

        assertThat(settled(drain).isSuccess()).isTrue();
        assertThat(registryWrites).containsExactly(TARGET);
    }

    /// TB8, the drain side: a drain of an inactive manager is refused at once and records nothing.
    @Test
    void tb8_drainOfAnInactiveManager_isRefused_andRecordsNothing() {
        ctm.deactivate();

        var outcome = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        assertThat(outcome.isResolved()).as("refused at once, not left pending").isTrue();
        assertThat(outcome.await().isFailure()).isTrue();
        assertThat(sink.requests()).as("no witness for an inactive manager").isEmpty();

        ctm.activate();
        ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        assertThat(sink.requests()).as("the refusal left no entry").hasSize(1);
    }

    /// TB9: a failed attempt is untracked before its outcome is resolved, so a caller woken by the failure finds the map without it
    /// and opens a fresh attempt. The woken caller is a joiner's continuation, which runs inline inside the resolution (an event
    /// handler would race the resolver's thread).
    @Test
    void tb9_aCallerWokenByTheFailure_findsNoEntry_andOpensAFreshAttempt() {
        var first = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);
        var joined = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);
        var again = joined.fold(_ -> ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND));

        sink.refuse(0, WITNESS_REFUSED);

        assertThat(sink.requests()).as("the woken caller found no entry and asked its own witness").hasSize(2);

        sink.accept(1);

        assertThat(settled(again).isSuccess()).isTrue();
        assertThat(failureOf(settled(first))).isSameAs(WITNESS_REFUSED);
        assertThat(failureOf(settled(joined))).isSameAs(WITNESS_REFUSED);
        assertThat(registryWrites).containsExactly(TARGET);
    }

    /// TB11: a refused re-admission evicts the node, and the operator warning follows the eviction's outcome, never precedes it.
    @Test
    void tb11_refusedReadmission_evicts_andTheWarningFollowsTheOutcome() {
        lifecycle.readmission.set(() -> READMISSION_REFUSED.promise());

        join(TARGET);

        assertThat(sink.requests()).as("the refusal asked the witness for the eviction").hasSize(1);
        awaitWarningBarrier();
        assertThat(raised).as("the outcome is not known yet: no warning").doesNotContain(UNRECONCILED);

        sink.accept(0);

        awaitRaised(UNRECONCILED);
        assertThat(registryWrites).containsExactly(TARGET);
        assertThat(texts).as("the warning of a refused node").anyMatch(text -> text.contains("is refused and is being drained"));
    }

    /// TB11, the other failure: a re-admission that fails for any other reason raises the not-refused warning and evicts nothing.
    @Test
    void tb11_otherReadmissionFailure_warnsNotRefused_andEvictsNothing() {
        lifecycle.readmission.set(() -> LEDGER_UNREADABLE.promise());

        join(TARGET);

        awaitRaised(UNRECONCILED);
        assertThat(sink.requests()).as("a member whose reservation is unknown is not evicted").isEmpty();
        assertThat(texts).as("the warning of a node that is not refused").anyMatch(text -> text.contains("could not be reconciled with the ledger"));
        assertThat(texts).noneMatch(text -> text.contains("is refused and is being drained"));
    }

    /// TB12: a drain does not retry `ApplyTimeout` (only an eviction does): one request, and the timeout is the outcome.
    @Test
    void tb12_drainDoesNotRetryApplyTimeout() {
        var outcome = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        sink.refuse(0, APPLY_TIMEOUT);

        assertThat(sink.requests()).as("a drain never retries").hasSize(1);
        assertThat(outcome.isResolved()).isTrue();
        assertThat(failureOf(outcome.await())).isSameAs(APPLY_TIMEOUT);
    }

    /// The observer the manager reads: every call goes to the real observer, `self()` first samples the monitor.
    private final class SelfProbe implements InvocationHandler {
        private final TopologyObserver real;

        SelfProbe(TopologyObserver real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            sampleEvictionDecision(method);
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /// A witness the test answers: `request` records the call and returns a promise that stays in flight until `accept` (the
    /// gate runs once, in the continuation, and the answer follows it) or `refuse`.
    private static final class WitnessSink implements DrainSink {
        record Request(NodeId target, DrainGate gate, Promise<Unit> witness) {}

        private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
        private final Consumer<NodeId> registryAdd;

        WitnessSink(Consumer<NodeId> registryAdd) {
            this.registryAdd = registryAdd;
        }

        List<Request> requests() {
            return List.copyOf(requests);
        }

        @Override
        public Promise<Unit> request(NodeId target, DrainGate gate) {
            var witness = Promise.<Unit> promise();

            requests.add(new Request(target, gate, witness));
            return witness;
        }

        @Override
        public Option<Promise<Unit>> inFlight(NodeId target) {
            return Option.none();
        }

        void accept(int index) {
            var request = requests.get(index);
            var ran = request.gate().runIfOwned(() -> registryAdd.accept(request.target()));

            request.witness().resolve(ran
                                      ? Result.unitResult()
                                      : FenceFailure.AttemptCancelled.attemptCancelled().result());
        }

        void refuse(int index, Cause cause) {
            requests.get(index).witness().resolve(cause.result());
        }
    }

    private static final class EmptySnapshotSource implements GenerationSnapshotSource {
        @Override
        public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override
        public long observedRabiaTerm() {
            return 0L;
        }
    }

    private static final class RecordingLifecycle implements NodeLifecycleManager {
        private final CopyOnWriteArrayList<NodeId> terminated = new CopyOnWriteArrayList<>();
        final AtomicReference<Supplier<Promise<Unit>>> readmission = new AtomicReference<>(Promise::unitPromise);

        List<NodeId> terminated() {
            return List.copyOf(terminated);
        }

        @Override
        public Promise<Unit> readmitExternal(NodeId nodeId) {
            return readmission.get().get();
        }

        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            terminated.add(nodeId);
            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<List<InstanceInfo>> instancesForNode(NodeId nodeId) {
            return Promise.success(List.of());
        }

        @Override
        public boolean isCloudManaged() {
            return true;
        }
    }
}
