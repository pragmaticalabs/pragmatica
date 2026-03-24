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

package org.pragmatica.consensus.net.quic;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionDirectionTest {

    @Nested
    class ShouldInitiate {
        @Test
        void shouldInitiate_lowerIdReturnsTrue() {
            var lower = new NodeId("node-aaa");
            var higher = new NodeId("node-zzz");

            assertThat(ConnectionDirection.shouldInitiate(lower, higher)).isTrue();
        }

        @Test
        void shouldInitiate_higherIdReturnsFalse() {
            var lower = new NodeId("node-aaa");
            var higher = new NodeId("node-zzz");

            assertThat(ConnectionDirection.shouldInitiate(higher, lower)).isFalse();
        }

        @Test
        void shouldInitiate_equalIdsReturnsFalse() {
            var node = new NodeId("node-same");

            assertThat(ConnectionDirection.shouldInitiate(node, node)).isFalse();
        }

        @Test
        void shouldInitiate_isAntisymmetric() {
            var nodeA = new NodeId("node-alpha");
            var nodeB = new NodeId("node-beta");

            var aInitiates = ConnectionDirection.shouldInitiate(nodeA, nodeB);
            var bInitiates = ConnectionDirection.shouldInitiate(nodeB, nodeA);

            assertThat(aInitiates).isNotEqualTo(bInitiates);
        }
    }
}
