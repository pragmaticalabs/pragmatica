package org.pragmatica.dht;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.DHTTopologyListener.dhtTopologyListener;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 track 3 test harness: nodes on a synchronous in-JVM network, one shared wall clock the tests move, and a
/// set of nodes whose inbound traffic is dropped (a replica that misses a remove).
final class DurableDeleteCluster {
    record Member(NodeId id,
                  DHTNode node,
                  MemoryStorageEngine storage,
                  DHTAntiEntropy antiEntropy,
                  DHTTopologyListener listener,
                  DistributedDHTClient client) {}

    final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    final Set<NodeId> unreachable = ConcurrentHashMap.newKeySet();
    private final Map<NodeId, Member> members = new LinkedHashMap<>();

    DurableDeleteCluster(int size, DHTConfig config) {
        this(size, config, _ -> OwnerEpochGate.noOp(), _ -> OwnerEpochSource.zero());
    }

    DurableDeleteCluster(int size,
                         DHTConfig config,
                         Function<NodeId, OwnerEpochGate> gates,
                         Function<NodeId, OwnerEpochSource> epochs) {
        var ids = IntStream.range(0, size).mapToObj(i -> new NodeId("node-" + i)).toList();

        ids.forEach(id -> members.put(id, member(id, ids, config, gates.apply(id), epochs.apply(id))));
    }

    private Member member(NodeId id, List<NodeId> ids, DHTConfig config, OwnerEpochGate gate, OwnerEpochSource epochs) {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ids.forEach(ring::addNode);

        var storage = memoryStorageEngine(gate);
        var node = dhtNode(id, storage, ring, config);
        DHTNetwork network = this::deliver;
        var antiEntropy = dhtAntiEntropy(node, network, _ -> true);

        node.useWallClock(clock::get);

        return new Member(id,
                          node,
                          storage,
                          antiEntropy,
                          dhtTopologyListener(node, dhtRebalancer(node, network), antiEntropy),
                          distributedDHTClient(node, network, epochs));
    }

    Member member(NodeId id) {
        return members.get(id);
    }

    List<Member> members() {
        return List.copyOf(members.values());
    }

    List<NodeId> replicasOf(byte[] key) {
        var any = members.values().iterator().next().node();

        return any.ring().nodesFor(key, any.config().effectiveReplicationFactor(any.ring().nodeCount()));
    }

    Option<DHTMessage.KeyValue> entryAt(NodeId id, byte[] key) {
        return members.get(id).storage().getEntry(key).await().or(Option.none());
    }

    boolean holdsLive(NodeId id, byte[] key) {
        return entryAt(id, key).filter(entry -> !entry.tombstone()).isPresent();
    }

    boolean holdsTombstone(NodeId id, byte[] key) {
        return entryAt(id, key).filter(DHTMessage.KeyValue::tombstone).isPresent();
    }

    /// One periodic anti-entropy round on every member, in order.
    void synchronizeAll() {
        members.values().forEach(member -> member.antiEntropy().synchronizeNow());
    }

    void catchUpAll(int rounds) {
        for (int i = 0; i < rounds; i++) {
            members.values().forEach(member -> member.antiEntropy().catchUpNow());
        }
    }

    void advanceClockBy(long millis) {
        clock.addAndGet(millis);
    }

    static MembershipDecision.NodeRemoved removed(NodeId id) {
        return new MembershipDecision.NodeRemoved(id, List.of(), 1L, HlcTimestamp.ZERO);
    }

    static MembershipDecision.NodeJoined joined(NodeId id) {
        return new MembershipDecision.NodeJoined(id, List.of(), 2L, HlcTimestamp.ZERO);
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void deliver(NodeId target, ProtocolMessage message) {
        if (unreachable.contains(target)) {
            return;
        }

        Option.option(members.get(target)).onPresent(member -> route(member, message));
    }

    private void route(Member member, ProtocolMessage message) {
        var node = member.node();

        switch (message) {
            case DHTMessage.GetRequest r -> node.handleGetRequest(r, resp -> deliver(r.sender(), resp));
            case DHTMessage.GetResponse r -> member.client().onGetResponse(r);
            case DHTMessage.PutRequest r -> node.handlePutRequest(r, resp -> deliver(r.sender(), resp));
            case DHTMessage.PutResponse r -> member.client().onPutResponse(r);
            case DHTMessage.RemoveRequest r -> node.handleRemoveRequest(r, resp -> deliver(r.sender(), resp));
            case DHTMessage.RemoveResponse r -> member.client().onRemoveResponse(r);
            case DHTMessage.ExistsRequest r -> node.handleExistsRequest(r, resp -> deliver(r.sender(), resp));
            case DHTMessage.ExistsResponse r -> member.client().onExistsResponse(r);
            case DHTMessage.DigestRequest r -> node.handleDigestRequest(r, resp -> deliver(r.sender(), resp));
            case DHTMessage.DigestResponse r -> member.antiEntropy().onDigestResponse(r);
            case DHTMessage.MigrationDataRequest r -> node.handleMigrationDataRequest(r, resp -> deliver(r.sender(), resp));
            case DHTMessage.MigrationDataResponse r -> member.antiEntropy().onMigrationDataResponse(r);
            default -> {}
        }
    }
}
