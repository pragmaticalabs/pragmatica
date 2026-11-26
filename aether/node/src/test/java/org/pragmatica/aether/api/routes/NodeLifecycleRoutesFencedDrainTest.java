// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.DrainGate;
import org.pragmatica.aether.deployment.cluster.FenceFailure.ActivationChanged;
import org.pragmatica.aether.deployment.cluster.FenceFailure.AttemptCancelled;
import org.pragmatica.aether.deployment.cluster.FenceFailure.CalledUnderOwnerMonitor;
import org.pragmatica.aether.deployment.cluster.FenceFailure.LeadershipNotWitnessed;
import org.pragmatica.aether.deployment.cluster.FenceFailure.NotLeader;
import org.pragmatica.aether.deployment.cluster.FenceFailure.WitnessExpired;
import org.pragmatica.aether.deployment.cluster.FencePort;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.NodeReplacementWiring;
import org.pragmatica.aether.node.NodeReplacementWiring.DrainOutcome;
import org.pragmatica.aether.node.assembly.ScriptedFencedDrain;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceRowSource;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.RefusalKind;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3754 lane (b2), half 2: the operator and replacement drains driven through the real routes, over the real `FencedDrainSink` and a
/// witness port the test scripts (design r6, rows TB2, TB8 and TB8r). The port holds every witness until the test accepts it or
/// refuses it with a cause, so "in flight" is a state the test can stand in.
///
/// TB2: the #1720 floor holds with the first witness in flight. TB8: every row of §3.4's cause-to-status table, through the route,
/// with the default row (a failed submit); nothing is reported initiated, and the audit says "not queued". TB8r: a refused
/// replacement drain is `DrainOutcome.pending(cause)` and never `admitted`.
class NodeLifecycleRoutesFencedDrainTest {
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final String SLICE_A = "org.example:a:1.0.0";
    private static final String AUDIT_LOGGER = "org.pragmatica.aether.audit";
    private static final String AUDIT_MARK = "NODE_LIFECYCLE_TRANSITION";
    private static final int INTENDED_SIZE = 5;
    private static final long APPLY_TIMEOUT_MILLIS = 30_000L;
    private static final TimeSpan SETTLE = timeSpan(5).seconds();

    private final List<String> routedEvents = new CopyOnWriteArrayList<>();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final Set<NodeId> registry = ConcurrentHashMap.newKeySet();
    private final ScriptedPort scripted = new ScriptedPort();

    private KVStore<AetherKey, AetherValue> kvStore;
    private MembershipFsm fsm;
    private ScriptedFencedDrain.Assembled assembled;
    private NodeLifecycleRoutes routes;
    private LogCapture capture;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.DelegateRouter.delegate();

