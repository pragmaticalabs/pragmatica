// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.DeploymentAtomicity;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Activate;
import org.pragmatica.aether.deployment.cluster.fsm.ClusterDeploymentEvents.Deactivate;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
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

        Set<NodeId> members() {
            if (armed.compareAndSet(true, false)) {
                parked.countDown();
                await(release);
            }

            return Set.of();
        }
    }

    private final Park park = new Park();
    private Fsm<ClusterDeploymentState, ClusterFsmEvent> fsm;

    private void build() {
        var router = MessageRouter.mutable();
        var kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
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
                                                       Set::of,
                                                       Set::of,
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
