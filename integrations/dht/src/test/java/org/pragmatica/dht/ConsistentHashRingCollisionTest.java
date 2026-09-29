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

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;

/// #1324: two virtual nodes that hash to the same ring point. The ring kept ONE node per point, so the
/// later insertion overwrote the earlier one (placement depended on insertion order), and removing
/// either node deleted the shared point even while it belonged to the other.
class ConsistentHashRingCollisionTest {
    /// Two real node names whose virtual nodes collide under the ring's own hash (#1324): only these
    /// two nodes are needed, not a 1698-node cluster.
    private static final String LOW = "node-1698";
    private static final String HIGH = "node-89";
    private static final int COLLISION = -1059726684;

    @Test
    void collision_isRealUnderTheRingHash() {
        assertThat(ConsistentHashRing.hash(HIGH + "#99")).isEqualTo(COLLISION);
        assertThat(ConsistentHashRing.hash(LOW + "#10")).isEqualTo(COLLISION);
    }

    @Test
    void oppositeInsertionOrders_placeEveryPartitionIdentically() {
        var forward = ringOf(HIGH, LOW);
        var backward = ringOf(LOW, HIGH);

        assertThat(forward.nodesAtPoint(COLLISION)).containsExactly(LOW, HIGH);
        assertThat(backward.nodesAtPoint(COLLISION)).containsExactly(LOW, HIGH);
        assertThat(forward.nodesForPosition(COLLISION, 2)).isEqualTo(backward.nodesForPosition(COLLISION, 2))
                                                         .containsExactly(LOW, HIGH);
        IntStream.range(0, Partition.MAX_PARTITIONS)
                 .mapToObj(Partition::at)
                 .forEach(partition -> assertThat(forward.nodesFor(partition, 2)).as("partition %s", partition)
                                                                               .isEqualTo(backward.nodesFor(partition, 2)));
    }

    @Test
    void removingEitherNode_keepsTheOthersCollidingPoint() {
        var withoutLow = ringOf(HIGH, LOW);
        var withoutHigh = ringOf(HIGH, LOW);

        withoutLow.removeNode(LOW);
        withoutHigh.removeNode(HIGH);

        assertThat(withoutLow.nodesAtPoint(COLLISION)).containsExactly(HIGH);
        assertThat(withoutLow.nodesForPosition(COLLISION, 1)).containsExactly(HIGH);
        assertThat(withoutHigh.nodesAtPoint(COLLISION)).containsExactly(LOW);
        assertThat(withoutHigh.nodesForPosition(COLLISION, 1)).containsExactly(LOW);
    }

    @Test
    void repeatedRegistrationAndRemoval_leavesOnlyTheRegisteredNode() {
        var ring = ringOf(HIGH, LOW);

        ring.removeNode(HIGH);
        ring.addNode(HIGH);
        ring.addNode(HIGH);
        ring.removeNode(LOW);
        ring.removeNode(LOW);

        assertThat(ring.nodes()).isEqualTo(Set.of(HIGH));
        assertThat(ring.nodesAtPoint(COLLISION)).containsExactly(HIGH);
        IntStream.range(0, Partition.MAX_PARTITIONS)
                 .mapToObj(Partition::at)
                 .forEach(partition -> assertThat(ring.nodesFor(partition, 2)).containsExactly(HIGH));
    }

    @Test
    void emptiedPoint_isGoneFromTheRing() {
        var ring = ringOf(HIGH, LOW);

        ring.removeNode(HIGH);
        ring.removeNode(LOW);

        assertThat(ring.isEmpty()).isTrue();
        assertThat(ring.nodesAtPoint(COLLISION)).isEmpty();
    }

    private static ConsistentHashRing<String> ringOf(String first, String second) {
        ConsistentHashRing<String> ring = consistentHashRing();

        ring.addNode(first);
        ring.addNode(second);

        return ring;
    }
}
