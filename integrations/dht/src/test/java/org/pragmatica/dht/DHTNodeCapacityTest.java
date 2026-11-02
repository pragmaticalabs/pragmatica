/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.dht;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.DHTMessage.KeyValue;
import org.pragmatica.dht.DHTMessage.PutRequest;
import org.pragmatica.dht.DHTMessage.PutResponse;
import org.pragmatica.dht.DHTMessage.RemoveRequest;
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #2258 spec 3.2 item 7 at one replica (NT-125): a client put that would take the live bytes of `stream-segments` above the
/// node's cap is refused with `capacityExceeded` and stores nothing; repair writes are accounted and never refused;
/// tombstones are never capped; other prefixes are accounted, not bounded.
class DHTNodeCapacityTest {
    private static final NodeId NODE_ID = new NodeId("node-1");
    private static final long CAP = 100L;
    private static final String SEGMENT = "stream-segments/";

    private MemoryStorageEngine storage;
    private DHTNode node;
    private long version;

    @BeforeEach
    void setUp() {
        storage = memoryStorageEngine(OwnerEpochGate.noOp(), DhtPrefixAccounting.dhtPrefixAccounting(CAP));
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(NODE_ID);
        node = dhtNode(NODE_ID, storage, ring, DHTConfig.SINGLE_NODE);
        version = 1_000L;
    }

    private static byte[] key(String name) {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    private PutResponse put(String name, int size, boolean repair) {
        var response = new AtomicReference<PutResponse>();

        node.handlePutRequest(new PutRequest("req-" + name,
                                             NODE_ID,
                                             key(name),
                                             new byte[size],
                                             ++version,
                                             0L,
                                             0L,
                                             0L,
                                             DHTNode.NO_CHANGE,
                                             repair),
                              response::set);

        return response.get();
    }

    private boolean held(String name) {
        return node.getLocal(key(name)).await().map(Option::isPresent).or(false);
    }

    private PutResponse put(String name, int size) {
        return put(name, size, false);
    }

    @Test
    void aClientPutAboveTheCap_isRefusedWithCapacityExceeded_andStoresNothing() {
        assertThat(put(SEGMENT + "a", 60).success()).isTrue();

        var refused = put(SEGMENT + "b", 60);

        assertThat(refused.success()).isFalse();
        assertThat(refused.capacityExceeded()).as("the refusal says why").isTrue();
        assertThat(refused.usedBytes()).as("the replica's live bytes at the refusal").isEqualTo(60L);
        assertThat(refused.maxBytes()).isEqualTo(CAP);
        assertThat(refused.fenced() || refused.replicationStale() || refused.fenceUnknown() || refused.writePending()).isFalse();
        assertThat(held(SEGMENT + "b")).as("nothing was stored").isFalse();
        assertThat(storage.usedBytes("stream-segments")).isEqualTo(60L);
        assertThat(storage.refusedPuts()).isEqualTo(1L);
    }

    @Test
    void aPutUpToExactlyTheCap_isAccepted() {
        assertThat(put(SEGMENT + "a", 60).success()).isTrue();
        assertThat(put(SEGMENT + "b", 40).success()).isTrue();
        assertThat(put(SEGMENT + "c", 1).capacityExceeded()).isTrue();
    }

    /// A retry of a seal re-puts the same block: counted as new minus old, so it adds 0 and is accepted at the cap; a
    /// replacement that grows past the cap is refused.
    @Test
    void anIdempotentRePutAtTheCap_isAccepted_aGrowingReplacementIsRefused() {
        assertThat(put(SEGMENT + "a", 100).success()).isTrue();

        var again = put(SEGMENT + "a", 100);

        assertThat(again.success()).isTrue();
        assertThat(again.capacityExceeded()).isFalse();
        assertThat(storage.usedBytes("stream-segments")).isEqualTo(100L);
        assertThat(put(SEGMENT + "a", 101).capacityExceeded()).isTrue();
        assertThat(storage.usedBytes("stream-segments")).as("the refused replacement changed nothing").isEqualTo(100L);
    }

    /// MT-51 gate: without the replica's check this put is stored.
    @Test
    void theRefusalIsTheReplicasOwn_theSameBytesAreAcceptedBelowTheCap() {
        assertThat(put(SEGMENT + "a", 60).success()).isTrue();
        assertThat(put(SEGMENT + "b", 40).success()).isTrue();
        assertThat(storage.usedBytes("stream-segments")).isEqualTo(100L);
    }

    /// NT-133: a re-homing put carries `repair = true`, and a replica accepts it above the cap, counted.
    @Test
    void aRepairPut_aboveTheCap_isStoredAndCounted() {
        assertThat(put(SEGMENT + "a", 100).success()).isTrue();

        var repair = put(SEGMENT + "b", 60, true);

        assertThat(repair.success()).isTrue();
        assertThat(repair.capacityExceeded()).isFalse();
        assertThat(storage.usedBytes("stream-segments")).as("accounted, past the cap").isEqualTo(160L);
        assertThat(put(SEGMENT + "c", 1).capacityExceeded()).as("a client put is still refused").isTrue();
    }

    /// MT-52 gate: the cap applied to `putReplica` would refuse catch-up and keep the partition catching up for ever.
    @Test
    void putReplica_aboveTheCap_isStoredAndCounted() {
        assertThat(put(SEGMENT + "a", 100).success()).isTrue();

        var stored = node.applyMigrationData(List.of(new KeyValue(key(SEGMENT + "migrated"), new byte[70], 50L, 0L, 0L, 0L)))
                         .await();

        assertThat(stored.or(false)).isTrue();
        assertThat(storage.usedBytes("stream-segments")).isEqualTo(170L);
    }

    /// Tombstones are always accepted and are not counted against the cap: they are gauged, not capped.
    @Test
    void tombstones_areAcceptedAboveTheCap_andNotCountedAgainstIt() {
        assertThat(put(SEGMENT + "a", 60).success()).isTrue();

        for (var i = 0; i < 50; i++) {
            var response = new AtomicReference<DHTMessage.RemoveResponse>();

            node.handleRemoveRequest(new RemoveRequest("rm-" + i, NODE_ID, key(SEGMENT + "gone-" + i), ++version, 0L, 0L, 0L, DHTNode.NO_CHANGE),
                                     response::set);
            assertThat(response.get().fenced() || response.get().writePending()).isFalse();
        }

        assertThat(storage.tombstoneBytes("stream-segments")).as("tombstones far above the cap").isGreaterThan(CAP);
        assertThat(put(SEGMENT + "b", 40).success()).as("they did not use up the cap").isTrue();
        assertThat(storage.usedBytes("stream-segments")).isEqualTo(100L);
    }

    @Test
    void otherPrefixes_areAccounted_notBounded() {
        assertThat(put("artifacts/big", 10_000).success()).isTrue();
        assertThat(put("namespace:key", 10_000).success()).isTrue();

        assertThat(storage.usedBytes("artifacts")).isEqualTo(10_000L);
        assertThat(storage.usedBytes("other")).isEqualTo(10_000L);
        assertThat(storage.maxBytes("stream-segments")).isEqualTo(CAP);
        assertThat(storage.maxBytes("artifacts")).isEqualTo(Long.MAX_VALUE);
    }
}
