// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.WarningLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.DhtCapacityWarnings.dhtCapacityWarnings;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258 spec 3.2 item 8 (NT-126, the DHT codes): the node binds the engine's capacity listener to its warning sink, so the
/// engine's transitions become `STREAM_SEGMENT_DHT_NEAR_FULL` and `_FULL` once per episode, each closed by its partner, for
/// the subject `node <nodeId> dht prefix stream-segments`. `integrations/dht` itself knows no warning type.
class DhtCapacityWarningsTest {
    private static final long CAP = 1_000L;
    private static final String SUBJECT = "node n1 dht prefix stream-segments";

    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final MemoryStorageEngine engine = engine();
    private long version = 1_000L;

    private MemoryStorageEngine engine() {
        var accounting = DhtPrefixAccounting.dhtPrefixAccounting(CAP);
        var warnings = dhtCapacityWarnings("n1");

        warnings.operatorWarnings(OperatorWarningSink.handingOffTo(raised::add));
        accounting.listener(warnings);

        return memoryStorageEngine(OwnerEpochGate.noOp(), accounting);
    }

    private boolean put(String name, int size) {
        return engine.putVersioned(("stream-segments/" + name).getBytes(StandardCharsets.UTF_8), new byte[size], ++version, 0L, 0L, 0L, false)
                     .await()
                     .isSuccess();
    }

    private void remove(String name) {
        engine.removeVersioned(("stream-segments/" + name).getBytes(StandardCharsets.UTF_8), ++version, 0L, 0L, 0L).await();
    }

    private void collectTombstones() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(new NodeId("n1"));
        engine.entries()
              .await()
              .or(List.of())
              .forEach(entry -> engine.collectTombstones(ring, ring.partitionFor(entry.key()), Long.MAX_VALUE).await());
    }

    /// The sink hands warnings off on its own thread: wait until the queue has drained and nothing more arrives.
    private void awaitQuiet() {
        try {
            for (var round = 0; round < 40; round++) {
                var seen = raised.size();

                Thread.sleep(150);
                if (raised.size() == seen) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<OperatorWarningCode> codes() {
        awaitQuiet();

        return raised.stream().map(OperatorWarning::code).toList();
    }

    @Test
    void eachTransition_isRaisedOnce_andClosedByItsPartner() {
        for (var i = 1; i <= 9; i++) {
            put("k" + i, 100);
        }
        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_SEGMENT_DHT_NEAR_FULL);
        assertThat(raised.getFirst().subject()).isEqualTo(SUBJECT);
        assertThat(raised.getFirst().code().level()).isEqualTo(WarningLevel.WARNING);

        put("k10", 100);
        put("k11", 100);
        put("k12", 100);
        put("k13", 100);
        assertThat(codes()).as("MT-54: three refusals are one FULL")
                           .containsExactly(OperatorWarningCode.STREAM_SEGMENT_DHT_NEAR_FULL,
                                            OperatorWarningCode.STREAM_SEGMENT_DHT_FULL);
        assertThat(raised.getLast().code().level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(raised.getLast().subject()).isEqualTo(SUBJECT);
        assertThat(raised.getLast().message()).as("names the cap, the usage and the refused size")
                                              .contains("1000")
                                              .contains("100");

        remove("k10");
        remove("k9");
        collectTombstones();
        remove("k8");

        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_SEGMENT_DHT_NEAR_FULL,
                                            OperatorWarningCode.STREAM_SEGMENT_DHT_FULL,
                                            OperatorWarningCode.STREAM_SEGMENT_DHT_FULL_CLEARED,
                                            OperatorWarningCode.STREAM_SEGMENT_DHT_NEAR_FULL_CLEARED);
        assertThat(raised.getLast().subject()).isEqualTo(SUBJECT);
        assertThat(raised.getLast().code().level()).isEqualTo(WarningLevel.INFO);
    }

    @Test
    void furtherPutsInTheSameState_raiseNothing() {
        for (var i = 1; i <= 9; i++) {
            put("k" + i, 100);
        }
        put("k1", 100);
        put("k2", 100);
        put("extra", 10);

        assertThat(codes()).containsExactly(OperatorWarningCode.STREAM_SEGMENT_DHT_NEAR_FULL);
    }
}
