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

import io.netty.buffer.Unpooled;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.KeyStamp;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.StorageEngine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// A6 test harness: an in-process DHT cluster whose messages are delivered synchronously, every message recorded,
/// every repair message (page, push) measured with the REAL wire codec, and every engine instrumented to count the
/// calls that read values. A node listed in [#legacyNodes] behaves as an older node on the wire: it drops the
/// message types introduced by A6, exactly as `QuicLaneDataHandler` drops a wire tag it has no codec for.
final class RepairCluster {
    private static final SliceCodec CODEC = codec();

    final Map<NodeId, Member> members = new LinkedHashMap<>();
    final List<Map.Entry<NodeId, ProtocolMessage>> sent = new CopyOnWriteArrayList<>();
    /// Encoded size of every repair message carrying entries: page answers and pushes.
    final List<Integer> repairMessageBytes = new CopyOnWriteArrayList<>();
    final Set<NodeId> legacyNodes = ConcurrentHashMap.newKeySet();
    final Set<NodeId> departing = ConcurrentHashMap.newKeySet();
    final Set<NodeId> unreachable = ConcurrentHashMap.newKeySet();
    /// Directed links that drop every message, as `from>to` (an asymmetric partition).
    final Set<String> cutLinks = ConcurrentHashMap.newKeySet();
    /// The wall clock every node reads (tombstone ages, freshness): starts at real time, moved by [#advance].
    final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    final AtomicInteger droppedUnknownTypes = new AtomicInteger();
    /// Page requests delivered before exactly ONE is lost (every later one is delivered); negative: none is lost.
    final AtomicInteger pageRequestsDelivered = new AtomicInteger(-1);
    private final DHTConfig config;

    record Member(NodeId id, DHTNode node, CountingEngine engine, DHTAntiEntropy antiEntropy,
                  DHTRebalancer rebalancer, DistributedDHTClient client) {}

    RepairCluster(DHTConfig config, String... ids) {
        this.config = config;

        for (var id : ids) {
            add(new NodeId(id));
        }
    }

    private void add(NodeId id) {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        var engine = new CountingEngine();
        DHTNetwork network = (target, message) -> deliver(id, target, message);

        allIds(id).forEach(ring::addNode);

        var node = DHTNode.dhtNode(id, engine, ring, config);

        node.useWallClock(clock::get);
        var antiEntropy = DHTAntiEntropy.dhtAntiEntropy(node, network, config, departing::contains);
        var rebalancer = DHTRebalancer.dhtRebalancer(node, network, config);
        var client = DistributedDHTClient.distributedDHTClient(node, network, config);

        members.values().forEach(other -> other.node().ring().addNode(id));
        members.put(id, new Member(id, node, engine, antiEntropy, rebalancer, client));
    }

    private List<NodeId> allIds(NodeId newcomer) {
        var ids = new ArrayList<>(members.keySet());

        ids.add(newcomer);

        return ids;
    }

    Member member(String id) {
        return members.get(new NodeId(id));
    }

    /// Move every node's wall clock forward by `millis`.
    void advance(long millis) {
        clock.addAndGet(millis);
    }

    void cut(String from, String to) {
        cutLinks.add(from + ">" + to);
    }

    private void deliver(NodeId from, NodeId target, ProtocolMessage message) {
        sent.add(Map.entry(target, message));
        measure(message);

        if (unreachable.contains(target) || pageRequestLost(message) || cutLinks.contains(from.id() + ">" + target.id())) {
            return;
        }

        // an older node neither understands nor ever emits the message types A6 introduced
        if ((legacyNodes.contains(target) || legacyNodes.contains(from)) && introducedByA6(message)) {
            droppedUnknownTypes.incrementAndGet();

            return;
        }

        Option.option(members.get(target)).onPresent(member -> route(member, message));
    }

    private boolean pageRequestLost(ProtocolMessage message) {
        return message instanceof DHTMessage.MigrationPageRequest
               && pageRequestsDelivered.get() >= 0
               && pageRequestsDelivered.getAndDecrement() == 0;
    }

    private static boolean introducedByA6(ProtocolMessage message) {
        return message instanceof DHTMessage.StampDigestRequest
               || message instanceof DHTMessage.MigrationPageRequest
               || message instanceof DHTMessage.MigrationPageResponse
               || message instanceof DHTMessage.VerifiedReadRequest;
    }

    private void measure(ProtocolMessage message) {
        if (message instanceof DHTMessage.MigrationPageResponse || message instanceof DHTMessage.MigrationDataResponse) {
            repairMessageBytes.add(encodedSize(message));
        }
    }