        router.quiesce();
        kvStore = new KVStore<>(router, noopSerializer(), null);
        fsm = fsmAllCore(presentMembers());
        assembled = ScriptedFencedDrain.assemble(scripted, registry::add, () -> Set.copyOf(registry), new AtomicReference<>(fsm));
        routes = routesOver(assembled.port());
        capture = LogCapture.capture(AUDIT_LOGGER, Level.INFO, false);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    /// §3.4's cause-to-status table: one row per cause, the default row last.
    private static Stream<Arguments> table() {
        return Stream.of(Arguments.of("NotLeader", NotLeader.notLeader(), HttpStatus.SERVICE_UNAVAILABLE),
                         Arguments.of("LeadershipNotWitnessed(LEADER)",
                                      LeadershipNotWitnessed.leadershipNotWitnessed(RefusalKind.LEADER),
                                      HttpStatus.SERVICE_UNAVAILABLE),
                         Arguments.of("LeadershipNotWitnessed(WITNESS)",
                                      LeadershipNotWitnessed.leadershipNotWitnessed(RefusalKind.WITNESS),
                                      HttpStatus.SERVICE_UNAVAILABLE),
                         Arguments.of("ActivationChanged", ActivationChanged.activationChanged(), HttpStatus.SERVICE_UNAVAILABLE),
                         Arguments.of("WitnessExpired", WitnessExpired.witnessExpired(), HttpStatus.GATEWAY_TIMEOUT),
                         Arguments.of("ApplyTimeout",
                                      new ConsensusError.ApplyTimeout(APPLY_TIMEOUT_MILLIS),
                                      HttpStatus.GATEWAY_TIMEOUT),
                         Arguments.of("AttemptCancelled", AttemptCancelled.attemptCancelled(), HttpStatus.CONFLICT),
                         Arguments.of("CalledUnderOwnerMonitor",
                                      CalledUnderOwnerMonitor.calledUnderOwnerMonitor(node(1)),
                                      HttpStatus.INTERNAL_SERVER_ERROR),
                         Arguments.of("any other cause (default row)",
                                      Causes.cause("submit failed"),
                                      HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ---- TB2: the #1720 floor with the first witness in flight ----

    /// Slice A, floor 2, on node-1..3. The first drain's witness is in flight and nothing is registered; draining node-2 would leave A on
    /// node-3 only, so it is refused 409 by the floor reading the in-flight target as pending. A recording of the intent only in the
    /// accepted continuation would let both pass.
    @Test
    void floor_holdsWithTheFirstWitnessInFlight_aSecondDrainIsRefused() {
        host(slice("a", 3, 2), node(1), node(2), node(3));
        var first = routes.drainNodeForTest(node(1).id());

        assertThat(first.isResolved()).as("the first drain waits for its witness").isFalse();
        assertThat(assembled.port().pending()).as("the in-flight target is pending before any registry write").containsExactly(node(1));
        assertThat(registry).isEmpty();

        assertFloorRefusal(routes.drainNodeForTest(node(2).id()));
        assertThat(scripted.submitted()).as("the refused request submitted no witness").isEqualTo(1);
        assertThat(registry).isEmpty();

        scripted.accept(0);
        assertThat(first.await(SETTLE).isSuccess()).isTrue();
        assertThat(registry).containsExactly(node(1));
        assertFloorRefusal(routes.drainNodeForTest(node(2).id()));
        assertThat(scripted.submitted()).isEqualTo(1);
    }

    @Test
    void floor_holdsWithTheFirstWitnessInFlight_aShutdownIsRefusedToo() {
        host(slice("a", 3, 2), node(1), node(2), node(3));
        var first = routes.drainNodeForTest(node(1).id());

        assertThat(first.isResolved()).isFalse();
        assertFloorRefusal(routes.shutdownNodeForTest(node(2).id()));
        assertThat(scripted.submitted()).isEqualTo(1);
        assertThat(registry).isEmpty();
    }

    /// The control: at the floor with nothing in flight, the drain is admitted and submits its witness.
    @Test
    void floor_atTheFloorWithNothingInFlight_aDrainIsAdmitted() {
        host(slice("a", 3, 2), node(1), node(2), node(3));
        var admitted = routes.drainNodeForTest(node(2).id());

        assertThat(scripted.submitted()).isEqualTo(1);
        assertThat(admitted.isResolved()).isFalse();
    }

    // ---- TB8: every status-table row, through the routes ----

    @ParameterizedTest(name = "drain route, witness refused with {0} -> {2}")
    @MethodSource("table")
    void drainRoute_witnessRefused_answersTheTableStatus_andAuditsNotQueued(String label, Cause cause, HttpStatus expected) {
        var drain = routes.drainNodeForTest(node(1).id());

        assertNothingReportedWhileInFlight(drain);
        scripted.refuse(0, cause);

        assertRefusal(drain, cause, expected);
        assertAuditedNotQueued("DRAINING", cause);
        assertNothingQueued();
    }

    @ParameterizedTest(name = "shutdown route, witness refused with {0} -> {2}")
    @MethodSource("table")
    void shutdownRoute_witnessRefused_answersTheTableStatus_andAuditsNotQueued(String label, Cause cause, HttpStatus expected) {
        var shutdown = routes.shutdownNodeForTest(node(1).id());

        assertNothingReportedWhileInFlight(shutdown);
        scripted.refuse(0, cause);

        assertRefusal(shutdown, cause, expected);
        assertAuditedNotQueued("STOPPED", cause);
        assertNothingQueued();
    }

    /// The default row reached by a submit that fails at once (before any witness is held), not only by a refused witness.
    @Test
    void drainRoute_submitFailsAtOnce_isTheDefaultRow_503_andAuditsNotQueued() {
        var cause = Causes.cause("submit failed");

        scripted.failSubmitWith(cause);
        var drain = routes.drainNodeForTest(node(1).id());

        assertRefusal(drain, cause, HttpStatus.SERVICE_UNAVAILABLE);
        assertAuditedNotQueued("DRAINING", cause);
        assertNothingQueued();
    }

    /// The control for the refusals above: an accepted witness is reported initiated only after the commit, audited once, and the
    /// route event is raised once.
    @Test
    void drainRoute_witnessAccepted_isInitiatedOnlyAfterTheCommit() {
        var drain = routes.drainNodeForTest(node(1).id());

        assertNothingReportedWhileInFlight(drain);
        scripted.accept(0);

        var result = drain.await(SETTLE);

        assertThat(result.isSuccess()).isTrue();
        await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(audit()).hasSize(1);
            assertThat(routedEvents).hasSize(1);
        });
        assertThat(audit().getFirst()).startsWith(AUDIT_MARK + " nodeId=node-1 targetState=DRAINING success=true");
        assertThat(registry).containsExactly(node(1));
        assertThat(fsm.memberStates().get(node(1))).isEqualTo("Departing");
    }

    // ---- TB8r: the replacement drain ----

    @ParameterizedTest(name = "replacement drain, witness refused with {0}")
    @MethodSource("table")
    void replacementDrain_witnessRefused_isPendingWithTheCause_andReadmittedOnTheNextTick(String label, Cause cause, HttpStatus unused) {
        var outcome = NodeReplacementWiring.drainOutcomeOf(routes.admitReplacementDrain(node(1)));

        assertThat(outcome.isResolved()).isFalse();
        assertThat(scripted.submitted()).isEqualTo(1);
        scripted.refuse(0, cause);

        var answered = outcome.await(SETTLE).unwrap();

        assertThat(answered).isEqualTo(DrainOutcome.pending(cause.message()));
        assertThat(answered.accepted()).as("no drainRequested follows a refused witness").isFalse();
        assertThat(registry).isEmpty();
        assertThat(fsm.memberStates().get(node(1))).isNotEqualTo("Departing");

        awaitNoneInFlight(node(1));
        var nextTick = NodeReplacementWiring.drainOutcomeOf(routes.admitReplacementDrain(node(1)));

        assertThat(scripted.submitted()).as("the next tick runs the admission again").isEqualTo(2);
        scripted.accept(1);
        assertThat(nextTick.await(SETTLE).unwrap()).isEqualTo(DrainOutcome.admitted());
        assertThat(registry).containsExactly(node(1));
    }

    /// Another caller (the operator route) has the witness in flight; the replacement waits for that witness instead of submitting its
    /// own, and its refusal is the replacement's refusal.
    @Test
    void replacementDrain_anotherCallersWitnessInFlight_thenRefused_isPendingWithTheCause() {
        var cause = new ConsensusError.ApplyTimeout(APPLY_TIMEOUT_MILLIS);
        var operator = routes.drainNodeForTest(node(1).id());
        var outcome = NodeReplacementWiring.drainOutcomeOf(routes.admitReplacementDrain(node(1)));

        assertThat(scripted.submitted()).as("the replacement waits, it submits no witness").isEqualTo(1);
        assertThat(outcome.isResolved()).isFalse();
        scripted.refuse(0, cause);

        var answered = outcome.await(SETTLE).unwrap();

        assertThat(answered).isEqualTo(DrainOutcome.pending(cause.message()));
        assertThat(answered.accepted()).isFalse();
        assertThat(failureOf(operator)).isInstanceOf(HttpError.class);
        assertThat(registry).isEmpty();
    }

    /// Another caller's witness is accepted, but its gate answers `false` and writes nothing: the target is not registered, so the
    /// replacement that waited for it gets `AttemptCancelled` as a pending refusal and is not told "admitted".
    @Test
    void replacementDrain_anotherCallersWitnessAccepted_gateAnswersFalse_isPendingNotAdmitted() {
        var other = assembled.sink().request(node(1), gateThatWritesNothing());
        var outcome = NodeReplacementWiring.drainOutcomeOf(routes.admitReplacementDrain(node(1)));

        assertThat(scripted.submitted()).isEqualTo(1);
        scripted.accept(0);

        var answered = outcome.await(SETTLE).unwrap();

        assertThat(answered).isEqualTo(DrainOutcome.pending(AttemptCancelled.attemptCancelled().message()));
        assertThat(answered.accepted()).isFalse();
        assertThat(failureOf(other)).isInstanceOf(AttemptCancelled.class);
        assertThat(registry).isEmpty();
        assertThat(fsm.memberStates().get(node(1))).isNotEqualTo("Departing");
    }

    /// `pending()` named the target and `inFlight` is empty (the witness ended between the reads): with the target neither registered
    /// nor reported draining, the full admission runs and submits a witness.
    @Test
    void replacementDrain_inFlightEmptyAfterPendingNamedTheTarget_runsTheFullAdmission() {
        var flipping = routesOver(portNamingTheTargetOnce(Set.of(node(1)), Set.of()));
        var outcome = NodeReplacementWiring.drainOutcomeOf(flipping.admitReplacementDrain(node(1)));

        assertThat(scripted.submitted()).as("the admission ran to its request").isEqualTo(1);
        scripted.accept(0);
        assertThat(outcome.await(SETTLE).unwrap()).isEqualTo(DrainOutcome.admitted());
        assertThat(registry).containsExactly(node(1));
    }

    /// The same read order, and the full admission refuses on the floor: it is `blocked`, and no witness was submitted.
    @Test
    void replacementDrain_inFlightEmptyAfterPendingNamedTheTarget_theFloorStillApplies() {
        host(slice("a", 2, 2), node(1), node(2));
        var flipping = routesOver(portNamingTheTargetOnce(Set.of(node(1)), Set.of()));

        var answered = NodeReplacementWiring.drainOutcomeOf(flipping.admitReplacementDrain(node(1))).await(SETTLE).unwrap();

        assertThat(answered.accepted()).isFalse();
        assertThat(answered.blockedBy()).contains(SLICE_A);
        assertThat(scripted.submitted()).isZero();
    }

    /// The same read order, and the full admission refuses on the disruption budget: pending names two other cores, so draining
    /// node-1 would leave 2 of 5 cores (minimum 3).
    @Test
    void replacementDrain_inFlightEmptyAfterPendingNamedTheTarget_theBudgetStillApplies() {
        var flipping = routesOver(portNamingTheTargetOnce(Set.of(node(1)), Set.of(node(2), node(3))));

        var answered = NodeReplacementWiring.drainOutcomeOf(flipping.admitReplacementDrain(node(1))).await(SETTLE).unwrap();

        assertThat(answered.accepted()).isFalse();
        assertThat(answered.refusedFor()).contains("Disruption budget exceeded");
        assertThat(scripted.submitted()).isZero();
    }

    // ---- assertions ----

    private void assertNothingReportedWhileInFlight(Promise<NodeLifecycleRoutes.TransitionResult> promise) {
        assertThat(scripted.submitted()).isEqualTo(1);
        assertThat(promise.isResolved()).as("nothing is reported before the commit answers").isFalse();
        assertThat(audit()).as("nothing is audited before the commit answers").isEmpty();
        assertThat(routedEvents).as("no lifecycle event before the commit answers").isEmpty();
    }

    private void assertRefusal(Promise<NodeLifecycleRoutes.TransitionResult> promise, Cause cause, HttpStatus expected) {
        var refusal = failureOf(promise);

        assertThat(refusal).as("an explicit HttpError, never the raw cause").isInstanceOf(HttpError.class);
        assertThat(((HttpStatusAware) refusal).httpStatus()).isEqualTo(expected);
        assertThat(refusal.message()).contains(cause.message());
    }

    private void assertAuditedNotQueued(String targetState, Cause cause) {
        await().atMost(java.time.Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(audit()).containsExactly(AUDIT_MARK + " nodeId=node-1 targetState=" + targetState
                                                                        + " success=false message=drain not queued: "
                                                                        + cause.message()));
    }

    private void assertNothingQueued() {
        assertThat(routedEvents).as("a refused drain raises no lifecycle event").isEmpty();
        assertThat(registry).as("a refused drain writes no registry entry").isEmpty();
        assertThat(fsm.memberStates().get(node(1))).as("a refused drain makes no FSM call").isNotEqualTo("Departing");
        awaitNoneInFlight(node(1));
        assertThat(assembled.port().pending()).as("the refused request leaves nothing pending").isEmpty();
    }

    private void assertFloorRefusal(Promise<NodeLifecycleRoutes.TransitionResult> promise) {
        var refusal = failureOf(promise);

        assertThat(refusal).isInstanceOf(NodeLifecycleRoutes.SliceFloorBreached.class);
        assertThat(((HttpStatusAware) refusal).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refusal.message()).contains(SLICE_A);
    }

    private void awaitNoneInFlight(NodeId target) {
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> assembled.port().inFlight(target).isEmpty());
    }

