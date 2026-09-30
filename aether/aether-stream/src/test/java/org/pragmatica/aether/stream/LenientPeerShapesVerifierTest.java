// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1555, adopted from verifier v1555's R3 shapes (assertions verbatim). Each shape builds real rings, promotes one candidate
/// through OwnerActivation, and asserts the one property that matters: the candidate NEVER activates holding a
/// record that is not on the acked lineage ("acked-*"), and never activates while missing an acked record.
/// Refusal (no activation, block reported) is always acceptable; a control shape proves activation is reachable.
class LenientPeerShapesVerifierTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private final List<StreamPartitionManager> opened = new ArrayList<>();
    private final List<OwnerActivation.ActivationBlock> alarms = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(StreamPartitionManager::close);
    }

    /// R3: the CANDIDATE is the returning divergent node and the highest head, so there is no catch-up source and
    /// every peer is "lower" and checked leniently against the candidate's own log. The candidate's local compare
    /// reads its RING only (AetherNode.readLocalRange clamps to the ring tail); its ring starts at 13, above the
    /// lower acked peer's head 12, so nothing is compared and the divergent candidate activates over acked 11'..12'.
    @Test
    void divergentCandidate_ownRingEvictedBelowLowerPeerHead() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        var d = ring(11, "divergent-d", 5);
        rings.put(id("d"), d);
        rings.put(id("c"), ring(11, "acked", 2));
        assertSafe(promote(id("d"), rings, Map.of(id("d"), 13L)), d, 2);
    }

    /// Control for R3: identical, but the candidate's ring still holds the window -> refused as DivergentPeer.
    @Test
    void control_divergentCandidate_windowHeld_refuses() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        var d = ring(11, "divergent-d", 5);
        rings.put(id("d"), d);
        rings.put(id("c"), ring(11, "acked", 2));
        assertThat(promote(id("d"), rings, Map.of())).as("control refuses").isFalse();
        assertThat(alarms).isNotEmpty();
    }

    /// R3 with three nodes: the divergent candidate is highest; one lower peer is acked, one lags on the prefix.
    @Test
    void divergentCandidate_ownRingEvicted_twoLowerPeers() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        var d = ring(11, "divergent-d", 8);
        rings.put(id("d"), d);
        rings.put(id("c"), ring(11, "acked", 3));
        rings.put(id("b"), ring(11, "x", 0));
        assertSafe(promote(id("d"), rings, Map.of(id("d"), 15L)), d, 3);
    }

    /// Attack (1) as briefed: a LOWER non-source peer holds a divergent record in a range the SOURCE has evicted.
    /// Candidate lags at 10; source A (acked lineage, head 16) retains only 14..; lower peer B holds a divergent
    /// record at 11 (head 11 > candidate head 10, so B is compared pairwise with A, over a window A evicted).
    @Test
    void sourceEvicted_peerAboveCandidateDivergentInEvictedRange() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        rings.put(id("a"), ring(11, "acked", 6));
        rings.put(id("b"), ring(11, "divergent-b", 1));
        var d = ring(11, "x", 0);
        rings.put(id("d"), d);
        assertSafe(promote(id("d"), rings, Map.of(id("a"), 14L)), d, 6);
    }

    /// Healthy: the candidate is highest on the acked lineage with a short ring; a lower peer lags on the same
    /// lineage below the candidate's ring tail. Activation is the right answer (reported, not asserted).
    @Test
    void healthy_ackedCandidateShortRing_laggingPeer_activates() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        var d = ring(11, "acked", 5);
        rings.put(id("d"), d);
        rings.put(id("c"), ring(11, "acked", 1));
        var activated = promote(id("d"), rings, Map.of(id("d"), 14L));
        assertThat(activated).as("healthy short-ring candidate activates").isTrue();
    }

    private void assertSafe(boolean activated, StreamPartitionManager candidate, int ackedCount) {
        var held = payloads(candidate);
        if (activated) {
            assertThat(held).as("activated holding a non-acked record").allMatch(p -> p.startsWith("common") || p.startsWith("acked"));
            for (var i = 0; i < ackedCount; i++) {
                assertThat(held).as("activated without acked-%d", i).contains("acked-" + i);
            }
        }
    }

    private boolean promote(NodeId self, Map<NodeId, StreamPartitionManager> rings, Map<NodeId, Long> oldest) {
        var promoted = rings.get(self);
        var record = Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(self, Epoch.epoch(0L, 3, 0), 3, HlcTimestamp.ZERO));
        var real = PromotionTestRanges.over(rings);
        // `oldest` is what a copy's catch-up read can no longer serve. For a PEER that is its ring-and-tier retention.
        // For the CANDIDATE it is only its ring tail: the gate reads the candidate's own window through ring AND
        // tier (CatchupRead, pinned in ReplicaCatchupTierFallbackTest and the node wiring pin), and the tier holds
        // what the ring evicted, so the candidate's read is not clamped. v1555's original harness clamped the
        // candidate at its ring tail, which is exactly the ring-only read R3 fixed.
        OwnerActivation.RecordRange ranges = (node, stream, partition, from, to) -> {
            var start = node.equals(self) ? from : Math.max(from, oldest.getOrDefault(node, 0L));
            return start > to
                   ? Promise.success(List.<OffHeapRingBuffer.RawEvent>of())
                   : real.read(node, stream, partition, start, to);
        };
        var gate = OwnerActivation.ownerActivation(self,
                                                   (_, _) -> record,
                                                   (_, _) -> true,
                                                   Option.some((_, _) -> Promise.success(Unit.unit())),
                                                   () -> List.copyOf(rings.keySet()),
                                                   (node, _, _) -> Promise.success(head(rings.get(node))),
                                                   (_, _) -> head(promoted),
                                                   (stream, partition, source, tail) -> copyFrom(rings.get(source), promoted, tail),
                                                   () -> true,
                                                   ranges,
                                                   block -> {
                                                       alarms.add(block);
                                                       return Unit.unit();
                                                   },
                                                   PromotionTestRanges.NEVER_ALARM);
        promoted.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        promoted.ownerServeGate(gate::admit);
        promoted.ownerBlockSource(gate::blockOf);
        assertThat(eventually(() -> promoted.mayServeAsOwner(STREAM, PARTITION) || gate.blockOf(STREAM, PARTITION).isPresent())).isTrue();
        return promoted.mayServeAsOwner(STREAM, PARTITION);
    }

    private static NodeId id(String s) {
        return new NodeId(s);
    }

    private StreamPartitionManager ring(int common, String tag, int count) {
        var m = streamPartitionManager(Long.MAX_VALUE);
        opened.add(m);
        assertThat(m.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        publishAll(m, "common", common);
        publishAll(m, tag, count);
        return m;
    }

    private static Promise<Long> copyFrom(StreamPartitionManager source, StreamPartitionManager target, long tail) {
        var from = head(target) + 1;
        source.readLocal(STREAM, PARTITION, from, (int) (tail - from + 1))
              .unwrap()
              .forEach(event -> target.appendRecovered(STREAM, PARTITION, event.offset(), event.data(), event.timestamp(), Epoch.ZERO)
                                      .unwrap());
        return Promise.success(tail);
    }

    private static long head(StreamPartitionManager manager) {
        return manager.partitionInfo(STREAM, PARTITION).map(StreamPartitionManager.PartitionInfo::headOffset).or(-1L);
    }

    private static List<String> payloads(StreamPartitionManager manager) {
        return manager.readLocal(STREAM, PARTITION, 0, 100)
                      .unwrap()
                      .stream()
                      .map(event -> new String(event.data(), StandardCharsets.UTF_8))
                      .toList();
    }

    private static void publishAll(StreamPartitionManager manager, String tag, int count) {
        for (var i = 0; i < count; i++) {
            assertThat(manager.publishLocal(STREAM, PARTITION, (tag + "-" + i).getBytes(StandardCharsets.UTF_8), 1L).isSuccess()).isTrue();
        }
    }

    private static boolean eventually(BooleanSupplier condition) {
        for (var attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            LockSupport.parkNanos(20_000_000L);
        }
        return false;
    }
}