    /// The size of `message` encoded by the real wire codec, the size the transport bounds.
    static int encodedSize(ProtocolMessage message) {
        var buf = Unpooled.buffer();

        try {
            CODEC.write(buf, message);

            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    /// `message` encoded and decoded by the real wire codec.
    static <M extends ProtocolMessage> M roundTrip(M message) {
        var buf = Unpooled.buffer();

        try {
            CODEC.write(buf, message);

            return CODEC.read(buf);
        } finally {
            buf.release();
        }
    }

    private void route(Member member, ProtocolMessage message) {
        var node = member.node();

        switch (message) {
            case DHTMessage.GetRequest r -> node.handleGetRequest(r, resp -> deliver(member.id(), r.sender(), resp));
            case DHTMessage.GetResponse r -> member.client().onGetResponse(r);
            case DHTMessage.PutRequest r -> node.handlePutRequest(r, resp -> deliver(member.id(), r.sender(), resp));
            case DHTMessage.PutResponse r -> member.client().onPutResponse(r);
            case DHTMessage.DigestRequest r -> node.handleDigestRequest(r, resp -> deliver(member.id(), r.sender(), resp));
            case DHTMessage.StampDigestRequest r -> node.handleStampDigestRequest(r, resp -> deliver(member.id(), r.sender(), resp));
            case DHTMessage.DigestResponse r -> member.antiEntropy().onDigestResponse(r);
            case DHTMessage.MigrationDataRequest r -> node.handleMigrationDataRequest(r, resp -> deliver(member.id(), r.sender(), resp));
            case DHTMessage.MigrationPageRequest r -> node.handleMigrationPageRequest(r, resp -> deliver(member.id(), r.sender(), resp));
            case DHTMessage.MigrationDataResponse r -> member.antiEntropy().onMigrationDataResponse(r);
            case DHTMessage.MigrationPageResponse r -> member.antiEntropy().onMigrationPageResponse(r);
            case DHTMessage.MigrationDataAck r -> member.rebalancer().onMigrationDataAck(r);
            case DHTMessage.ExistsRequest r -> node.handleExistsRequest(r, resp -> deliver(member.id(), r.sender(), resp));
            case DHTMessage.ExistsResponse r -> member.client().onExistsResponse(r);
            case DHTMessage.VerifiedReadRequest r -> node.handleVerifiedReadRequest(r, resp -> deliver(member.id(), r.sender(), resp));
            default -> { }
        }
    }

    <M extends ProtocolMessage> List<M> sentOfType(Class<M> type) {
        return sent.stream()
                   .map(Map.Entry::getValue)
                   .filter(type::isInstance)
                   .map(type::cast)
                   .toList();
    }

    /// `count` keys that all fall into ONE partition of `ring` — the first partition the search lands on twice.
    static List<byte[]> keysInOnePartition(ConsistentHashRing<NodeId> ring, int count, String prefix) {
        var target = ring.partitionFor(bytes(prefix + "-0"));
        var keys = new ArrayList<byte[]>();

        for (int i = 0; keys.size() < count; i++) {
            var key = bytes(prefix + "-" + i);

            if (ring.partitionFor(key).equals(target)) {
                keys.add(key);
            }
        }

        return List.copyOf(keys);
    }

    /// A value of `size` pseudo-random bytes, distinct per seed.
    static byte[] value(int size, long seed) {
        var value = new byte[size];

        new Random(seed).nextBytes(value);

        return value;
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static SliceCodec codec() {
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();

        codecs.addAll(ConsensusCodecs.CODECS);
        codecs.addAll(DhtCodecs.CODECS);

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), codecs);
    }

    /// A memory engine that counts every call returning VALUES (`get`, `getEntry`, `entries`, `entriesForPartition`)
    /// and can serve a corrupted copy of chosen keys, as bit rot on one replica would. Stamps come from the
    /// memory engine's index and are not counted: they carry no value.
    static final class CountingEngine implements StorageEngine {
        private final MemoryStorageEngine inner = memoryStorageEngine();
        /// When set, every replica copy fails to store, as a failing disk would.
        final AtomicBoolean failReplicaWrites = new AtomicBoolean();
        final AtomicInteger valueReads = new AtomicInteger();
        private final Set<String> corrupted = ConcurrentHashMap.newKeySet();

        void corrupt(byte[] key) {
            corrupted.add(Arrays.toString(key));
        }

        private byte[] served(byte[] key, byte[] value) {
            if (!corrupted.contains(Arrays.toString(key))) {
                return value;
            }

            var copy = value.clone();

            copy[0] ^= (byte) 0xFF;

            return copy;
        }

        private Option<DHTMessage.KeyValue> served(byte[] key, Option<DHTMessage.KeyValue> entry) {
            return entry.map(kv -> new DHTMessage.KeyValue(kv.key(),
                                                           kv.tombstone() ? kv.value() : served(key, kv.value()),
                                                           kv.version(),
                                                           kv.epochIncarnation(),
                                                           kv.epochTerm(),
                                                           kv.epochCounter(),
                                                           kv.tombstone()));
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            valueReads.incrementAndGet();

            return inner.get(key).map(value -> value.map(present -> served(key, present)));
        }

        @Override
        public Promise<Option<DHTMessage.KeyValue>> getEntry(byte[] key) {
            valueReads.incrementAndGet();

            return inner.getEntry(key).map(entry -> served(key, entry));
        }

        @Override
        public Promise<List<DHTMessage.KeyValue>> entries() {
            valueReads.incrementAndGet();

            return inner.entries();
        }

        @Override
        public Promise<List<DHTMessage.KeyValue>> entriesForPartition(ConsistentHashRing<?> ring, Partition partition) {
            valueReads.incrementAndGet();

            return inner.entriesForPartition(ring, partition);
        }

        @Override
        public Promise<List<KeyStamp>> stampsForPartition(ConsistentHashRing<?> ring, Partition partition) {
            return inner.stampsForPartition(ring, partition);
        }

        @Override
        public Promise<List<KeyStamp>> stamps() {
            return inner.stamps();
        }

        @Override
        public Promise<Boolean> removeVersioned(byte[] key, long version, long epochIncarnation, long epochTerm, long epochCounter) {
            return inner.removeVersioned(key, version, epochIncarnation, epochTerm, epochCounter);
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            return inner.put(key, value);
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return inner.remove(key);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return inner.exists(key);
        }

        @Override
        public Promise<Boolean> putVersioned(byte[] key, byte[] value, long version, long epochIncarnation, long epochTerm, long epochCounter) {
            return inner.putVersioned(key, value, version, epochIncarnation, epochTerm, epochCounter);
        }

        @Override
        public Promise<Boolean> putReplica(byte[] key, byte[] value, long version, long epochIncarnation, long epochTerm, long epochCounter) {
            return inner.putReplica(key, value, version, epochIncarnation, epochTerm, epochCounter);
        }

        @Override
        public Promise<Boolean> putReplica(DHTMessage.KeyValue entry, boolean createIfAbsent) {
            if (failReplicaWrites.get()) {
                return Causes.cause("injected store failure").promise();
            }

            return inner.putReplica(entry, createIfAbsent);
        }

        @Override
        public Promise<Integer> dropPartition(ConsistentHashRing<?> ring, Partition partition) {
            return inner.dropPartition(ring, partition);
        }

        @Override
        public Promise<Integer> collectTombstones(ConsistentHashRing<?> ring, Partition partition, long expiredAtOrBeforeMillis) {
            return inner.collectTombstones(ring, partition, expiredAtOrBeforeMillis);
        }

        @Override
        public long tombstoneCount() {
            return inner.tombstoneCount();
        }

        @Override
        public Promise<Boolean> removeIfExactly(byte[] key, long version, long epochIncarnation, long epochTerm, long epochCounter) {
            return inner.removeIfExactly(key, version, epochIncarnation, epochTerm, epochCounter);
        }

        @Override
        public Promise<Displaced> putVersionedDisplacing(byte[] key, byte[] value, long version, long epochIncarnation, long epochTerm, long epochCounter) {
            return inner.putVersionedDisplacing(key, value, version, epochIncarnation, epochTerm, epochCounter);
        }

        @Override
        public Promise<Displaced> removeVersionedDisplacing(byte[] key, long version, long epochIncarnation, long epochTerm, long epochCounter) {
            return inner.removeVersionedDisplacing(key, version, epochIncarnation, epochTerm, epochCounter);
        }

        @Override
        public Promise<Boolean> restoreIfExactly(byte[] key, long version, long epochIncarnation, long epochTerm, long epochCounter,
                                                 Option<DHTMessage.KeyValue> prior) {
            return inner.restoreIfExactly(key, version, epochIncarnation, epochTerm, epochCounter, prior);
        }

        @Override
        public boolean belowHighWater(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {
            return inner.belowHighWater(key, epochIncarnation, epochTerm, epochCounter);
        }

        @Override
        public long size() {
            return inner.size();
        }

        @Override
        public Promise<Unit> clear() {
            return inner.clear();
        }

        @Override
        public Promise<Unit> shutdown() {
            return inner.shutdown();
        }

        @Override
        public Promise<List<byte[]>> keys() {
            return inner.keys();
        }
    }

    static Set<String> keySet(List<byte[]> keys) {
        var set = new HashSet<String>();

        keys.forEach(key -> set.add(Arrays.toString(key)));

        return set;
    }
}
