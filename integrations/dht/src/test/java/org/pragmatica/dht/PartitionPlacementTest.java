package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

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

    /// The ruling's property, over GENERATED rings rather than one hand-built example: for every key
    /// on an N-node RF=R ring, the set the REPAIR cycle works on (`nodesFor(Partition, rf)` — what
    /// [DHTAntiEntropy] and [DHTRebalancer] call) is the set the WRITE path placed the key on
    /// (`nodesFor(byte[], rf)`), ordered, filtered and primary alike. A single example cannot tell a
    /// fix from a coincidence; the defect this replaces was found by a ratio (196/2,000 same-owner-set
    /// on a 5-node RF=3 ring — chance level for three-of-five sets).
    @Test
    void repairOwnersEqualPlacementOwners_overGeneratedRings() {
        var random = new Random(20_420L);
        var checked = 0;

        for (var nodeCount : List.of(3, 5, 7, 20)) {
            for (var rf : List.of(1, 2, 3, 5)) {
                var ring = randomRing(nodeCount, random);
                var ringNodes = List.copyOf(ring.nodes());
                var excluded = ringNodes.get(random.nextInt(ringNodes.size()));

                for (int i = 0; i < 500; i++) {
                    var key = randomKey(random);
                    var partition = ring.partitionFor(key);

                    assertThat(ring.nodesFor(key, rf)).as("N=%d rf=%d: repair set is the placement set", nodeCount, rf)
                                                      .isEqualTo(ring.nodesFor(partition, rf));
                    assertThat(ring.primaryFor(key)).isEqualTo(ring.primaryFor(partition));
                    assertThat(ring.nodesFor(key, rf, n -> !n.equals(excluded)))
                        .isEqualTo(ring.nodesFor(partition, rf, n -> !n.equals(excluded)));
                    checked++;
                }
            }
        }

        assertThat(checked).as("control: the property was evaluated, not skipped").isEqualTo(4 * 4 * 500);
    }

    /// The structural half of the same ruling, and the half that is NOT true by delegation: placement
    /// is by PARTITION, so two distinct keys that hash to the same partition have the SAME owners. A
    /// per-key ring walk — the pre-#420 placement — starts at each key's own position and gives them
    /// different owners; this test goes red on any reintroduction of one, whatever the call graph
    /// looks like.
    @Test
    void keysSharingAPartitionShareTheirOwners() {
        var ring = fiveNodeRing();
        Map<Partition, byte[]> firstKeyOfPartition = new HashMap<>();
        var pairsCompared = 0;

        for (int i = 0; i < 20_000; i++) {
            var key = key(i);
            var partition = ring.partitionFor(key);
            var first = firstKeyOfPartition.putIfAbsent(partition, key);

            if (first == null) {
                continue;
            }

            assertThat(ring.nodesFor(key, RF)).as("partition %d: two keys in it, one owner list", partition.value())
                                              .isEqualTo(ring.nodesFor(first, RF));
            pairsCompared++;
        }

        assertThat(pairsCompared).as("control: same-partition key pairs were actually found").isGreaterThan(10_000);
    }

    private static ConsistentHashRing<NodeId> randomRing(int nodeCount, Random random) {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        for (int i = 0; i < nodeCount; i++) {
            ring.addNode(new NodeId("node-" + i + "-" + random.nextInt(1_000_000)));
        }

        return ring;
    }

    private static byte[] randomKey(Random random) {
        var key = new byte[16];

        random.nextBytes(key);

        return key;
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
