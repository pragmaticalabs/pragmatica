package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Issue #420 — the ring has ONE placement: a key's nodes are its partition's nodes. Before, a key
/// sat at its own hash position while anti-entropy and the rebalancer worked on the partition's
/// position; on a 5-node ring the two owner sets agreed for 196 of 2,000 keys (9.8%, chance level
/// for three-of-five sets), so the repair machinery moved data among non-owners.
class PartitionPlacementTest {
    private static final int NODES = 5;
    private static final int KEYS = 2_000;
    private static final int RF = 3;

    private static ConsistentHashRing<NodeId> fiveNodeRing() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        for (int i = 0; i < NODES; i++) {
            ring.addNode(new NodeId("node-" + i));
        }
        return ring;
    }

    private static byte[] key(int i) {
        return ("k-" + i).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keyOwnersArePartitionOwners_forEveryKey() {
        var ring = fiveNodeRing();
        int agreeing = 0;

        for (int i = 0; i < KEYS; i++) {
            var key = key(i);
            var keyOwners = ring.nodesFor(key, RF);
            var partitionOwners = ring.nodesFor(ring.partitionFor(key), RF);

            assertThat(keyOwners).as("key %d: ordered owner list equals its partition's", i).isEqualTo(partitionOwners);
            assertThat(ring.primaryFor(key)).isEqualTo(ring.primaryFor(ring.partitionFor(key)));
            if (new HashSet<>(keyOwners).equals(new HashSet<>(partitionOwners))) agreeing++;
        }

        assertThat(agreeing).isEqualTo(KEYS);
    }

    @Test
    void keyOwnersRespectTheFilter_throughThePartition() {
        var ring = fiveNodeRing();
        var excluded = new NodeId("node-0");

        for (int i = 0; i < KEYS; i++) {
            var key = key(i);
            var filtered = ring.nodesFor(key, RF, n -> !n.equals(excluded));

            assertThat(filtered).doesNotContain(excluded).hasSize(RF);
            assertThat(filtered).isEqualTo(ring.nodesFor(ring.partitionFor(key), RF, n -> !n.equals(excluded)));
        }
    }

    /// Placement by 1,024 partition positions instead of per-key positions coarsens the spread;
    /// the ratio between the most and least loaded node stays within 1.5 over 2,000 keys, RF=3.
    @Test
    void loadSpreadAcrossFiveNodes_staysWithinOneAndAHalf() {
        var ring = fiveNodeRing();
        Map<NodeId, Integer> perNode = new HashMap<>();
        Map<NodeId, Integer> perNodeAsPrimary = new HashMap<>();

        for (int i = 0; i < KEYS; i++) {
            var owners = ring.nodesFor(key(i), RF);

            owners.forEach(n -> perNode.merge(n, 1, Integer::sum));
            perNodeAsPrimary.merge(owners.getFirst(), 1, Integer::sum);
        }

        var max = perNode.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        var min = perNode.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        var maxPrimary = perNodeAsPrimary.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        var minPrimary = perNodeAsPrimary.values().stream().mapToInt(Integer::intValue).min().orElseThrow();

        System.out.printf("PLACEMENT-SPREAD replicas per node=%s ratio=%.3f; primaries per node=%s ratio=%.3f%n",
                          perNode, (double) max / min, perNodeAsPrimary, (double) maxPrimary / minPrimary);
        assertThat(perNode).hasSize(NODES);
        assertThat((double) max / min).as("replica-count ratio max/min over %d keys", KEYS).isLessThanOrEqualTo(1.5);
    }
}
