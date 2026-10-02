// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.net.InetSocketAddress;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Pins the PER-EDGE cost of `CommunityMembershipFilter` as driven by `AetherNode` (#1840): the worker
/// re-runs the filter on every SWIM observation edge, and the filter rebuilds the community node-id set
/// from the WHOLE committed KV snapshot each time. So one edge costs O(alive + KV entries), and a wave of
/// E edges at one observer scans E x KV entries.
///
/// The fixture delivers all N joins to a single observer with an unscoped alive view, so its N(N+1)/2
/// total is a property of the fixture, not a claim about a deployed worker: SWIM membership is
/// community-scoped there, so a real join wave has fewer in-scope edges. The claim is the per-edge cost.
///
/// The primary measure is deterministic: entries the filter streams out of the KV snapshot (a counting
/// double) plus alive members tested. Wall time is printed for indication only and never asserted.
///
/// The exact-count assertions are an ENABLED TRIPWIRE for the current behaviour. If one reddens, the
/// filter stopped rescanning the KV per edge (the #1840 incremental/indexed fix): replace the bound with
/// the new one rather than loosening it blindly.
class CommunityMembershipFilterPerEdgeCostTest {
    private static final int COMMUNITY_SIZE = 10;
    private static final String OBSERVED_COMMUNITY = "c-0";

    private static final int[] SIZES = {200, 1_000, 10_000};

    private record WaveCost(long kvEntriesScanned,
                            long aliveTested,
                            long snapshotCalls,
                            long millis,
                            int lastResultSize) {
        long totalOps() {
            return kvEntriesScanned + aliveTested;
        }
    }

    @Test
    void edgeWave_everyEdgeScansWholeKv_tripwireForIncrementalFilter() {
        var costs = new ArrayList<WaveCost>();

        System.out.println("N | edges | kvEntriesScanned | aliveTested | totalOps | ms");
        for (var n : SIZES) {
            var cost = joinWave(n);

            costs.add(cost);
            System.out.printf("%d | %d | %d | %d | %d | %d%n",
                              n,
                              n,
                              cost.kvEntriesScanned(),
                              cost.aliveTested(),
                              cost.totalOps(),
                              cost.millis());
        }

        for (int i = 0; i < SIZES.length; i++) {
            long n = SIZES[i];
            long triangular = n * (n + 1) / 2;
            var cost = costs.get(i);

            assertThat(cost.snapshotCalls()).as("one full KV snapshot per edge at N=%d", n).isEqualTo(n);
            assertThat(cost.kvEntriesScanned()).as("KV entries scanned over %d edges whose KV grows 1..%d: each edge scans the whole KV (sum_{i=1..N} i). "
                                                  + "If this reddens the filter is no longer a per-edge rescan (#1840 incremental fix): "
                                                  + "update this bound, do not loosen it.",
                                                   n,
                                                   n)
                      .isEqualTo(triangular);
            assertThat(cost.aliveTested()).isEqualTo(triangular);
            assertThat(cost.lastResultSize()).isEqualTo(COMMUNITY_SIZE);
        }

        for (int i = 1; i < SIZES.length; i++) {
            double nRatio = (double) SIZES[i] / SIZES[i - 1];
            double opsRatio = (double) costs.get(i).totalOps() / costs.get(i - 1).totalOps();

            System.out.printf("N x%.0f -> ops x%.2f (E x KV predicts x%.0f)%n", nRatio, opsRatio, nRatio * nRatio);
            assertThat(opsRatio).as("growth ratio N=%d -> N=%d", SIZES[i - 1], SIZES[i])
                      .isBetween(nRatio * nRatio * 0.95, nRatio * nRatio * 1.05);
        }
    }

    @Test
    void control_singleFilterCall_isLinearAndCounterCounts() {
        var scanned = new ArrayList<Long>();

        for (var n : SIZES) {
            var kv = new CountingKVStore();
            var alive = new ArrayList<SwimMember>();

            for (int i = 0; i < n; i++) {
                kv.put(nodeId(i), "c-" + i / COMMUNITY_SIZE);
                alive.add(alive(nodeId(i)));
            }

            var result = CommunityMembershipFilter.communityAliveMembers(alive, kv, OBSERVED_COMMUNITY);
            // The counter counts: exactly the N entries the filter streamed, and a known result size.
            assertThat(kv.entriesScanned.get()).isEqualTo(n);
            assertThat(kv.snapshotCalls.get()).isEqualTo(1);
            assertThat(result).hasSize(COMMUNITY_SIZE);
            scanned.add(kv.entriesScanned.get());
        }
        // Known-linear operation measures linear: x5 members -> x5 entries, x10 -> x10.
        assertThat(scanned.get(1)).isEqualTo(scanned.get(0) * 5);
        assertThat(scanned.get(2)).isEqualTo(scanned.get(1) * 10);
    }

    private static WaveCost joinWave(int n) {
        var kv = new CountingKVStore();
        var members = new ArrayList<SwimMember>(n);
        int lastSize = 0;
        long aliveTested = 0;
        long start = System.nanoTime();

        for (int i = 0; i < n; i++) {
            // Edge i: committed directive lands, member turns ALIVE, observer re-filters the full view (one KV scan).
            kv.put(nodeId(i), "c-" + i / COMMUNITY_SIZE);
            members.add(alive(nodeId(i)));
            aliveTested += members.size();
            lastSize = CommunityMembershipFilter.communityAliveMembers(List.copyOf(members),
                                                                       kv,
                                                                       OBSERVED_COMMUNITY)
                                                .size();
        }

        long millis = (System.nanoTime() - start) / 1_000_000;

        return new WaveCost(kv.entriesScanned.get(), aliveTested, kv.snapshotCalls.get(), millis, lastSize);
    }

    private static NodeId nodeId(int i) {
        return NodeId.nodeId("worker-" + i).unwrap();
    }

    private static SwimMember alive(NodeId id) {
        return SwimMember.swimMember(id, SwimMember.MemberState.ALIVE, 0, new InetSocketAddress("127.0.0.1", 0));
    }

    /// Mirrors `KVStore.snapshot()` (a full `Map.copyOf` per call) and counts every entry the
    /// caller streams out of the returned view, which is the filter's only access path.
    private static final class CountingKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();
        final AtomicLong entriesScanned = new AtomicLong();
        final AtomicLong snapshotCalls = new AtomicLong();

        private CountingKVStore() {
            super(null, null, null);
        }

        void put(NodeId nodeId, String communityId) {
            storage.put(new AetherKey.ActivationDirectiveKey(nodeId), ActivationDirectiveValue.worker(communityId, ""));
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            snapshotCalls.incrementAndGet();
            var copy = Map.copyOf(storage);

            return new AbstractMap<>() {
                @Override
                public Set<Entry<AetherKey, AetherValue>> entrySet() {
                    return new AbstractSet<>() {
                        @Override
                        public Iterator<Entry<AetherKey, AetherValue>> iterator() {
                            return copy.entrySet()
                                       .iterator();
                        }

                        @Override
                        public int size() {
                            return copy.size();
                        }

                        @Override
                        public Stream<Entry<AetherKey, AetherValue>> stream() {
                            return copy.entrySet()
                                       .stream()
                                       .peek(_ -> entriesScanned.incrementAndGet());
                        }
                    };
                }
            };
        }
    }
}
