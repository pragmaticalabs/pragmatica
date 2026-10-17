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

/// #1555, adopted from verifier v1555's promotion-gate shapes (assertions verbatim). Each shape builds real rings, promotes one candidate
/// through OwnerActivation, and asserts the one property that matters: the candidate NEVER activates holding a
/// record that is not on the acked lineage ("acked-*"), and never activates while missing an acked record.
/// Refusal (no activation, block reported) is always acceptable; a control shape proves activation is reachable.
class PromotionGateShapesVerifierTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private final List<StreamPartitionManager> opened = new ArrayList<>();
    private final List<OwnerActivation.ActivationBlock> alarms = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(StreamPartitionManager::close);
    }

    /// 4 nodes, three lineages above the common prefix; the candidate holds only the prefix.
    @Test
    void threeLineages_lowCandidate() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        rings.put(id("a"), ring(11, "divergent-a", 5));
        rings.put(id("b"), ring(11, "divergent-b", 3));
        rings.put(id("c"), ring(11, "acked", 2));
        rings.put(id("d"), ring(11, "x", 0));
        assertSafe(promote(id("d"), rings, Set.of()), rings.get(id("d")), 2);
    }

    /// The candidate is AHEAD of the acked member on the same lineage, and BEHIND a divergent member.
    @Test
    void candidateAheadOfOneLineageBehindTheOther() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        rings.put(id("a"), ring(11, "divergent-a", 6));
        rings.put(id("c"), ring(11, "acked", 2));
        var d = ring(11, "acked", 4);
        rings.put(id("d"), d);
        assertSafe(promote(id("d"), rings, Set.of()), d, 4);
    }

    /// The candidate is AHEAD of a divergent member and BEHIND the acked member.
    @Test
    void candidateAheadOfDivergentBehindAcked() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        rings.put(id("a"), ring(11, "divergent-a", 1));
        rings.put(id("c"), ring(11, "acked", 5));
        var d = ring(11, "acked", 3);
        rings.put(id("d"), d);
        assertSafe(promote(id("d"), rings, Set.of()), d, 5);
    }

    /// The divergent SOURCE has "evicted" the shared window: its range read answers nothing, exactly as the
    /// production peer read does on CursorExpired (AetherNode.nothingWhenExpired).
    @Test
    void divergentSourceEvictedTheSharedWindow_lowCandidate() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        rings.put(id("a"), ring(11, "divergent-a", 5));
        rings.put(id("c"), ring(11, "acked", 2));
        rings.put(id("d"), ring(11, "x", 0));
        assertSafe(promote(id("d"), rings, Set.of(id("a"))), rings.get(id("d")), 2);
    }

    /// Same, but the candidate itself holds the acked lineage and the divergent source's window is evicted.
    @Test
    void divergentSourceEvictedTheSharedWindow_ackedCandidate() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        rings.put(id("a"), ring(11, "divergent-a", 5));
        var d = ring(11, "acked", 2);
        rings.put(id("d"), d);
        assertSafe(promote(id("d"), rings, Set.of(id("a"))), d, 2);
    }

    /// Control: four nodes all on the acked lineage at different heads; the low candidate must ACTIVATE.
    @Test
    void control_oneLineage_activatesHoldingEverything() {
        var rings = new LinkedHashMap<NodeId, StreamPartitionManager>();
        rings.put(id("a"), ring(11, "acked", 5));
        rings.put(id("b"), ring(11, "acked", 3));
        rings.put(id("c"), ring(11, "acked", 1));
        var d = ring(11, "acked", 0);
        rings.put(id("d"), d);
        assertThat(promote(id("d"), rings, Set.of())).as("control activates").isTrue();
        assertThat(payloads(d)).containsSubsequence("acked-0", "acked-4");
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

    private boolean promote(NodeId self, Map<NodeId, StreamPartitionManager> rings, Set<NodeId> evicted) {
        var promoted = rings.get(self);
        var record = Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(self, Epoch.epoch(0L, 3, 0), 3, HlcTimestamp.ZERO));
        var real = PromotionTestRanges.over(rings);
        OwnerActivation.RecordRange ranges = (node, stream, partition, from, to) -> evicted.contains(node)
                                                                                   ? Promise.success(List.<OffHeapRingBuffer.RawEvent>of())
                                                                                   : real.read(node, stream, partition, from, to);
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
