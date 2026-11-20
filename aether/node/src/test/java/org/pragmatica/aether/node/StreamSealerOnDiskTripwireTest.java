// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.pragmatica.aether.node.assembly.StreamAssembly;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.segment.SegmentProvenance;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.BlockId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/// Tripwire for #3515, stated from one invariant: a sealed segment that production writes at this release is
/// uncompressed and unencrypted ON DISK. The sealed-segment path has four silent fallbacks (compress, decompress, unknown
/// codec ordinal, decrypt) that return wrong bytes instead of a typed error; they are unreachable only while that holds.
///
/// The sealer is the one `StreamAssembly.streamReplication` hands to the partition manager (captured at that call, with
/// the default profile and no system property set), records are evicted into it, a seal is forced, and the block file the
/// disk tier holds is read as raw bytes: it must start with the plain identity header (an LZ4-framed or encrypted block
/// does not) and end with the plain serialized records. Nothing here reads a field of the sink or the text of a source.
class StreamSealerOnDiskTripwireTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long FIRST_OFFSET = 10L;
    private static final String MESSAGE = "#3515: the sealed segment on disk is not plain -- a compression codec or an encryptor "
                                          + "is wired into the production sealer. Wire the fallbacks first: make compress, decompress, "
                                          + "unknown codec ordinal and decrypt return typed errors instead of wrong bytes; only then "
                                          + "change this test";

    @TempDir
    Path dir;

    @Test
    void theSegmentTheProductionSealerWritesToDisk_isPlain() throws IOException {
        var setup = StorageFactory.defaultStreamStorage(Option.none(), dir, "test-node").unwrap();
        var sealer = productionSealer(setup);
        var events = records();

        sealer.onEviction(STREAM, PARTITION, events);
        sealer.flushAll().await().unwrap();

        var id = setup.instance().resolveRef("streams/" + STREAM + "/" + PARTITION + "/" + FIRST_OFFSET + "-" + (FIRST_OFFSET + 2)).unwrap();
        var raw = Files.readAllBytes(blockFile(id));
        var plain = plainEvents(events);

        assertThat(SegmentProvenance.isIdentified(raw)).as(MESSAGE).isTrue();
        assertThat(SegmentProvenance.split(raw).unwrap().events()).as(MESSAGE).isEqualTo(plain);
        assertThat(raw).as(MESSAGE).endsWith(plain);
    }

    /// The `StreamAssembly` entry point the node uses, with the default profile: the sealer it builds is read off the call that
    /// hands it to the partition manager, so what is tested is the instance production wires, not one the test builds.
    @SuppressWarnings("unchecked")
    private SegmentSealer productionSealer(StorageFactory.StorageSetup setup) {
        var captured = new AtomicReference<SegmentSealer>();
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(), Option.none(), ConfigurationProvider.builder().build(), dir);

        try (var _ = Mockito.mockStatic(StreamPartitionManager.class, capturingTheSealer(captured))) {
            StreamAssembly.streamReplication(new StreamAssembly.StreamReplicationInputs(config,
                                                                                         null,
                                                                                         null,
                                                                                         mock(RabiaNode.class),
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         new StreamMemoryBudget(128L << 20, 128L << 20),
                                                                                         setup,
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         null,
                                                                                         null));
        } catch (SealerCaptured expected) {
            // the assembly reached the partition manager with its sealer; nothing after that call is needed
        }
        assertThat(captured.get()).as("control: the assembly must hand its sealer to the partition manager, or this test proves nothing").isNotNull();
        return captured.get();
    }

    private static Answer<Object> capturingTheSealer(AtomicReference<SegmentSealer> captured) {
        return invocation -> capture(captured, invocation.getRawArguments());
    }

    private static Object capture(AtomicReference<SegmentSealer> captured, Object[] arguments) {
        Arrays.stream(arguments)
              .filter(SegmentSealer.class::isInstance)
              .findFirst()
              .ifPresent(sealer -> captured.set((SegmentSealer) sealer));
        throw new SealerCaptured();
    }

    private static List<RawEvent> records() {
        return List.of(RawEvent.rawEvent(FIRST_OFFSET, "alpha-record".getBytes(), 1_000L),
                       RawEvent.rawEvent(FIRST_OFFSET + 1, "bravo-record-longer".getBytes(), 2_000L),
                       RawEvent.rawEvent(FIRST_OFFSET + 2, "charlie".getBytes(), 3_000L));
    }

    /// The serialized events as the sealer lays them down: offset, timestamp, length, data, big-endian.
    private static byte[] plainEvents(List<RawEvent> events) {
        var buffer = ByteBuffer.allocate(events.stream().mapToInt(e -> 20 + e.data().length).sum()).order(ByteOrder.BIG_ENDIAN);

        events.forEach(e -> buffer.putLong(e.offset()).putLong(e.timestamp()).putInt(e.data().length).put(e.data()));
        return buffer.array();
    }

    private Path blockFile(BlockId id) throws IOException {
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().equals(id.hexString()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the disk tier holds no block file for the sealed segment: " + id.hexString()));
        }
    }

    private static final class SealerCaptured extends RuntimeException {
        SealerCaptured() {
            super("sealer captured", null, false, false);
        }
    }
}
