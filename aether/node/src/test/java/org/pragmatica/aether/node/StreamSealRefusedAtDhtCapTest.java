// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSink;
import org.pragmatica.aether.storage.DhtStorageTier;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.LocalDHTClient;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Cause;
import org.pragmatica.storage.LocalDiskTier;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.storage.TierLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258 NT-125, the chain's end: "a no-WAL partition then refuses `SEALING_BEHIND`" once the shared tier refuses for capacity.
///
/// The real path, no stand-in for any link: a ring with no WAL and the pending-seal cap -> `SegmentSealer` -> `StorageSegmentSink`
/// -> a streams-shaped `StorageInstance` (memory, local disk, DHT) -> `DhtStorageTier` -> a DHT engine that is AT its
/// `stream-segments` cap. The engine refuses with `ReplicaFull`, the tier maps it to `TierFull(REMOTE)`, the required tier fails
/// the seal and undoes its local-disk copy, the sealer keeps the segments, and once they reach its cap the ring refuses the
/// append that needs their room with `SEALING_BEHIND`. Lives in `aether/node`, the one module that sees all of those layers.
class StreamSealRefusedAtDhtCapTest {
    private static final String STREAM = "capped-stream";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 4;
    /// A one-event segment of a 3-byte payload is 23 bytes, so a 64-byte cap admits three pending segments and refuses the fourth.
    private static final long PENDING_CAP_BYTES = 64;
    private static final long DHT_CAP_BYTES = 10;
    private static final long AWAIT_MS = 10_000;

    @TempDir
    Path dir;

    @Test
    void aNoWalPartition_whoseSealsTheFullDhtTierRefuses_refusesTheAppendWithSealingBehind_andNothingIsSealedOrLeftOnDisk() {
        var engine = memoryStorageEngine(OwnerEpochGate.noOp(), DhtPrefixAccounting.dhtPrefixAccounting(DHT_CAP_BYTES));
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(new NodeId("n1"));
        var client = LocalDHTClient.localDHTClient(dhtNode(new NodeId("n1"), engine, ring, DHTConfig.SINGLE_NODE));

        client.put("stream-segments/prefill".getBytes(StandardCharsets.UTF_8), new byte[(int) DHT_CAP_BYTES])
              .await()
              .onFailure(cause -> fail("the prefill up to the cap must be accepted: " + cause.message()));
        assertThat(engine.usedBytes("stream-segments")).as("precondition: the node is exactly at its cap").isEqualTo(DHT_CAP_BYTES);

        var disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1 << 20).unwrap();
        var storage = StorageInstance.storageInstance("streams",
                                                      List.of(MemoryTier.memoryTier(1 << 20),
                                                              disk,
                                                              DhtStorageTier.dhtStorageTier(client, "stream-segments")),
                                                      MetadataStore.inMemoryMetadataStore("streams"));
        var index = new SegmentIndex();
        var sinkFailures = new CopyOnWriteArrayList<Cause>();
        var realSink = storageSegmentSink(storage, index);
        SegmentSink recording = (segment, log) -> realSink.seal(segment, log).onFailure(sinkFailures::add);
        var sealer = segmentSealer(recording, PENDING_CAP_BYTES);

        try (var buffer = OffHeapRingBuffer.offHeapRingBuffer(STREAM, PARTITION, RING_EVENTS, 4096, sealer)) {
            for (int i = 0; i < RING_EVENTS + 3; i++) {
                buffer.append(("e-" + i).getBytes(), 1000L + i).onFailure(cause -> fail(cause.message()));
            }
            awaitCondition("the first seal fails against the full DHT tier", () -> !sinkFailures.isEmpty());

            buffer.append("e-7".getBytes(), 1007L)
                  .onSuccess(offset -> fail("expected SEALING_BEHIND, appended at " + offset))
                  .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.SEALING_BEHIND));

            assertThat(sealer.refusalCount()).isEqualTo(1L);
            assertThat(sinkFailures.getFirst()).as("the seal failed because the shared tier is full, not for another reason")
                                               .isInstanceOfSatisfying(StorageError.TierFull.class,
                                                                       full -> assertThat(full.tier()).isEqualTo(TierLevel.REMOTE));
            assertThat(index.findSegment(STREAM, PARTITION, 0).isEmpty()).as("no segment was sealed").isTrue();
            assertThat(disk.usedBytes()).as("the failed seals left no local-disk copy").isZero();
            assertThat(buffer.eventCount()).as("the ring kept its events").isEqualTo((long) RING_EVENTS);
        } finally {
            sealer.onStreamDeleted(STREAM);
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void awaitCondition(String what, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }

            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for: " + what, e);
            }
        }

        throw new AssertionError("not within " + AWAIT_MS + "ms: " + what);
    }
}
