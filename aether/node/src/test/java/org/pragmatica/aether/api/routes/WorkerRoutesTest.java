// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.WorkerInfo;
import org.pragmatica.aether.api.ManagementApiResponses.WorkersResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #525: `/api/workers` was declared, offered by `aether workers list`, and served by nobody.
///
/// What is proven here is that the roster comes from COMMITTED consensus state — the governor
/// announcements — and is projected per-worker rather than per-community, which is the difference
/// between this route and `/api/cluster/governors`.
class WorkerRoutesTest {
    private static final NodeId GOVERNOR = NodeId.nodeId("governor-1").unwrap();
    private static final NodeId WORKER_A = NodeId.nodeId("worker-a").unwrap();
    private static final NodeId WORKER_B = NodeId.nodeId("worker-b").unwrap();

    @Test
    void workersList_projectsOneRowPerMember_ofEveryLiveCommunity() {
        var store = new TestKVStore();

        store.put(GovernorAnnouncementKey.forCommunity("east"),
                  announcement(GOVERNOR, List.of(GOVERNOR, WORKER_A), false));
        store.put(GovernorAnnouncementKey.forCommunity("west"),
                  announcement(WORKER_B, List.of(WORKER_B), false));

        var workers = fetchWorkers(store);

        assertThat(workers).hasSize(3);
        assertThat(workers.stream().map(WorkerInfo::nodeId))
                .containsExactly("governor-1", "worker-a", "worker-b");
        assertThat(workers.stream().map(WorkerInfo::community))
                .containsExactly("east", "east", "west");
    }

    /// The governor is itself a member of its community, and an operator needs to tell it apart from
    /// the workers it governs — otherwise `workers list` and `cluster governors` cannot be reconciled.
    @Test
    void workersList_flagsTheGovernorAmongItsOwnMembers() {
        var store = new TestKVStore();

        store.put(GovernorAnnouncementKey.forCommunity("east"),
                  announcement(GOVERNOR, List.of(GOVERNOR, WORKER_A), false));

        var workers = fetchWorkers(store);

        assertThat(workers).filteredOn(WorkerInfo::isGovernor)
                           .extracting(WorkerInfo::nodeId)
                           .containsExactly("governor-1");
        assertThat(workers).allMatch(worker -> "governor-1".equals(worker.governorId()));
    }

    /// A dissolved community has been stood down by its governor; its members are no longer serving
    /// it. Reporting them would claim workers for a community that no longer exists.
    @Test
    void workersList_omitsMembers_ofDissolvedCommunities() {
        var store = new TestKVStore();

        store.put(GovernorAnnouncementKey.forCommunity("east"),
                  announcement(GOVERNOR, List.of(GOVERNOR, WORKER_A), true));
        store.put(GovernorAnnouncementKey.forCommunity("west"),
                  announcement(WORKER_B, List.of(WORKER_B), false));

        assertThat(fetchWorkers(store)).extracting(WorkerInfo::nodeId)
                                       .containsExactly("worker-b");
    }

    /// A cluster with no workers must report an empty roster, not fail — the honest degenerate value.
    @Test
    void workersList_returnsEmptyRoster_whenNoCommunitiesAnnounced() {
        assertThat(fetchWorkers(new TestKVStore())).isEmpty();
    }

    @Test
    void routes_registerWorkersList_underTheDeclaredRouteName() {
        assertThat(WorkerRoutes.workerRoutes(() -> nodeWith(new TestKVStore())).routes())
                .extracting(Route::name)
                .containsExactly(ManagementRoute.WORKERS_LIST.name());
    }

    private static List<WorkerInfo> fetchWorkers(TestKVStore store) {
        return workersResponse(store).workers();
    }

    /// `toJson(Supplier)` ignores the request context entirely, so no request needs to be faked.
    private static WorkersResponse workersResponse(TestKVStore store) {
        return WorkerRoutes.workerRoutes(() -> nodeWith(store))
                           .routes()
                           .findFirst()
                           .orElseThrow()
                           .handler()
                           .handle(null)
                           .await()
                           .onFailure(cause -> fail(cause.message()))
                           .map(WorkersResponse.class::cast)
                           .unwrap();
    }

    private static GovernorAnnouncementValue announcement(NodeId governorId, List<NodeId> members, boolean dissolved) {
        return new GovernorAnnouncementValue(governorId,
                                             members.size(),
                                             members,
                                             "10.0.0.1:9000",
                                             1700000000000L,
                                             7L,
                                             Epoch.ZERO,
                                             Epoch.ZERO,
                                             HlcTimestamp.ZERO,
                                             dissolved);
    }

    private static ManageableNode nodeWith(KVStore<AetherKey, AetherValue> store) {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, _) -> {
                if ("kvStore".equals(method.getName())) {
                    return store;
                }
                throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            });
    }

    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        private TestKVStore() {
            super(null, null, null);
        }

        void put(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }
    }
}