    private static Cause failureOf(Promise<?> promise) {
        var holder = new AtomicReference<Cause>();

        promise.await(SETTLE).onFailure(holder::set);
        assertThat(holder.get()).as("expected a refusal").isNotNull();

        return holder.get();
    }

    private List<String> audit() {
        return capture.messages().stream().filter(message -> message.startsWith(AUDIT_MARK)).toList();
    }

    // ---- fixtures ----

    private static DrainGate gateThatWritesNothing() {
        return new DrainGate() {
            @Override
            public boolean runIfOwned(Runnable write) {
                return false;
            }

            @Override
            public boolean ownerMonitorHeld() {
                return false;
            }
        };
    }

    /// The assembled port, with `pending()` answering `first` on its first read and `later` on every read after: the witness that
    /// ends between the two reads the replacement makes.
    private NodeLifecycleRoutes.DrainPort portNamingTheTargetOnce(Set<NodeId> first, Set<NodeId> later) {
        var reads = new AtomicInteger();
        Supplier<Set<NodeId>> pending = () -> reads.getAndIncrement() == 0
                                              ? first
                                              : later;

        return NodeLifecycleRoutes.DrainPort.registry(assembled.port()::request, pending, assembled.port()::inFlight);
    }

    private NodeLifecycleRoutes routesOver(NodeLifecycleRoutes.DrainPort port) {
        return NodeLifecycleRoutes.withDrainPort(this::nodeProxy,
                                                 port,
                                                 NodeLifecycleRoutes.SliceFloor.sliceFloor(SliceOwnershipQuery.minAvailableDrainViolations(kvStore,
                                                                                                                                         SliceRowSource.legacy()),
                                                                                           OperatorWarningSink.handingOffTo(warnings::add)));
    }

