package org.pragmatica.dht;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTMessage.KeyValue;
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 track 3: the stamp and tombstone fields survive the wire, and the anti-entropy digest tells a tombstone from
/// a value and leaves out tombstones expired by their own stamp.
class DHTTombstoneWireTest {
    private static final NodeId NODE = new NodeId("n1");
    private static final byte[] KEY = "k".getBytes(StandardCharsets.UTF_8);

    private static SliceCodec codec() {
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();

        codecs.addAll(ConsensusCodecs.CODECS);
        codecs.addAll(DhtCodecs.CODECS);

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), codecs);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T message) {
        var codec = codec();
        var buf = Unpooled.buffer();

        try {
            codec.write(buf, message);

            return (T) codec.read(buf);
        } finally {
            buf.release();
        }
    }

    @Test
    void getResponse_tombstoneAndStamp_surviveTheWire() {
        var decoded = roundTrip(new DHTMessage.GetResponse("r", NODE, Option.none(), Readiness.SERVING, true, 42L, 1L, 2L, 3L));

        assertThat(decoded.tombstone()).isTrue();
        assertThat(decoded.version()).isEqualTo(42L);
        assertThat(decoded.epochIncarnation()).isEqualTo(1L);
        assertThat(decoded.epochTerm()).isEqualTo(2L);
        assertThat(decoded.epochCounter()).isEqualTo(3L);
    }

    @Test
    void existsResponse_tombstoneAndStamp_surviveTheWire() {
        var decoded = roundTrip(new DHTMessage.ExistsResponse("r", NODE, false, Readiness.SERVING, true, 7L, 0L, 1L, 1L));

        assertThat(decoded.tombstone()).isTrue();
        assertThat(decoded.version()).isEqualTo(7L);
    }

    @Test
    void removeRequest_stamp_survivesTheWire() {
        var decoded = roundTrip(new DHTMessage.RemoveRequest("r", NODE, KEY, 99L, 4L, 5L, 6L, 11L));

        assertThat(decoded.version()).isEqualTo(99L);
        assertThat(decoded.epochIncarnation()).isEqualTo(4L);
        assertThat(decoded.epochTerm()).isEqualTo(5L);
        assertThat(decoded.epochCounter()).isEqualTo(6L);
        assertThat(decoded.replicationVersion()).as("#1777 R1c: the replication-change stamp").isEqualTo(11L);
        assertThat(decoded.key()).isEqualTo(KEY);
    }

    @Test
    void removeResponse_fenced_survivesTheWire() {
        assertThat(roundTrip(new DHTMessage.RemoveResponse("r", NODE, false, true, false)).fenced()).isTrue();
        assertThat(roundTrip(new DHTMessage.RemoveResponse("r", NODE, false, false, true)).replicationStale()).isTrue();
    }

    @Test
    void migratedKeyValue_tombstone_survivesTheWire() {
        var decoded = roundTrip(new DHTMessage.MigrationDataResponse("r",
                                                                     NODE,
                                                                     List.of(new KeyValue(KEY, new byte[0], 5L, 0L, 0L, 0L, true)),
                                                                     false,
                                                                     false));

        assertThat(decoded.entries()).singleElement().matches(KeyValue::tombstone);
    }

    @Test
    void digest_separatesATombstoneFromAnEmptyValue() {
        var stamp = HlcTimestamp.pack(System.currentTimeMillis(), 0);
        var node = node();

        assertThat(node.digestOf(List.of(new KeyValue(KEY, new byte[0], stamp, 0L, 0L, 0L, true))))
            .isNotEqualTo(node.digestOf(List.of(new KeyValue(KEY, new byte[0], stamp, 0L, 0L, 0L, false))));
    }

    /// Expiry is read from the tombstone's own stamp, so a replica that has collected it and one that has not agree.
    @Test
    void digest_leavesOutATombstoneExpiredByItsStamp() {
        var node = node();
        var expired = HlcTimestamp.pack(System.currentTimeMillis() - 2 * DHTNode.DEFAULT_TOMBSTONE_RETENTION.millis(), 0);
        var fresh = HlcTimestamp.pack(System.currentTimeMillis(), 0);

        assertThat(node.digestOf(List.of(new KeyValue(KEY, new byte[0], expired, 0L, 0L, 0L, true))))
            .isEqualTo(node.digestOf(List.of()));
        assertThat(node.digestOf(List.of(new KeyValue(KEY, new byte[0], fresh, 0L, 0L, 0L, true))))
            .isNotEqualTo(node.digestOf(List.of()));
    }

    /// A migrated tombstone supersedes the receiver's older value — the departure hand-off and the survivor
    /// rebalance carry removes, not only values.
    @Test
    void appliedMigration_tombstoneSupersedesAnOlderValue() {
        var node = node();
        var older = HlcTimestamp.pack(System.currentTimeMillis() - 1000, 0);
        var newer = HlcTimestamp.pack(System.currentTimeMillis(), 0);

        node.storage().putVersioned(KEY, "v".getBytes(StandardCharsets.UTF_8), older).await();
        node.applyMigrationData(List.of(new KeyValue(KEY, new byte[0], newer, 0L, 0L, 0L, true))).await();

        assertThat(node.getLocal(KEY).await().or(Option.none()).isPresent()).isFalse();
    }

    private static DHTNode node() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(NODE);

        return dhtNode(NODE, memoryStorageEngine(), ring, DHTConfig.SINGLE_NODE);
    }
}
