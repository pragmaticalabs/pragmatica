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
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;

class ConsistentHashRingRemovalListenerTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");

    @Test
    void removeNode_notifiesListener_whenNodeWasInRing() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        var removed = new ArrayList<NodeId>();
        ring.addNode(A);
        ring.onNodeRemoved(removed::add);

        ring.removeNode(A);

        assertThat(removed).isEqualTo(List.of(A));
    }

    @Test
    void removeNode_doesNotNotify_whenNodeAbsent() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        var removed = new ArrayList<NodeId>();
        ring.addNode(A);
        ring.onNodeRemoved(removed::add);

        ring.removeNode(B);
        ring.removeNode(A);
        ring.removeNode(A);

        assertThat(removed).isEqualTo(List.of(A));
    }

    @Test
    void removeNode_doesNotNotify_afterUnsubscribe() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        var removed = new ArrayList<NodeId>();
        ring.addNode(A);
        var unsubscribe = ring.onNodeRemoved(removed::add);

        unsubscribe.run();
        ring.removeNode(A);

        assertThat(removed).isEmpty();
    }

    @Test
    void removeNode_runsListenerOutsideTheRingLock() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(A);
        ring.addNode(B);
        var nodesSeen = new ArrayList<Integer>();
        // reading the ring (and writing it) from the listener would deadlock if the write lock were held
        ring.onNodeRemoved(_ -> {
            nodesSeen.add(ring.nodeCount());
            ring.addNode(new NodeId("c"));
        });

        ring.removeNode(A);

        assertThat(nodesSeen).isEqualTo(List.of(1));
        assertThat(ring.nodeCount()).isEqualTo(2);
    }
}
