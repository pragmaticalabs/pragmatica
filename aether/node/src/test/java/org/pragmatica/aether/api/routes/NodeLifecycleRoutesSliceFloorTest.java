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


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;


/// #1720: operator drain and shutdown bypassed the slice `minAvailable` floor that the automatic drain honours
/// (#1488). The pins run the REAL KV-backed guard ([SliceOwnershipQuery#minAvailableDrainViolations]) against a real
/// KV store, through the real routes: a drain or shutdown that would leave a hosted slice below its floor is a typed 409
/// naming the slice and counts; `force` overrides and is then never silent (an operator warning); concurrent admissions
/// are serialised so two requests cannot both pass against one pending-drains snapshot.
class NodeLifecycleRoutesSliceFloorTest {
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final String SLICE_A = "org.example:a:1.0.0";
    private static final String SLICE_B = "org.example:b:1.0.0";

    private static final int INTENDED_SIZE = 5;

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();

    private final Set<NodeId> pendingDrains = new LinkedHashSet<>();
    private final List<String> routedEvents = new CopyOnWriteArrayList<>();

    private KVStore<AetherKey, AetherValue> kvStore;
    private MembershipFsm fsm;
    private Set<NodeId> allPresent;
    private Set<NodeId> installedVoters;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, noopSerializer(), null);
        fsm = fsmAllCore(presentMembers());
        allPresent = presentMembers();
        installedVoters = presentMembers();
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
        return routes(pendingDrains::add);
    }

    private NodeLifecycleRoutes routes(java.util.function.Consumer<NodeId> sink) {
        return NodeLifecycleRoutes.nodeLifecycleRoutes(this::nodeProxy,
                                                       sink,
                                                       () -> Set.copyOf(pendingDrains),
                                                       NodeLifecycleRoutes.SliceFloor.sliceFloor(SliceOwnershipQuery.minAvailableDrainViolations(kvStore),
                                                                                                 OperatorWarningSink.handingOffTo(warnings::add)));
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

    private static NodeLifecycleRoutes.SliceFloorBreached breach(org.pragmatica.lang.Result<?> result) {
        assertThat(result.isFailure()).as("expected a refusal, got " + result).isTrue();
        var holder = new java.util.concurrent.atomic.AtomicReference<org.pragmatica.lang.Cause>();

        result.onFailure(holder::set);
        assertThat(holder.get()).isInstanceOf(NodeLifecycleRoutes.SliceFloorBreached.class);

        return (NodeLifecycleRoutes.SliceFloorBreached) holder.get();
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
                case "coreNodeIds" -> installedVoters;
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


    /// Slice A, floor 2, on node-1..3. Draining node-1 leaves A on node-2 and node-3 (2, at the floor): admitted.
    /// With node-1 already pending, draining node-2 leaves A on node-3 only (1 < 2): refused.
    @Test
    void drain_wouldLeaveSliceBelowItsFloor_isRefused409_namingSliceAndCounts() {
        var a = slice("a", 3, 2);
        host(a, node(1), node(2), node(3));

        assertThat(routes().drainNodeForTest(node(1).id()).await().isSuccess()).as("control: at the floor is allowed").isTrue();

        var refusal = breach(routes().drainNodeForTest(node(2).id()).await());

        assertThat(((HttpStatusAware) refusal).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refusal.message()).contains(SLICE_A, "1 ACTIVE", "minAvailable 2", "force=true");
        assertThat(pendingDrains).as("a refused drain reserves nothing").containsExactly(node(1));
        assertThat(settledWarnings()).as("a refusal is not a forced breach").isEmpty();
    }

    @Test
    void shutdown_wouldLeaveSliceBelowItsFloor_isRefused409() {
        var a = slice("a", 3, 2);
        host(a, node(1), node(2), node(3));
        pendingDrains.add(node(1));

        var refusal = breach(routes().shutdownNodeForTest(node(2).id()).await());

        assertThat(refusal.message()).contains("shutdown", SLICE_A);
        assertThat(pendingDrains).containsExactly(node(1));
    }

    /// Workers carry slices and bypass the core quorum budget, so the floor is their only operator-drain guard.
    @Test
    void workerDrain_isGuardedByTheFloor_thoughItBypassesTheCoreBudget() {
        fsm = fsmWithWorkers(presentMembers(), Set.of(worker(1)));
        allPresent = new LinkedHashSet<>(presentMembers());
        allPresent.add(worker(1));
        var b = slice("b", 2, 2);
        host(b, worker(1), node(4));

        var refusal = breach(routes().drainNodeForTest(worker(1).id()).await());

        assertThat(refusal.message()).contains(SLICE_B, "1 ACTIVE", "minAvailable 2");
        assertThat(pendingDrains).isEmpty();
    }

    @Test
    void force_overridesTheFloor_andRaisesAnOperatorWarningNamingTheSlices() {
        var a = slice("a", 3, 2);
        var b = slice("b", 3, 2);
        host(a, node(1), node(2), node(3));
        host(b, node(2), node(3), node(4));
        pendingDrains.add(node(1));

        var forced = routes().drainNodeForTest(node(2).id(), true).await();

        assertThat(forced.isSuccess()).as("a forced drain is admitted: " + forced).isTrue();
        assertThat(pendingDrains).contains(node(2));
        assertThat(awaitWarnings(1)).hasSize(1);
        var warning = warnings.getFirst();

        assertThat(warning.code()).isEqualTo(OperatorWarningCode.SLICE_FLOOR_BREACHED_BY_FORCE);
        assertThat(warning.subject()).isEqualTo(node(2).id());
        assertThat(warning.message()).contains("drain", SLICE_A);
    }

    @Test
    void forcedShutdown_alsoWarns() {
        var a = slice("a", 3, 2);
        host(a, node(1), node(2), node(3));
        pendingDrains.add(node(1));

        assertThat(routes().shutdownNodeForTest(node(2).id(), true).await().isSuccess()).isTrue();
        assertThat(awaitWarnings(1)).hasSize(1);
        assertThat(warnings.getFirst().message()).contains("shutdown");
    }

    /// A forced request that breaches nothing is just a drain: no warning, so the warning means a breach.
    @Test
    void force_withoutABreach_raisesNoWarning() {
        var a = slice("a", 3, 2);
        host(a, node(1), node(2), node(3));

        assertThat(routes().drainNodeForTest(node(1).id(), true).await().isSuccess()).isTrue();
        assertThat(settledWarnings()).isEmpty();
    }

    /// The sink hands each warning to a virtual thread (it never runs the publisher on the raising thread), so a
    /// positive assertion waits for it, and a negative one gives it time to arrive before concluding it did not.
    private List<OperatorWarning> awaitWarnings(int expected) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (warnings.size() < expected && System.nanoTime() < deadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
        }

        return List.copyOf(warnings);
    }

    private List<OperatorWarning> settledWarnings() {
        java.util.concurrent.locks.LockSupport.parkNanos(300_000_000L);

        return List.copyOf(warnings);
    }

    /// The query parameter reaches the handler: `?force=true` through the real route breaks the floor, its
    /// absence does not.
    @Test
    void route_forceQueryParameter_isHonoured() {
        var a = slice("a", 3, 2);
        host(a, node(1), node(2), node(3));
        pendingDrains.add(node(1));
        var route = routes().routes()
                            .filter(candidate -> candidate.name().equals(ManagementRoute.NODE_DRAIN.name()))
                            .findFirst()
                            .orElseThrow();

        var unforced = invoke(route, node(2), Map.of());
        var forced = invoke(route, node(2), Map.of("force", List.of("true")));

        assertThat(unforced.isFailure()).as("without force the floor refuses: " + unforced).isTrue();
        assertThat(forced.isSuccess()).as("with ?force=true it is admitted: " + forced).isTrue();
    }

    @SuppressWarnings("unchecked")
    private Result<Object> invoke(Route<?> route, NodeId target, Map<String, List<String>> query) {
        return ((Route<Object>) route).handler().handle(new QueryRequestContext(target.id(), query)).await();
    }

    /// Serialisation (#1720): two operator requests must not both pass against one pending-drains snapshot. The
    /// first is held INSIDE the sink (the reservation point), the second then arrives; only the admission monitor
    /// keeps the second from evaluating the floor against a pending set that does not yet hold the first.
    @Test
    void concurrentOperatorDrains_cannotBothPassTheFloor() {
        var a = slice("a", 3, 2);
        host(a, node(1), node(2), node(3));
        var entered = Promise.<Unit> promise();
        var release = Promise.<Unit> promise();
        var calls = new AtomicInteger();
        var routes = routes(target -> {
            calls.incrementAndGet();
            entered.succeed(Unit.unit());
            release.await(TimeSpan.timeSpan(5).seconds());
            pendingDrains.add(target);
        });
        var first = CompletableFuture.supplyAsync(() -> routes.drainNodeForTest(node(1).id()).await());

        assertThat(entered.await(TimeSpan.timeSpan(5).seconds()).isSuccess()).isTrue();
        var second = CompletableFuture.supplyAsync(() -> routes.drainNodeForTest(node(2).id()).await());

        java.util.concurrent.locks.LockSupport.parkNanos(TimeSpan.timeSpan(200).millis().nanos());
        assertThat(calls).as("the second admission waits for the first's reservation").hasValue(1);
        release.succeed(Unit.unit());

        assertThat(first.orTimeout(5, TimeUnit.SECONDS).join().isSuccess()).isTrue();
        breach(second.orTimeout(5, TimeUnit.SECONDS).join());
        assertThat(pendingDrains).containsExactly(node(1));
    }

    /// Destroy's premise (#1720 ruling): it takes every slice below its floor by design, so it passes `force`. A
    /// slice with floor 4 on all five nodes tolerates one drain; the second is refused unforced and admitted forced
    /// (the core quorum budget, a separate guard, is not what stops it here: 5 - 2 = 3 voters remain).
    @Test
    void forcedDrain_isAdmittedWhereTheUnforcedOneIsRefused() {
        var a = slice("a", 5, 4);
        host(a, node(1), node(2), node(3), node(4), node(5));

        assertThat(routes().drainNodeForTest(node(1).id()).await().isSuccess()).isTrue();
        breach(routes().drainNodeForTest(node(2).id()).await());
        assertThat(routes().drainNodeForTest(node(2).id(), true).await().isSuccess()).as("force is the destroy path").isTrue();
    }

    private record QueryRequestContext(String nodeId, Map<String, List<String>> query) implements RequestContext {
        @Override
        public List<String> pathParams() {
            return List.of(nodeId);
        }

        @Override
        public org.pragmatica.http.QueryParams queryParams() {
            return org.pragmatica.http.QueryParams.queryParams(query);
        }

        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            throw new UnsupportedOperationException("fromJson");
        }

        @Override
        public byte[] body() {
            return new byte[0];
        }

        @Override
        public Route<?> route() {
            throw new UnsupportedOperationException("route");
        }

        @Override
        public io.netty.handler.codec.http.HttpHeaders responseHeaders() {
            throw new UnsupportedOperationException("responseHeaders");
        }

        @Override
        public String requestId() {
            return "req-test";
        }

        @Override
        public org.pragmatica.http.HttpMethod method() {
            return org.pragmatica.http.HttpMethod.POST;
        }

        @Override
        public String path() {
            return "/api/v1/nodes/drain/" + nodeId;
        }

        @Override
        public org.pragmatica.http.Headers headers() {
            throw new UnsupportedOperationException("headers");
        }
    }
}