    /// A witness port that holds every witness until the test accepts it (the effect runs) or refuses it with a cause.
    private static final class ScriptedPort implements FencePort {
        private record Witness(Runnable accept, Consumer<Cause> refuse) {}

        private final List<Witness> witnesses = new CopyOnWriteArrayList<>();
        private volatile Option<Cause> submitFailure = Option.none();

        @Override
        public <T> Promise<T> fenced(List<KVCommand.ReadWitness<AetherKey>> guards, Supplier<Promise<T>> effect) {
            return submitFailure.fold(() -> hold(effect), cause -> cause.<T> promise());
        }

        private <T> Promise<T> hold(Supplier<Promise<T>> effect) {
            var outcome = Promise.<T> promise();

            witnesses.add(new Witness(() -> effect.get().onResult(outcome::resolve), outcome::fail));

            return outcome;
        }

        int submitted() {
            return witnesses.size();
        }

        void accept(int index) {
            witnesses.get(index).accept().run();
        }

        void refuse(int index, Cause cause) {
            witnesses.get(index).refuse().accept(cause);
        }

        void failSubmitWith(Cause cause) {
            submitFailure = Option.some(cause);
        }
    }

    private static NodeId node(int index) {
        return new NodeId("node-" + index);
    }

    private static Set<NodeId> presentMembers() {
        return IntStream.rangeClosed(1, INTENDED_SIZE)
                        .mapToObj(NodeLifecycleRoutesFencedDrainTest::node)
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static org.pragmatica.serialization.Serializer noopSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    private Artifact slice(String name, int instances, int minAvailable) {
        var artifact = ArtifactBase.artifactBase("org.example:" + name).unwrap().withVersion(V1);

        apply(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(artifact.base()),
                                  SliceTargetValue.sliceTargetValue(V1, instances, minAvailable, Option.none())));

        return artifact;
    }

