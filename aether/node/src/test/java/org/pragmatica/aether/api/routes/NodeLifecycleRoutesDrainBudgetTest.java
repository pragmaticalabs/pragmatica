// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Covers the disruption-budget guard on `POST /api/nodes/drain/{id}` (13-edge-cases regression).
///
/// Pre-fix the guard computed BOTH sides of the budget inequality from the live SWIM presence set
/// and counted in-flight drains as still-operational. Because a drain does NO lifecycle/KV write and
/// DRAINING is not part of `presentMembers()`, a previously-commanded drain was invisible — the
/// threshold and the post-drain count shrank in lockstep and the guard could never reject sequential
/// in-flight drains, admitting a quorum-losing cascade. The fix thresholds against a stable intended
/// size (configured/peak core count) and subtracts the leader's commanded-but-not-departed drains.
class NodeLifecycleRoutesDrainBudgetTest {

    private static final int INTENDED_SIZE = 5;

    private final Set<NodeId> pendingDrains = new LinkedHashSet<>();
    private final java.util.List<String> routedEvents = new CopyOnWriteArrayList<>();

    private NodeId node(int index) {
        return new NodeId("node-" + index);
    }

    private Set<NodeId> presentMembers() {
        return IntStream.rangeClosed(1, INTENDED_SIZE)
                        .mapToObj(this::node)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private NodeLifecycleRoutes routes() {
        return NodeLifecycleRoutes.nodeLifecycleRoutes(this::nodeProxy,
                                                       pendingDrains::add,
                                                       () -> Set.copyOf(pendingDrains));
    }

    private MembershipView membershipView() {
        var present = presentMembers();
        var snapshot = new LinkedHashMap<NodeId, MembershipView.MemberView>();
        present.forEach(peer -> snapshot.put(peer, new MembershipView.MemberView(peer, null)));

        return new MembershipView() {
            @Override
            public Map<NodeId, MemberView> snapshot() {
                return Map.copyOf(snapshot);
            }

            @Override
            public Option<MemberView> get(NodeId peer) {
                return Option.option(snapshot.get(peer));
            }
        };
    }

    /// All present members report READY so an admitted drain flows through the READY gate and
    /// succeeds — proving the budget guard did NOT reject (rather than being masked by a later guard).
    private ClusterSyncCollector metricsCollector() {
        var states = presentMembers().stream()
                                     .collect(Collectors.toMap(peer -> peer, _ -> NodeReportedState.READY));
        return (ClusterSyncCollector) Proxy.newProxyInstance(
            ClusterSyncCollector.class.getClassLoader(),
            new Class[]{ClusterSyncCollector.class},
            (_, method, _) -> switch (method.getName()) {
                case "reportedStates" -> Map.copyOf(states);
                case "hasAuthoritativeReadiness" -> Boolean.TRUE;
                default -> throw new UnsupportedOperationException("Not implemented: " + method.getName());
            });
    }

    private ManageableNode nodeProxy() {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, args) -> switch (method.getName()) {
                case "membershipView" -> membershipView();
                case "metricsCollector" -> metricsCollector();
                case "initialTopology" -> presentMembers().stream().toList();
                case "route" -> recordRoute(args);
                default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            });
    }

    private Object recordRoute(Object[] args) {
        routedEvents.add(String.valueOf(args[0]));
        return null;
    }

    @Nested
    class DisruptionBudget {

        @Test
        void drainNode_rejected_whenTwoDrainsAlreadyInFlight() {
            pendingDrains.add(node(1));
            pendingDrains.add(node(2));

            var result = routes().drainNodeForTest(node(3).id())
                                 .onSuccess(_ -> fail("Third drain on a 5-node cluster with two in-flight drains must be rejected"))
                                 .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(((HttpError) cause).status()).isEqualTo(HttpStatus.CONFLICT));
            result.onFailure(cause -> assertThat(cause.message()).contains("Disruption budget exceeded"));
        }

        @Test
        void drainNode_allowed_whenNoDrainsInFlight() {
            var result = routes().drainNodeForTest(node(1).id())
                                 .onFailure(cause -> fail("First drain on a healthy 5-node cluster must pass the budget: " + cause.message()))
                                 .await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(transition -> assertThat(transition.state()).isEqualTo(NodeReportedState.DRAINING.name()));
        }

        @Test
        void drainNode_allowed_whenOneDrainInFlight() {
            pendingDrains.add(node(1));

            var result = routes().drainNodeForTest(node(2).id())
                                 .onFailure(cause -> fail("Second drain (one in flight) must still pass the budget on a 5-node cluster: " + cause.message()))
                                 .await();

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        void drainNode_chargesTargetOnce_whenAlreadyPending() {
            // Target already registered (retried call): it must be charged exactly once, not twice.
            // With one OTHER drain in flight + this target pending, available = 5 - 1 - 1 = 3 >= 3 → allowed.
            pendingDrains.add(node(1));
            pendingDrains.add(node(2));

            var result = routes().drainNodeForTest(node(2).id())
                                 .onFailure(cause -> fail("Re-drain of an already-pending target must not be double-counted: " + cause.message()))
                                 .await();

            assertThat(result.isSuccess()).isTrue();
        }
    }
}
