// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Deactivate;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.statemachine.Fsm;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1191 at the cluster-deployment site: `Active.onEntry` arms its reconcile timer (periodic) and its 2 s deferred recheck
/// through the entry's lifetime, so an `onEntry` that `Active.onExit` has overtaken registers nothing that survives.
///
/// Before the conversion `onExit` cancelled a holder (`reconcileTimer`) that the overtaken `onEntry` had not filled yet, and the
/// `onEntry` then stored a periodic future that nothing cancelled: a leaked timer whose body is a no-op (`deactivated` is
/// read first), invisible to any behavioural assertion. The observable here is the entry's lifetime, so the race test pairs with
/// a control that an un-overtaken entry DOES register its two timers there.
///
/// The race is driven, not awaited: the first winner is parked inside `onEntry`, in the first read of the counted-members
/// supplier (the first statement of the hook, before any arming), while `Deactivate` wins and completes on the test thread.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ClusterDeploymentActiveLifetimeTest {
    private static final NodeId SELF = new NodeId("node-1");

    /// Parks the first caller of the counted-members supplier; every later call returns the empty set at once.
    private static final class Park {
        final AtomicBoolean armed = new AtomicBoolean(false);
        final CountDownLatch parked = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Set<NodeId>> counted = new AtomicReference<>(Set.of());

        Set<NodeId> members() {
            if (armed.compareAndSet(true, false)) {
                parked.countDown();
                await(release);
            }

            return counted.get();
        }
    }

    private final Park park = new Park();
    private final AtomicReference<Set<NodeId>> ready = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<NodeId>> draining = new AtomicReference<>(Set.of());
    private InMemoryKvStore kvStore;
    private Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm;

    private void build() {
        var router = MessageRouter.mutable();
        kvStore = new InMemoryKvStore(router);
        ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
        var ctxHolder = new AtomicReference<ClusterDeploymentContext>();
        Function<Fsm<ClusterDeploymentState, ClusterFsmEvent>, ClusterDeploymentState> factory = f -> {
            var context = new ClusterDeploymentContext(f,
                                                       SELF,
                                                       cluster,
                                                       kvStore,
                                                       router,
                                                       stubTopologyManager(SELF),
                                                       stubSchemaOrchestrator(),
                                                       park::members,
                                                       ready::get,
                                                       draining::get,
                                                       Set.of(SELF),
                                                       DeploymentAtomicity.ALL_OR_NOTHING,
                                                       3,
                                                       timeSpan(300).seconds());
            ctxHolder.set(context);
            return context.dormant();
        };
        fsm = Fsm.fsm("cluster-deployment", SELF.id(), factory);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).as("latch released in time").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /// Control: an entry that is not overtaken registers both timers through its lifetime, and leaving the state ends them.
    @Test
    void activeEntry_registersItsTwoTimersOnTheEntryLifetime_andLeavingTheStateCancelsThem() {
        build();

        fsm.dispatch(new Activate());
        var active = fsm.entered();

        assertThat(active.state()).isInstanceOf(ClusterDeploymentState.Active.class);
        assertThat(active.lifetime().size())
                .as("the reconcile timer and the deferred topology recheck are armed through the entry's lifetime")
                .isEqualTo(2);

        fsm.dispatch(new Deactivate());

        assertThat(active.lifetime().isEnded()).as("leaving Active ends its lifetime").isTrue();
        assertThat(active.lifetime().size()).as("and cancels both timers").isZero();
    }

    /// The #1191 interleaving at this site: `Active.onEntry` parked before it arms; `Deactivate` wins and runs `Active.onExit`;
    /// only then does `onEntry` arm.
    @Test
    void activeEntryOvertakenByDeactivate_armsNothingThatSurvivesTheExit() throws Exception {
        build();
        park.armed.set(true);
        var first = Thread.ofVirtual().start(() -> fsm.dispatch(new Activate()));

        await(park.parked);
        var active = fsm.entered();

        assertThat(active.state()).as("precondition: the parked winner's CAS has landed").isInstanceOf(ClusterDeploymentState.Active.class);
        assertThat(active.lifetime().size()).as("precondition: its onEntry has not armed yet").isZero();

        fsm.dispatch(new Deactivate());
        assertThat(fsm.current()).as("precondition: the second winner has completed").isInstanceOf(ClusterDeploymentState.Dormant.class);

        park.release.countDown();
        first.join();

        assertThat(active.lifetime().isEnded()).isTrue();
        assertThat(active.lifetime().size())
                .as("#1191: the reconcile timer and the deferred recheck armed after the exit are refused")
                .isZero();
    }

    /// The event- and callback-path schedules of `Active` (rows 3-14 of facts-i1191f.md). Each one is reached by a handler that
    /// runs outside `onEntry`, so none can use the hook's lifetime parameter: it arms through the entry that is current NOW.
    enum Site {
        RELEASE_JOIN,
        NODE_ADDED_RETRY,
        SLICE_NODE_REMOVAL_RECONCILE,
        APP_BLUEPRINT_REMOVAL_RECONCILE,
        DRAIN_CHECK_REPLACEMENT,
        DRAIN_EVICT_NEXT,
        DRAIN_REDEPLOY,
        TRANSIENT_FAILURE_RETRY,
        ACTIVATE_RETRY,
        BATCH_FAILURE_RECONCILE,
        SLICE_WRITE_FAILURE_RECONCILE,
        ROLLBACK_RESTORING_CLEAR
    }

    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_D = new NodeId("node-drain");
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final SliceNodeKey DRAINING_KEY = SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_D);

    private ClusterDeploymentState.Active enter() {
        fsm.dispatch(new Activate());
        return (ClusterDeploymentState.Active) fsm.entered().state();
    }

    /// A schedule armed by an event or callback handler of an un-overtaken Active registers on that entry's lifetime. Red when the
    /// handler still calls the raw scheduler: the lifetime then counts nothing.
    @ParameterizedTest
    @EnumSource(Site.class)
    void eventPathSchedule_onTheCurrentActive_armsThroughTheEntryLifetime(Site site) {
        build();
        var active = enter();
        var lifetime = fsm.entered().lifetime();
        var before = lifetime.size();

        trigger(site, active);

        assertThat(lifetime.size()).as(site + " arms through the entry's lifetime").isGreaterThan(before);
    }

    /// The same handler on an Active the FSM has left: it must arm on NO lifetime. First the Dormant entry that replaced it, then
    /// the fresh Active of the next term (a lifetime read apart from the state would land the stale arm there).
    @ParameterizedTest
    @EnumSource(Site.class)
    void eventPathSchedule_onAnExitedActive_armsOnNeitherTheDormantNorTheNextTermLifetime(Site site) {
        build();
        var stale = enter();
        var exited = fsm.entered().lifetime();

        fsm.dispatch(new Deactivate());
        var dormant = fsm.entered().lifetime();
        trigger(site, stale);

        assertThat(dormant.size()).as(site + ": the Dormant entry's lifetime receives nothing").isZero();

        fsm.dispatch(new Activate());
        var fresh = fsm.entered().lifetime();
        var baseline = fresh.size();
        trigger(site, stale);

        assertThat(fresh.size()).as(site + ": the next term's lifetime receives nothing from the stale Active").isEqualTo(baseline);
        assertThat(exited.size()).as(site + ": the ended lifetime holds nothing").isZero();
    }

    private void trigger(Site site, ClusterDeploymentState.Active active) {
        switch (site) {
            case RELEASE_JOIN -> armReleaseJoin(active);
            case NODE_ADDED_RETRY -> armNodeAddedRetry(active);
            case SLICE_NODE_REMOVAL_RECONCILE -> active.handleSliceNodeRemovalForTest(SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A));
            case APP_BLUEPRINT_REMOVAL_RECONCILE -> active.handleAppBlueprintRemovalForTest(AppBlueprintKey.appBlueprintKey(blueprintId(V1)),
                                                                                     false);
            case DRAIN_CHECK_REPLACEMENT -> armDrainCheck(active);
            case DRAIN_EVICT_NEXT -> armDrainEvictNext(active);
            case DRAIN_REDEPLOY -> armDrainRedeploy(active);
            case TRANSIENT_FAILURE_RETRY -> active.handleTransientFailureForTest(DRAINING_KEY, "boom");
            case ACTIVATE_RETRY -> active.onActivateWriteFailureForTest(DRAINING_KEY, Causes.cause("write failed"), 0);
            case BATCH_FAILURE_RECONCILE -> active.handleBatchFailureForTest(Causes.cause("batch failed"), List.of());
            case SLICE_WRITE_FAILURE_RECONCILE -> active.handleSliceNodeWriteFailureForTest(DRAINING_KEY, Causes.cause("dht failed"));
            case ROLLBACK_RESTORING_CLEAR -> armRollbackClear(active);
        }
    }

    private static void armReleaseJoin(ClusterDeploymentState.Active active) {
        var pending = Promise.<Unit>promise();

        active.ctx().removalsInFlight().put(NODE_A, pending);
        active.awaitRemovalThenRejoinForTest(new WorkerJoinDecision(NODE_A, "worker", HlcTimestamp.ZERO), pending);
    }

    private void armNodeAddedRetry(ClusterDeploymentState.Active active) {
        ready.set(Set.of());
        active.handleNodeAddedForTest(SELF);
    }

    /// A draining node holding a slice, the other two nodes ready and counted.
    private void drainFixture(ClusterDeploymentState.Active active) {
        park.counted.set(Set.of(SELF, NODE_A, NODE_D));
        ready.set(Set.of(SELF, NODE_A, NODE_D));
        draining.set(Set.of(NODE_D));
        active.sliceStates().put(DRAINING_KEY, SliceState.ACTIVE);
    }

    private void armDrainRedeploy(ClusterDeploymentState.Active active) {
        drainFixture(active);
        active.checkReplacementAndUnloadForTest(DRAINING_KEY);
    }

    private void armDrainCheck(ClusterDeploymentState.Active active) {
        drainFixture(active);
        active.deployReplacementForDrainForTest(DRAINING_KEY);
    }

    private void armDrainEvictNext(ClusterDeploymentState.Active active) {
        drainFixture(active);
        active.sliceStates().put(SliceNodeKey.sliceNodeKey(ARTIFACT, NODE_A), SliceState.ACTIVE);
        active.checkReplacementAndUnloadForTest(DRAINING_KEY);
    }

    private void armRollbackClear(ClusterDeploymentState.Active active) {
        var prior = expanded(V1);
        var failing = expanded(V2);

        kvStore.put(AppBlueprintKey.appBlueprintKey(failing.id()), AppBlueprintValue.appBlueprintValue(failing));
        active.restorePreviousBlueprintForTest(ClusterDeploymentState.Active.InFlightBlueprint.inFlightBlueprint(failing.id(),
                                                                                                                 failing,
                                                                                                                 Option.some(prior)),
                                               prior,
                                               "boom");
    }

    private static BlueprintId blueprintId(Version version) {
        return BlueprintId.blueprintId("com.example:app:" + version.bareVersion()).unwrap();
    }

    private static ExpandedBlueprint expanded(Version version) {
        var slice = ResolvedSlice.resolvedSlice(Artifact.artifact("com.example:slice-a:" + version.bareVersion()).unwrap(), 3, false)
                                 .unwrap();

        return ExpandedBlueprint.expandedBlueprint(blueprintId(version), List.of(slice));
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }
    }

    private static SchemaOrchestratorService stubSchemaOrchestrator() {
        return new SchemaOrchestratorService() {
            @Override public Promise<Unit> migrateIfNeeded(String datasourceName) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> undoTo(String datasourceName, int targetVersion) {
                return Promise.success(Unit.unit());
            }

            @Override public Promise<Unit> baseline(String datasourceName, int version) {
                return Promise.success(Unit.unit());
            }
        };
    }

    private static ClusterNode<KVCommand<AetherKey>> stubClusterNode(NodeId self) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return self;
            }

            @Override public TopologyManager topologyManager() {
                return stubTopologyManager(self);
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return Promise.success(Collections.emptyList());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 1;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
