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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;

class ConsistentHashRingFilterTest {
    private static final byte[] TEST_KEY = "test-key".getBytes(StandardCharsets.UTF_8);

    @Nested
    class FilteredNodeSelection {
        private ConsistentHashRing<String> ring;

        @BeforeEach
        void setUp() {
            ring = consistentHashRing();
            ring.addNode("node-1");
            ring.addNode("node-2");
            ring.addNode("node-3");
            ring.addNode("node-4");
        }

        @Test
        void nodesFor_filterExcludesSpecificNodes_skipsFilteredAndContinuesWalk() {
            var excluded = Set.of("node-2");
            var nodes = ring.nodesFor(TEST_KEY, 3, node -> !excluded.contains(node));

            assertThat(nodes).hasSize(3);
            assertThat(nodes).doesNotContain("node-2");
        }

        @Test
        void nodesFor_filterAcceptsAll_returnsSameAsUnfiltered() {
            var filtered = ring.nodesFor(TEST_KEY, 3, node -> true);
            var unfiltered = ring.nodesFor(TEST_KEY, 3);

            assertThat(filtered).isEqualTo(unfiltered);
        }

        @Test
        void nodesFor_filterExcludesMultiple_returnsOnlyAcceptedNodes() {
            var allowed = Set.of("node-1", "node-3");
            var nodes = ring.nodesFor(TEST_KEY, 3, allowed::contains);

            assertThat(nodes).hasSize(2);
            assertThat(nodes).allMatch(allowed::contains);
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void nodesFor_emptyRing_returnsEmptyList() {
            ConsistentHashRing<String> ring = consistentHashRing();

            var nodes = ring.nodesFor(TEST_KEY, 3, node -> true);

            assertThat(nodes).isEmpty();
        }

        @Test
        void nodesFor_allNodesFiltered_returnsEmptyList() {
            ConsistentHashRing<String> ring = consistentHashRing();
            ring.addNode("node-1");
            ring.addNode("node-2");

            var nodes = ring.nodesFor(TEST_KEY, 3, node -> false);

            assertThat(nodes).isEmpty();
        }

        @Test
        void nodesFor_zeroReplicaCount_returnsEmptyList() {
            ConsistentHashRing<String> ring = consistentHashRing();
            ring.addNode("node-1");

            var nodes = ring.nodesFor(TEST_KEY, 0, node -> true);

            assertThat(nodes).isEmpty();
        }

        @Test
        void nodesFor_fewerAcceptedNodesThanRequested_returnsAvailableOnly() {
            ConsistentHashRing<String> ring = consistentHashRing();
            ring.addNode("node-1");
            ring.addNode("node-2");
            ring.addNode("node-3");

            var nodes = ring.nodesFor(TEST_KEY, 3, node -> node.equals("node-1"));

            assertThat(nodes).hasSize(1);
            assertThat(nodes).containsExactly("node-1");
        }
    }
}
