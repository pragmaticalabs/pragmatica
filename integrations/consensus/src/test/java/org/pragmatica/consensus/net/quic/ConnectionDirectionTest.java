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

/// `ConnectionDirection` is no longer a dial precondition — it is a deterministic
/// duplicate-resolution tiebreak. `prefersInitiator(a, b)` decides which initiator id
/// wins a concurrent dual-dial: the lower NodeId. The tiebreak must be total and
/// antisymmetric so both ends converge on the same survivor.
class ConnectionDirectionTest {

    @Nested
    class PrefersInitiator {
        @Test
        void prefersInitiator_lowerInitiatorWins_returnsTrue() {
            var lower = new NodeId("node-aaa");
            var higher = new NodeId("node-zzz");

            assertThat(ConnectionDirection.prefersInitiator(lower, higher))
                .as("lower initiator id wins the duplicate tiebreak")
                .isTrue();
        }

        @Test
        void prefersInitiator_higherInitiatorLoses_returnsFalse() {
            var lower = new NodeId("node-aaa");
            var higher = new NodeId("node-zzz");

            assertThat(ConnectionDirection.prefersInitiator(higher, lower))
                .as("higher initiator id loses the duplicate tiebreak")
                .isFalse();
        }

        @Test
        void prefersInitiator_equalInitiators_returnsFalse() {
            var node = new NodeId("node-same");

            assertThat(ConnectionDirection.prefersInitiator(node, node))
                .as("a tie is not a strict preference — incumbent is kept")
                .isFalse();
        }

        @Test
        void prefersInitiator_isAntisymmetric_bothEndsAgreeOnWinner() {
            var nodeA = new NodeId("node-alpha");
            var nodeB = new NodeId("node-beta");

            var aWins = ConnectionDirection.prefersInitiator(nodeA, nodeB);
            var bWins = ConnectionDirection.prefersInitiator(nodeB, nodeA);

            assertThat(aWins).isNotEqualTo(bWins);
        }
    }
}
