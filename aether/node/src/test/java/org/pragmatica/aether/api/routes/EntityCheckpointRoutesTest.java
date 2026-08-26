// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.EntityKeyspaceView;
import org.pragmatica.aether.api.ManagementApiResponses.EntityKeyspacesResponse;
import org.pragmatica.aether.node.EntityOwnershipReconciler;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityKeyspaceRegistrationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// The #634-3 keyspace HOSTING view: the set of committed per-node registrations IS the hosting set the
/// leader mints entity-arc owners over (the 02w fix), and this is its read-side projection.
///
/// The merge semantics are the reconciler's (`EntityOwnershipReconciler.scanRegistrations` is the
/// AUTHORITY); this test exists to keep the projection behaviourally identical to it — a surface that
/// reported a different partition count or a different hosting set from the one placement actually uses
/// would send an operator hunting the wrong node.
class EntityCheckpointRoutesTest {
    private static final String ORDERS = "orders";
    private static final String BILLING = "billing";

    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");
    private static final NodeId N3 = new NodeId("node-3");

    private static final int LOW_COUNT = 4;
    private static final int HIGH_COUNT = 8;
    private static final int BILLING_COUNT = 2;

    /// A rolling redeploy window: two hosts of `orders` disagree about the partition count. The MAX wins
    /// (extra arcs are harmless no-ops; minting fewer than a host fences against strands that host's
    /// writes forever) and the disagreement surfaces as data. BOTH registration orders are pinned, for
    /// the same reason the reconciler's own test pins them — an implementation that maxed or warned in
    /// only one order would otherwise pass.
    @Test
    void assembleKeyspaces_mergesHostsAndMaxCount_andFlagsDisagreement() {
        assertMergedView(storeWithOrdersCounts(LOW_COUNT, HIGH_COUNT));
        assertMergedView(storeWithOrdersCounts(HIGH_COUNT, LOW_COUNT));
    }

    private static void assertMergedView(KVStore<AetherKey, AetherValue> store) {
        var response = EntityCheckpointRoutes.assembleKeyspaces(store);

        assertThat(keyspaceNames(response)).as("keyspaces are sorted by name, so an operator diffing two reads sees"
                                               + " only real change")
                                           .containsExactly(BILLING, ORDERS);

        var orders = viewOf(response, ORDERS);
        var billing = viewOf(response, BILLING);

        assertThat(orders.partitionCount()).as("the max count must win regardless of registration order")
                                           .isEqualTo(HIGH_COUNT);
        assertThat(orders.hosts()).as("hosts are sorted, so the response is stable across reads")
                                  .containsExactly(N1.id(), N2.id());
        assertThat(orders.partitionCountsDisagree()).as("the disagreement must surface as data, not a silent max")
                                                    .isTrue();

        assertThat(billing.partitionCount()).isEqualTo(BILLING_COUNT);
        assertThat(billing.hosts()).containsExactly(N3.id());
        assertThat(billing.partitionCountsDisagree())
            .as("a keyspace whose hosts agree must NOT be flagged — else the disagreement flag is noise nobody"
                + " can act on")
            .isFalse();
    }

    /// The equivalence the review demanded. `assembleKeyspaces` is now a pure projection over
    /// [EntityOwnershipReconciler#scanRegistrations] — the single authority on the merge — so the
    /// operator surface cannot drift from what the leader actually places over. The merge itself is now
    /// structurally shared, but the projection still OWNS the `NodeId`-to-string mapping and both
    /// sort orders, and those are exactly what a reader compares across nodes.
    @Test
    void assembleKeyspaces_projectsTheReconcilerAuthority_withoutAlteringTheMerge() {
        var store = storeWithOrdersCounts(LOW_COUNT, HIGH_COUNT);
        var authority = EntityOwnershipReconciler.scanRegistrations(store);
        var response = EntityCheckpointRoutes.assembleKeyspaces(store);

        assertThat(keyspaceNames(response))
            .as("every keyspace the leader sees is reported, and in name order")
            .containsExactlyElementsOf(authority.keySet().stream().sorted().toList());
        assertThat(response.keyspaces()).isNotEmpty();
        response.keyspaces().forEach(view -> assertProjects(authority.get(view.keyspace()), view));
    }

    private static void assertProjects(EntityOwnershipReconciler.HostedKeyspace hosted, EntityKeyspaceView view) {
        assertThat(view.partitionCount()).as("%s: the arc span is the authority's, unmodified", view.keyspace())
                                         .isEqualTo(hosted.partitionCount());
        assertThat(view.partitionCountsDisagree()).as("%s: the disagreement signal is the authority's, unmodified",
                                                       view.keyspace())
                                                  .isEqualTo(hosted.countsDisagree());
        assertThat(view.hosts()).as("%s: the projection owns the id-mapping and the sort", view.keyspace())
                                .containsExactlyElementsOf(hosted.hosts()
                                                                 .stream()
                                                                 .map(NodeId::id)
                                                                 .sorted()
                                                                 .toList());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static List<String> keyspaceNames(EntityKeyspacesResponse response) {
        return response.keyspaces()
                       .stream()
                       .map(EntityKeyspaceView::keyspace)
                       .toList();
    }

    private static EntityKeyspaceView viewOf(EntityKeyspacesResponse response, String keyspace) {
        return response.keyspaces()
                       .stream()
                       .filter(view -> view.keyspace().equals(keyspace))
                       .findFirst()
                       .orElseThrow(() -> new AssertionError("keyspace not in response: " + keyspace));
    }

    /// Two hosts of `orders` with the given declared counts, plus a single-host `billing` — the agreeing
    /// control that arms the disagreement flag.
    private static KVStore<AetherKey, AetherValue> storeWithOrdersCounts(int firstCount, int secondCount) {
        var store = emptyStore();

        seedRegistration(store, ORDERS, N1, firstCount);
        seedRegistration(store, ORDERS, N2, secondCount);
        seedRegistration(store, BILLING, N3, BILLING_COUNT);

        return store;
    }

    private static void seedRegistration(KVStore<AetherKey, AetherValue> store,
                                         String keyspace,
                                         NodeId node,
                                         int partitionCount) {
        store.process(store.createBatch(List.of(registrationPut(keyspace, node, partitionCount))));
    }

    private static KVCommand<AetherKey> registrationPut(String keyspace, NodeId node, int partitionCount) {
        return new KVCommand.Put<AetherKey, AetherValue>(EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey(keyspace,
                                                                                                                     node),
                                                         EntityKeyspaceRegistrationValue.entityKeyspaceRegistrationValue(partitionCount));
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    /// Nothing here restores a snapshot, so a read is a bug rather than a value worth stubbing.
    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                throw new UnsupportedOperationException("not used by this test");
            }
        };
    }
}
