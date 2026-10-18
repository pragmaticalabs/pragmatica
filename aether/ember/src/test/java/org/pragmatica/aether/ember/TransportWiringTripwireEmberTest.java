// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TransportObservation.SelfShutdown;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Wiring-level tripwire pairs of cluster T (rc4 `7c786db0d3`), on real nodes: they depend on `AetherNode` wiring that no
/// transport-level test reaches. One cluster is started on first use and shared by the four tests, which run in a fixed
/// order (the read-only D-06 pair first, the node-stopping A-01 pair after).
///
/// D-06: `AetherNode` registers the QUIC event-loop groups with `EventLoopMetricsCollector` only inside
/// `clusterNode.network().server().onPresent(...)`, and `QuicClusterNetwork.server()` is always empty, so no group is ever
/// registered: the event-loop lag and saturation the node reports (they feed `DerivedMetrics` and the controller) read zero
/// whatever the QUIC loop is doing. Observed through the collector's registered-group list.
///
/// A-01, wiring half (`SelfShutdownTripwireTest` is the transport half): `AetherNode.stop()` routes
/// `ClusterStateNotification.passive()`, quiesces the router, and only then stops the cluster node, so even with the self
/// guard removed a `SelfShutdown` emitted by `QuicClusterNetwork.stop()` would hit a quiesced router. The recorder below is
/// installed as the router's delegate, so it sees exactly what the router still delivers.
///
/// Reflection reaches two private fields (the node's `router` and the collector's group list); the tests assert the fields
/// exist, so a rename fails loudly instead of passing vacuously. Take the suite lock before running (multi-node, JVM-wide
/// port pool).
@PortBudget
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransportWiringTripwireEmberTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();

    private EmberCluster cluster;

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    private EmberCluster cluster() {
        if (cluster == null) {
            cluster = EmberTestPorts.startedCluster(PORTS,
                                                    basePort -> emberCluster(CLUSTER_SIZE,
                                                                             basePort,
                                                                             basePort + MGMT_OFFSET,
                                                                             basePort + APP_HTTP_OFFSET,
                                                                             "twire"),
                                                    START_BOUND);
        }

        return cluster;
    }

    // --- D-06: event-loop groups registered with the collector ---

    private int registeredEventLoopGroups(AetherNode node) {
        try {
            var collector = field(node.snapshotCollector(), "eventLoopCollector");
            var groups = (List<?>) field(collector, "eventLoopGroups");

            return groups.size();
        } catch (ReflectiveOperationException e) {
            return fail("D-06 reflection target moved: " + e);
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field found = null;

        for (Class<?> type = owner.getClass(); type != null && found == null; type = type.getSuperclass()) {
            for (var candidate : type.getDeclaredFields()) {
                if (candidate.getName().equals(name)) {
                    found = candidate;
                }
            }
        }
        if (found == null) {
            throw new NoSuchFieldException(name + " on " + owner.getClass());
        }
        found.setAccessible(true);

        return found.get(owner);
    }

    private int smallestRegisteredGroupCountAcrossNodes() {
        return cluster().allNodes().stream().mapToInt(this::registeredEventLoopGroups).min().orElse(-1);
    }

    @Test
    @Order(1)
    @Timeout(300)
    void eventLoopMetrics_registerNoQuicEventLoopGroup_onARunningNode() {
        assertThat(smallestRegisteredGroupCountAcrossNodes())
            .as("TRIPWIRE D-06: no event-loop group is registered with the node's EventLoopMetricsCollector (QuicClusterNetwork.server() "
                + "is empty); the QUIC loops are now observed. Delete this tripwire and enable "
                + "eventLoopMetrics_registerTheQuicEventLoopGroup_onARunningNode.")
            .isZero();
    }

    @Disabled("D-06: enable when the node registers the QUIC transport's event-loop group(s) with the event-loop metrics "
              + "collector. Today red: zero groups are registered on every node.")
    @Test
    @Order(2)
    @Timeout(300)
    void eventLoopMetrics_registerTheQuicEventLoopGroup_onARunningNode() {
        assertThat(smallestRegisteredGroupCountAcrossNodes()).as("event-loop groups registered on the node with the fewest").isPositive();
    }

    // --- A-01: SelfShutdown through the node's real stop path ---

    /// SelfShutdown deliveries (to the router's live delegate) during a graceful stop of one running node. Control:
    /// the recorder also saw the node's own `ClusterStateNotification.passive()`, which `AetherNode.stop()` routes just
    /// before it quiesces the router, so an empty answer cannot mean "the recorder was never in the path".
    private List<SelfShutdown> selfShutdownsDeliveredWhileOneNodeStops() {
        var victim = cluster().allNodes().stream().filter(node -> !node.isLeader()).findFirst().orElseThrow();
        var delivered = new CopyOnWriteArrayList<SelfShutdown>();
        var passiveSeen = new AtomicBoolean(false);

        install(recorderOn(victim, delivered, passiveSeen), victim);
        cluster().killNode(victim.self().id(), true).await(STOP_BOUND);
        assertThat(passiveSeen.get()).as("control: the recorder saw ClusterStateNotification.passive() routed by AetherNode.stop()").isTrue();

        return List.copyOf(delivered);
    }

    private static MessageRouter.DelegateRouter routerOf(AetherNode node) {
        try {
            return (MessageRouter.DelegateRouter) field(node, "router");
        } catch (ReflectiveOperationException e) {
            return fail("A-01 reflection target moved: " + e);
        }
    }

    private static MessageRouter currentDelegate(MessageRouter.DelegateRouter router) {
        try {
            return (MessageRouter) field(router, "delegate");
        } catch (ReflectiveOperationException e) {
            return fail("A-01 reflection target moved: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static MessageRouter.ImmutableRouter<Message> recorderOn(AetherNode node, List<SelfShutdown> delivered, AtomicBoolean passiveSeen) {
        var original = currentDelegate(routerOf(node));

        return new MessageRouter.ImmutableRouter<>() {
            @Override
            public Map<Class<Message>, List<Consumer<Message>>> routingTable() {
                return original instanceof MessageRouter.ImmutableRouter<?> immutable
                       ? (Map<Class<Message>, List<Consumer<Message>>>) (Map<?, ?>) immutable.routingTable()
                       : Map.of();
            }

            @Override
            public <R extends Message> void route(R message) {
                if (message instanceof SelfShutdown selfShutdown) {
                    delivered.add(selfShutdown);
                }
                if (message instanceof ClusterStateNotification notification && notification.state() == ClusterStateNotification.State.PASSIVE) {
                    passiveSeen.set(true);
                }
                original.route(message);
            }
        };
    }

    private static void install(MessageRouter recorder, AetherNode node) {
        routerOf(node).replaceDelegate(recorder);
    }

    @Test
    @Order(3)
    @Timeout(300)
    void gracefulNodeStop_deliversNoSelfShutdownThroughTheNodeRouter() {
        assertThat(selfShutdownsDeliveredWhileOneNodeStops())
            .as("TRIPWIRE A-01 (wiring): a graceful AetherNode.stop() delivers no SelfShutdown through its router; one is now "
                + "delivered. Delete this tripwire and enable gracefulNodeStop_deliversOneSelfShutdownForTheStoppingNode.")
            .isEmpty();
    }

    @Disabled("A-01: enable if the overhaul re-sources SelfShutdown from the stop path BEFORE router.quiesce() (delete the pair if the "
              + "spec removes the type). Today red: nothing is delivered (self guard in processViewChange, then the quiesced router).")
    @Test
    @Order(4)
    @Timeout(300)
    void gracefulNodeStop_deliversOneSelfShutdownForTheStoppingNode() {
        assertThat(selfShutdownsDeliveredWhileOneNodeStops()).as("SelfShutdown deliveries during a graceful stop").hasSize(1);
    }
}
