// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.worker.health.CommunityMemberDirectory;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.SwimMember;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Pins the PER-EDGE cost of `CommunityMembershipFilter` as driven by `AetherNode` (#1840, H12/H-T13): the
/// worker re-runs the filter on every SWIM observation edge. It used to copy and stream the WHOLE committed
/// KV snapshot per edge (O(KV)); it now reads the per-community candidate index that committed directive
/// changes maintain, so an edge costs O(alive view + community candidates) whatever the KV holds.
///
/// This replaces the #1843 tripwire, which pinned the old scan (N(N+1)/2 entries over a wave of N edges).
/// The observer's alive view is held at its scope (cores plus its own community, H12), so what grows across
/// the sizes is only the KV and the number of OTHER communities.
///
/// Deterministic measures: KV snapshot calls and entries scanned during the edges (a counting double wired
/// through the same committed-notification path `AetherNode` uses), the candidate-set size, and alive members
/// tested. A control proves the counter counts: rebuilding the index from a snapshot is one call that scans N.
/// What the index lookup itself touches is pinned separately and deterministically, by an operation count:
/// `CommunityMemberDirectoryOpCountTest`.
class CommunityMembershipFilterPerEdgeCostTest {
    private static final int COMMUNITY_SIZE = 10;
    private static final int CORES = 3;
    private static final String OBSERVED_COMMUNITY = "c-0";
    private static final int[] SIZES = {200, 1_000, 10_000};
    private static final int EDGES = 200;

    private record EdgeCost(long snapshotCalls, long kvEntriesScanned, long aliveTested, int candidates, int lastResultSize) {}

    @Test
    void perEdge_noKvScan_andWorkIndependentOfKvSize() {
        var costs = new ArrayList<EdgeCost>();

        for (var n : SIZES) {
            var cost = edges(n);

            costs.add(cost);
            System.out.printf("N=%d edges=%d kvSnapshots=%d kvEntriesScanned=%d aliveTested=%d candidates=%d%n",
                              n,
                              EDGES,
                              cost.snapshotCalls(),
                              cost.kvEntriesScanned(),
                              cost.aliveTested(),
                              cost.candidates());
            assertThat(cost.snapshotCalls()).as("KV snapshots over %d edges at N=%d", EDGES, n).isZero();
            assertThat(cost.kvEntriesScanned()).as("KV entries scanned over %d edges at N=%d", EDGES, n).isZero();
            assertThat(cost.lastResultSize()).isEqualTo(COMMUNITY_SIZE);
        }

        for (var cost : costs) {
            assertThat(cost.candidates()).as("candidate set copied per edge is the community, not the KV")
                      .isEqualTo(COMMUNITY_SIZE);
            assertThat(cost.aliveTested()).as("alive members tested over the wave").isEqualTo((long) EDGES * (CORES + COMMUNITY_SIZE));
        }
    }

    @Test
    void control_rebuildingTheIndexFromASnapshotIsOneCallThatScansEveryEntry() {
        for (var n : SIZES) {
            var kv = new CountingKVStore(CommunityMemberDirectory.communityMemberDirectory());

            for (int i = 0; i < n; i++) {
                kv.commit(nodeId(i), "c-" + i / COMMUNITY_SIZE);
            }

            kv.zeroCounters();
            CommunityMemberDirectory.communityMemberDirectory().restore(kv.snapshot());

            assertThat(kv.snapshotCalls.get()).isEqualTo(1);
            assertThat(kv.entriesScanned.get()).isEqualTo(n);
        }
    }

    private static EdgeCost edges(int n) {
        var directory = CommunityMemberDirectory.communityMemberDirectory();
        var kv = new CountingKVStore(directory);

        for (int i = 0; i < n; i++) {
            kv.commit(nodeId(i), "c-" + i / COMMUNITY_SIZE);
        }

        var view = aliveView();
        kv.zeroCounters();
        long aliveTested = 0;
        int last = 0;

        for (int edge = 0; edge < EDGES; edge++) {
            aliveTested += view.size();
            last = CommunityMembershipFilter.communityAliveMembers(view, directory, OBSERVED_COMMUNITY).size();
        }

        return new EdgeCost(kv.snapshotCalls.get(),
                            kv.entriesScanned.get(),
                            aliveTested,
                            directory.governorCandidates(OBSERVED_COMMUNITY).size(),
                            last);
    }

    /// The observer's SWIM view at its scope (H12): the cores plus the members of its own community.
    private static List<SwimMember> aliveView() {
        var view = new ArrayList<SwimMember>();

        for (int i = 0; i < CORES; i++) {
            view.add(alive(NodeId.nodeId("core-" + i).unwrap()));
        }

        for (int i = 0; i < COMMUNITY_SIZE; i++) {
            view.add(alive(nodeId(i)));
        }

        return view;
    }

    private static NodeId nodeId(int i) {
        return NodeId.nodeId("worker-" + i).unwrap();
    }

    private static SwimMember alive(NodeId id) {
        return SwimMember.swimMember(id, SwimMember.MemberState.ALIVE, 0, new InetSocketAddress("127.0.0.1", 0));
    }

    /// A real [KVStore] whose committed directive notifications feed the directory exactly as `AetherNode`
    /// does, with `snapshot()` instrumented: every call and every entry it hands out is counted.
    private static final class CountingKVStore extends KVStore<AetherKey, AetherValue> {
        final AtomicLong snapshotCalls = new AtomicLong();
        final AtomicLong entriesScanned = new AtomicLong();

        private CountingKVStore(CommunityMemberDirectory directory) {
            super(router(directory), stubSerializer(), stubDeserializer());
        }

        private static MessageRouter router(CommunityMemberDirectory directory) {
            var router = MessageRouter.mutable();

            router.addRoute(ValuePut.class,
                            (ValuePut<AetherKey, AetherValue> put) -> {
                                if (put.cause().key() instanceof AetherKey.ActivationDirectiveKey key
                                    && put.cause().value() instanceof ActivationDirectiveValue value) {
                                    directory.put(key.nodeId(), value);
                                }
                            });

            return router;
        }

        private static Serializer stubSerializer() {
            return new Serializer() {
                @Override public <T> void write(ByteBuf buffer, T value) {}
            };
        }

        private static Deserializer stubDeserializer() {
            return new Deserializer() {
                @Override public <T> T read(ByteBuf buffer) { return null; }
            };
        }

        void commit(NodeId nodeId, String communityId) {
            process(createBatch(List.of(new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(nodeId),
                                                            (AetherValue) ActivationDirectiveValue.worker(communityId, "")))));
        }

        void zeroCounters() {
            snapshotCalls.set(0);
            entriesScanned.set(0);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            snapshotCalls.incrementAndGet();
            var copy = super.snapshot();

            entriesScanned.addAndGet(copy.size());

            return copy;
        }
    }
}
