// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// Wiring-level tripwire pair of cluster T (rc4 `7c786db0d3`), on real nodes: it depends on `AetherNode` wiring that no
/// transport-level test reaches. One cluster is started on first use and shared by the two tests, which run in a fixed
/// order.
///
/// D-06: `AetherNode` registers the QUIC event-loop groups with `EventLoopMetricsCollector` only inside
/// `clusterNode.network().server().onPresent(...)`, and `QuicClusterNetwork.server()` is always empty, so no group is ever
/// registered: the event-loop lag and saturation the node reports (they feed `DerivedMetrics` and the controller) read zero
/// whatever the QUIC loop is doing. Observed through the collector's registered-group list.
///
/// The A-01 pair (`SelfShutdown` never delivered) was deleted with the dead producer; `SelfShutdownRemovalTest` in
/// `integrations/consensus` pins the replacement.
///
/// Reflection reaches one private field (the collector's group list); the tests assert the field exists, so a rename fails
/// loudly instead of passing vacuously. Take the suite lock before running (multi-node, JVM-wide port pool).
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
}
