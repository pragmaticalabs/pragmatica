package org.pragmatica.dht;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 M2 (v1820's walk sim, run through the real `DHTNode`): a node boots with a ring, then learns of J
/// nodes joining and some old members leaving while its partitions are still pending since boot. For every
/// such partition with a surviving old holder, the boot walk must reach at least one of them. A fixed 2·RF
/// walk misses every old holder in 0.3–3.2% of partitions at 5–8 joins; RF + J misses none. The 2·RF count is
/// computed alongside as the positive control: the harness can see a miss.
class DHTCatchUpBootWalkTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
    private static final int RF = 3;

    @Test
    void bootWalk_reachesASurvivingOldHolder_forOneToEightJoins_withRemovals() {
        long misses = 0, controlMisses = 0, checked = 0;

        for (int removals : new int[]{0, 2, 4}) {
            for (int joins = 1; joins <= 8; joins++) {
                var result = run(joins, removals);

                System.out.printf("M2 joins=%d removals=%d checked=%d missWalk=%d miss2RF(control)=%d%n",
                                  joins, removals, result[0], result[1], result[2]);
                checked += result[0];
                misses += result[1];
                controlMisses += result[2];
            }
        }

        assertThat(checked).as("partitions with a surviving old holder were checked").isPositive();
        assertThat(controlMisses).as("positive control: a fixed 2·RF walk misses some").isPositive();
        assertThat(misses).as("partitions whose boot walk reaches no surviving old holder").isZero();
    }

    /// Returns {checked, walk misses, 2·RF misses}.
    private static long[] run(int joins, int removals) {
        var rnd = new Random(31L * joins + removals);
        long checked = 0, misses = 0, controlMisses = 0;

        for (int trial = 0; trial < 40; trial++) {
            int n = 5 + rnd.nextInt(6);
            var base = new ArrayList<NodeId>();

            for (int i = 0; i < n; i++) {
                base.add(new NodeId("b" + trial + "-" + rnd.nextInt(1 << 30)));
            }

            var self = base.getFirst();
            var boot = ring(base);
            var node = dhtNode(self, memoryStorageEngine(), ring(base), CONFIG);

            node.beginCatchUp();

            var others = new ArrayList<>(base.subList(1, base.size()));

            Collections.shuffle(others, rnd);

            var removed = Set.copyOf(others.subList(0, Math.min(removals, others.size() - 2)));

            removed.forEach(node.ring()::removeNode);
            for (int i = 0; i < joins; i++) {
                node.ring().addNode(new NodeId("j" + trial + "-" + rnd.nextInt(1 << 30)));
            }

            for (var partition : node.pendingPartitions()) {
                if (!node.ring().nodesFor(partition, RF).contains(self)) {
                    continue;
                }

                var holders = new HashSet<>(boot.nodesFor(partition, RF));

                holders.remove(self);
                holders.removeAll(removed);
                if (holders.isEmpty()) {
                    continue;
                }

                checked++;
                if (missesAll(node.previousHolders(partition), holders)) {
                    misses++;
                }

                if (missesAll(node.ring().nodesFor(partition, 2 * RF), holders)) {
                    controlMisses++;
                }
            }
        }

        return new long[]{checked, misses, controlMisses};
    }

    private static boolean missesAll(Collection<NodeId> walk, Set<NodeId> holders) {
        return walk.stream().noneMatch(holders::contains);
    }

    private static ConsistentHashRing<NodeId> ring(List<NodeId> ids) {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ids.forEach(ring::addNode);

        return ring;
    }
}
