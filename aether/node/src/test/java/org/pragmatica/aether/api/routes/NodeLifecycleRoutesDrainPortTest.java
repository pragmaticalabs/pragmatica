// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// C6a-0 (#3467): direct pins for [NodeLifecycleRoutes.DrainPort] and for the one reader the routes make of it
/// (`drainUnderWay`). The port's no-op and null-default behaviour, and the legacy factories' inert port, were pinned
/// only by tests whose subject is something else; the null defaults are pinned as they are today (tripwires).
class NodeLifecycleRoutesDrainPortTest {
    private static final NodeId TARGET = NodeId.nodeId("node-target").unwrap();

    @Test
    void noneRequest_dropsTheRequestWithoutThrowing() {
        NodeLifecycleRoutes.DrainPort.none().request(TARGET);
    }

    @Test
    void nonePending_isEmptyNotNull() {
        assertThat(NodeLifecycleRoutes.DrainPort.none().pending()).isNotNull().isEmpty();
    }

    @Test
    void registryRequest_reachesTheSink() {
        var requested = new ArrayList<NodeId>();

        NodeLifecycleRoutes.DrainPort.registry(requested::add, Set::of).request(TARGET);

        assertThat(requested).containsExactly(TARGET);
    }

    @Test
    void registryPending_readsTheSupplierEachTime() {
        var pending = new ArrayList<NodeId>();
        var port = NodeLifecycleRoutes.DrainPort.registry(_ -> {}, () -> Set.copyOf(pending));

        assertThat(port.pending()).isEmpty();
        pending.add(TARGET);
        assertThat(port.pending()).containsExactly(TARGET);
    }

    /// Tripwire naming #3467 O1: a null sink FAILS OPEN to a no-op (the rule kept from the base). Changing it to refuse
    /// or to throw is a deliberate decision, and this test is where that decision is recorded.
    @Test
    void registryWithNullSink_failsOpenToANoOp() {
        NodeLifecycleRoutes.DrainPort.registry(null, Set::of).request(TARGET);
    }

    /// Tripwire naming #3467 O1: a null pending supplier FAILS OPEN to an empty pending set, so the disruption budget
    /// counts no in-flight drain.
    @Test
    void registryWithNullPendingSupplier_failsOpenToAnEmptySet() {
        assertThat(NodeLifecycleRoutes.DrainPort.registry(_ -> {}, null).pending()).isNotNull().isEmpty();
    }

    /// Tripwire naming #3467 O1: a null port becomes `DrainPort.none()`, so a drain is not seen as under way.
    @Test
    void routesWithNullPort_failOpenToNone() {
        var routes = NodeLifecycleRoutes.withDrainPort(this::nodeReportingNoState, null, noFloor());

        assertThat(routes.drainUnderWay(TARGET)).isFalse();
    }

    /// Tripwire naming #3467 O1: the single-arg legacy factory wires an inert port (no-op sink, empty pending set), so
    /// its pending read is a non-null empty set and a drain is not seen as under way.
    @Test
    void singleArgFactory_wiresAnInertPort() {
        var routes = NodeLifecycleRoutes.nodeLifecycleRoutes(this::nodeReportingNoState);

        assertThat(routes.drainUnderWay(TARGET)).isFalse();
    }

    @Test
    void drainUnderWay_isTrueForATargetThePortReportsPending() {
        var port = NodeLifecycleRoutes.DrainPort.registry(_ -> {}, () -> Set.of(TARGET));
        var routes = NodeLifecycleRoutes.withDrainPort(this::nodeReportingNoState, port, noFloor());

        assertThat(routes.drainUnderWay(TARGET)).as("pending in the port, no lifecycle state reported").isTrue();
    }

    @Test
    void drainUnderWay_isFalseForATargetThePortDoesNotReport() {
        var port = NodeLifecycleRoutes.DrainPort.registry(_ -> {}, () -> Set.of(NodeId.nodeId("node-other").unwrap()));
        var routes = NodeLifecycleRoutes.withDrainPort(this::nodeReportingNoState, port, noFloor());

        assertThat(routes.drainUnderWay(TARGET)).isFalse();
    }

    private static NodeLifecycleRoutes.SliceFloor noFloor() {
        return NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> List.of(), OperatorWarningSink.logOnly());
    }

    /// A node whose collector reports no lifecycle state for anyone: only the port can make a drain "under way".
    private ManageableNode nodeReportingNoState() {
        var collector = (ClusterSyncCollector) Proxy.newProxyInstance(ClusterSyncCollector.class.getClassLoader(),
                                                                      new Class[]{ClusterSyncCollector.class},
                                                                      (_, method, _) -> switch (method.getName()) {
                                                                          case "reportedStates" -> Map.of();
                                                                          default -> throw new UnsupportedOperationException(method.getName());
                                                                      });

        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "metricsCollector" -> collector;
                                                           default -> throw new UnsupportedOperationException(method.getName());
                                                       });
    }
}