    private void host(Artifact artifact, NodeId... nodes) {
        for (var node : nodes) {
            apply(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(node, artifact),
                                      NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE)));
        }
    }

    private void apply(KVCommand<AetherKey> command) {
        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    /// Every present member reports READY, so the readiness gate passes and a refusal is the witness's or the guards'.
    private ClusterSyncCollector metricsCollector() {
        var states = presentMembers().stream().collect(Collectors.toMap(peer -> peer, _ -> NodeReportedState.READY));

        return (ClusterSyncCollector) Proxy.newProxyInstance(ClusterSyncCollector.class.getClassLoader(),
                                                             new Class[]{ClusterSyncCollector.class},
                                                             (_, method, _) -> switch (method.getName()) {
                                                                 case "reportedStates" -> Map.copyOf(states);
                                                                 case "hasAuthoritativeReadiness" -> Boolean.TRUE;
                                                                 default -> throw new UnsupportedOperationException("Not implemented: " + method.getName());
                                                             });
    }

    private ManageableNode nodeProxy() {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
                                                           case "metricsCollector" -> metricsCollector();
                                                           case "coreNodeIds" -> presentMembers();
                                                           case "membershipFsm" -> fsm;
                                                           case "kvStore" -> kvStore;
                                                           case "leader" -> Option.none();
                                                           case "self" -> new NodeId("handler-self");
                                                           case "route" -> recordRoute(args);
                                                           default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
                                                       });
    }

    private Object recordRoute(Object[] args) {
        routedEvents.add(String.valueOf(args[0]));

        return null;
    }

    private static MembershipFsm fsmAllCore(Set<NodeId> ids) {
        var fsm = MembershipFsm.membershipFsm();

        ids.forEach(id -> promoteCore(fsm, id));

        return fsm;
    }

    private static void promoteCore(MembershipFsm fsm, NodeId id) {
        fsm.onSwimHealthy(id, 1L);
        fsm.onMemberDescriptor(NodeInfo.nodeInfo(id,
                                                 NodeAddress.nodeAddress("host-x", 6000).unwrap(),
                                                 Map.of(NodeInfo.LABEL_ROLE, "core")));
    }
}
