package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.DHTMessage.Readiness;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 x #1818 (v1820, merge verification): a catch-up pull whose copy the store keeps out under per-key
/// EPOCH ordering — the local entry is of a newer owner epoch but a lower HLC version, which a new owner's HLC
/// may legitimately be — is superseded, not refused (#1818's honest ack counts it applied). Readback must agree,
/// or the partition can never complete while any source holds the older-epoch copy.
class DHTCatchUpEpochReadbackTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);

    /// Epoch ordering on (as the production high-water gate has it), nothing stale.
    private static final OwnerEpochGate ORDERED = new OwnerEpochGate() {
        @Override
        public boolean isStale(byte[] key, long incarnation, long term, long counter) {
            return false;
        }

        @Override
        public void advance(byte[] key, long incarnation, long term, long counter) {}
    };

    @Test
    void pulledCopy_supersededByANewerEpochEntry_completesTheCatchUp() {
        var ids = List.of(new NodeId("node-0"), new NodeId("node-1"), new NodeId("node-2"));
        var nodes = new LinkedHashMap<NodeId, DHTNode>();
        var antiEntropies = new LinkedHashMap<NodeId, DHTAntiEntropy>();

        ids.forEach(id -> {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ids.forEach(ring::addNode);
            nodes.put(id, dhtNode(id, memoryStorageEngine(ORDERED), ring, CONFIG));
        });
        ids.forEach(id -> antiEntropies.put(id, dhtAntiEntropy(nodes.get(id), (target, message) -> route(nodes, antiEntropies, target, message), CONFIG)));

        var booter = ids.getFirst();
        var key = "epoch-readback".getBytes(StandardCharsets.UTF_8);
        var partition = nodes.get(booter).partitionFor(key);

        nodes.get(booter).beginCatchUp();
        // The sources still hold the deposed owner's copy: older epoch, higher HLC version.
        ids.stream().filter(id -> !id.equals(booter))
           .forEach(id -> nodes.get(id).putLocalVersioned(key, "old".getBytes(StandardCharsets.UTF_8), 200L, 0L, 1L, 1L).await());
        // The new owner wrote the key here at a newer epoch with a lower HLC version.
        nodes.get(booter).putLocalVersioned(key, "new".getBytes(StandardCharsets.UTF_8), 100L, 0L, 2L, 2L).await();

        for (int round = 0; round < 3; round++) {
            antiEntropies.get(booter).catchUpNow();
        }

        assertThat(new String(nodes.get(booter).getLocal(key).await().or(Option.none()).or(new byte[0]), StandardCharsets.UTF_8))
            .as("control: epoch ordering kept the newer-epoch entry").isEqualTo("new");
        assertThat(nodes.get(booter).readiness(partition)).as("a superseded copy must not block completion")
                                                           .isEqualTo(Readiness.SERVING);
    }

    private static void route(Map<NodeId, DHTNode> nodes, Map<NodeId, DHTAntiEntropy> antiEntropies, NodeId target, ProtocolMessage message) {
        var node = nodes.get(target);
        var antiEntropy = antiEntropies.get(target);

        switch (message) {
            case DHTMessage.DigestRequest r -> node.handleDigestRequest(r, resp -> route(nodes, antiEntropies, r.sender(), resp));
            case DHTMessage.DigestResponse r -> antiEntropy.onDigestResponse(r);
            case DHTMessage.MigrationDataRequest r -> node.handleMigrationDataRequest(r, resp -> route(nodes, antiEntropies, r.sender(), resp));
            case DHTMessage.MigrationDataResponse r -> antiEntropy.onMigrationDataResponse(r);
            default -> {}
        }
    }
}
