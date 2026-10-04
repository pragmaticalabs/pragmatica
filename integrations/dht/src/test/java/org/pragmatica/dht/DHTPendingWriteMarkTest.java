package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// The per-key pending-write mark (v1882 r12) can never refuse a key forever, even after a begin whose end never ran
/// (v1882 F19, probe b): an expired mark is dropped, not just ignored, so a leaked count cannot keep a later, correctly paired
/// write pending past its own end.
class DHTPendingWriteMarkTest {
    private static final byte[] KEY = "leak".getBytes(StandardCharsets.UTF_8);

    @Test
    void oneLeakedBegin_doesNotKeepALaterCorrectlyPairedWritePendingPastItsEnd() throws Exception {
        var node = node();

        node.beginLocalWrite(KEY, 20_000_000L);              // leaked: its end never runs; deadline 20 ms
        Thread.sleep(40);
        boolean afterLeakDeadline = node.localWritePending(KEY);

        node.beginLocalWrite(KEY, 10_000_000_000L);          // a later write, correctly paired below; deadline 10 s
        node.endLocalWrite(KEY);
        boolean afterPairedEnd = node.localWritePending(KEY);

        assertThat(afterLeakDeadline).as("arming: the leaked mark expired by its deadline").isFalse();
        assertThat(afterPairedEnd).as("a correctly paired write is not pending after its own end").isFalse();
    }

    /// The same leak, but NO query between its expiry and the next begin, so only the begin-side reset can stop the leaked count
    /// from inflating the next mark (v1882 b2: the test above queries first, and a query alone drops the expired entry).
    @Test
    void leakedBegin_thenBeginWithoutAnInterveningQuery_isNotPendingAfterItsEnd() throws Exception {
        var node = node();

        node.beginLocalWrite(KEY, 20_000_000L);
        Thread.sleep(40);
        node.beginLocalWrite(KEY, 10_000_000_000L);
        node.endLocalWrite(KEY);

        assertThat(node.localWritePending(KEY)).as("a correctly paired write is not pending after its own end").isFalse();
    }

    @Test
    void control_aPairedBeginEnd_leavesNothingPending_andNestedWritesCount() {
        var node = node();

        node.beginLocalWrite(KEY, 10_000_000_000L);
        node.beginLocalWrite(KEY, 10_000_000_000L);
        node.endLocalWrite(KEY);

        assertThat(node.localWritePending(KEY)).as("one of two writes still pending").isTrue();

        node.endLocalWrite(KEY);

        assertThat(node.localWritePending(KEY)).as("both ended: nothing pending").isFalse();
    }

    private static DHTNode node() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        var self = new NodeId("n");

        ring.addNode(self);

        return dhtNode(self, memoryStorageEngine(), ring, DHTConfig.DEFAULT);
    }
}
