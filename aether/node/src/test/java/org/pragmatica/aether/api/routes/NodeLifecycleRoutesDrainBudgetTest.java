// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.view.MembershipView;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.NodeReportedState;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
///
/// A second, independent pre-fix defect: the count on both sides was role-blind
/// (`presentMembers()` counts workers alongside cores) — an accidental worker-count floor made of
/// miscounting, since workers carry no consensus weight. The `WorkerBypass` nested class pins the
/// fix: a WORKER drain target bypasses this guard entirely (visibly, via `TransitionResult.message()`),
/// and a CORE target's threshold is computed from `coreCountedMembers()` so a connected worker
/// population never dilutes or inflates it.
class NodeLifecycleRoutesDrainBudgetTest {

    private static final int INTENDED_SIZE = 5;

    private final Set<NodeId> pendingDrains = new LinkedHashSet<>();
    private final List<String> routedEvents = new CopyOnWriteArrayList<>();

    private KVStore<AetherKey, AetherValue> kvStore;
    private MembershipFsm fsm;
    private Set<NodeId> allPresent;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, noopSerializer(), null);
        fsm = fsmAllCore(presentMembers());
        allPresent = presentMembers();
    }

    /// No-op serializer: this test seeds the KV store directly (not via consensus dedup), so
    /// the content-based batch id is irrelevant — an empty encoding satisfies `createBatch`.
    private static org.pragmatica.serialization.Serializer noopSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    private NodeId node(int index) {
        return new NodeId("node-" + index);
    }

    private NodeId worker(int index) {
        return new NodeId("worker-" + index);
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
        var snapshot = new LinkedHashMap<NodeId, MembershipView.MemberView>();
        allPresent.forEach(peer -> snapshot.put(peer, new MembershipView.MemberView(peer, null)));

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
        var states = allPresent.stream()
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
                case "membershipFsm" -> fsm;
                case "kvStore" -> kvStore;
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

    private static MembershipFsm fsmWithWorkers(Set<NodeId> cores, Set<NodeId> workers) {
        var fsm = fsmAllCore(cores);

        workers.forEach(id -> promoteWorker(fsm, id));

        return fsm;
    }

    private static void promoteCore(MembershipFsm fsm, NodeId id) {
        fsm.onSwimHealthy(id, 1L);
        fsm.onMemberDescriptor(labeledInfo(id, Map.of(NodeInfo.LABEL_ROLE, "core")));
    }

    private static void promoteWorker(MembershipFsm fsm, NodeId id) {
        fsm.onSwimHealthy(id, 1L);
        fsm.onMemberDescriptor(labeledInfo(id, Map.of(NodeInfo.LABEL_ROLE, "worker")));
    }

    private static NodeInfo labeledInfo(NodeId id, Map<String, String> labels) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("host-x", 6000).unwrap(), labels);
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

    /// #<disruption-budget-issue>: workers carry no consensus weight, so the core-minimum guard
    /// scopes to cores only. Fixture: 5 cores (`node-1..5`, matching `DisruptionBudget`'s baseline)
    /// plus 4 workers (`worker-1..4`), all present and READY.
    @Nested
    class WorkerBypass {

        private Set<NodeId> workers;

        @BeforeEach
        void seedWorkers() {
            workers = IntStream.rangeClosed(1, 4)
                               .mapToObj(NodeLifecycleRoutesDrainBudgetTest.this::worker)
                               .collect(Collectors.toCollection(LinkedHashSet::new));
            fsm = fsmWithWorkers(presentMembers(), workers);
            allPresent = new LinkedHashSet<>(presentMembers());
            allPresent.addAll(workers);
        }

        /// The reported repro: on a 5-core + 4-worker cluster, sequentially draining every worker
        /// must never trip the guard — a role-blind guard treats worker headcount as consensus
        /// capacity it doesn't protect, so enough sequential worker drains could wrongly exhaust it.
        @Test
        void drainNode_sequentialWorkerDrains_neverTripTheGuard_onFiveCoreFourWorkerCluster() {
            workers.forEach(target -> {
                var result = routes().drainNodeForTest(target.id())
                                     .onFailure(cause -> fail("Worker " + target.id()
                                                             + " drain must bypass the core-quorum guard entirely: "
                                                             + cause.message()))
                                     .await();

                assertThat(result.isSuccess()).isTrue();
                result.onSuccess(transition -> assertThat(transition.message()).contains("core-guard skipped (role=worker)"));

                pendingDrains.add(target);
            });
        }

        /// Core-side inverse: a connected worker population must not dilute or inflate the CORE
        /// guard's math. Same two-in-flight-core-drains scenario as
        /// `DisruptionBudget.drainNode_rejected_whenTwoDrainsAlreadyInFlight`, now with 4 workers
        /// also present — the guard must still reject at the identical core-scoped threshold.
        @Test
        void drainNode_coreGuard_stillTripsAtCoreScopedThreshold_whenWorkersArePresent() {
            pendingDrains.add(node(1));
            pendingDrains.add(node(2));

            var result = routes().drainNodeForTest(node(3).id())
                                 .onSuccess(_ -> fail("Third core drain on a 5-core+4-worker cluster with two "
                                                     + "in-flight core drains must still be rejected"))
                                 .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(((HttpError) cause).status()).isEqualTo(HttpStatus.CONFLICT));
            result.onFailure(cause -> assertThat(cause.message()).contains("Disruption budget exceeded"));
            result.onFailure(cause -> assertThat(cause.message()).contains("core-scoped"));
        }

        /// A pending WORKER drain carries no core-quorum weight and must not deflate the CORE
        /// availability count: with two worker drains pending but zero core drains pending, a core
        /// drain must pass exactly as if nothing were pending (5 - 0 - 1 = 4 >= 3).
        @Test
        void drainNode_coreGuard_ignoresPendingWorkerDrains_whenCheckingCoreBudget() {
            pendingDrains.add(workers.stream().findFirst().orElseThrow());
            pendingDrains.add(workers.stream().skip(1).findFirst().orElseThrow());

            var result = routes().drainNodeForTest(node(1).id())
                                 .onFailure(cause -> fail("A pending WORKER drain must not count against the CORE budget: "
                                                         + cause.message()))
                                 .await();

            assertThat(result.isSuccess()).isTrue();
            result.onSuccess(transition -> assertThat(transition.message()).contains("core-guard applied (role=core"));
        }
    }
}
