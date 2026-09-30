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
package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;

import static org.assertj.core.api.Assertions.assertThat;

/// The byte-budgeted packer's three obligations: a non-fitting update waits at the FRONT (delayed, never
/// reordered behind later ones); an update that alone exceeds the budget is neither sent oversized (every
/// receiver would drop it) nor skipped forever (it would block, or vanish from, the gossip) — it goes out
/// without its labels; and the budget follows the sender's actual id length.
class PiggybackBufferBudgetTest {
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 9600);

    @Test
    void nonFittingUpdate_staysAtTheFront_andIsSentFirstNextRound() {
        var buffer = PiggybackBuffer.piggybackBuffer(16);
        var updates = List.of(update("u1", Map.of()), update("u2", Map.of()), update("u3", Map.of()));
        var one = PiggybackBuffer.estimatedBytes(updates.getFirst());

        updates.forEach(buffer::addUpdate);
        var budget = one + one / 2;

        assertThat(buffer.peekUpdates(8, budget)).extracting(u -> u.nodeId().id()).containsExactly("u1");
        assertThat(buffer.peekUpdates(8, budget)).as("u2 did not fit, so it is first next round, not behind u3")
                                                 .extracting(u -> u.nodeId().id())
                                                 .containsExactly("u2");
    }

    @Test
    void loneUpdateOverTheBudget_isSentWithoutLabels_neverOversizedNeverSkipped() {
        var buffer = PiggybackBuffer.piggybackBuffer(16);
        var hugeRole = Map.of(NodeInfo.LABEL_ROLE, "r".repeat(3072));

        buffer.addUpdate(update("huge", hugeRole));
        buffer.addUpdate(update("small", Map.of(NodeInfo.LABEL_ROLE, "core")));
        var budget = 1240;

        var first = buffer.peekUpdates(8, budget);
        var second = buffer.peekUpdates(8, budget);

        assertThat(first).as("the oversized update is delivered, stripped").anySatisfy(u -> {
            assertThat(u.nodeId().id()).isEqualTo("huge");
            assertThat(u.labels()).isEmpty();
        });
        assertThat(PiggybackBuffer.estimatedBytes(first.getFirst())).isLessThanOrEqualTo(budget);
        assertThat(first.size() + second.size()).as("the update behind it is not starved").isGreaterThanOrEqualTo(2);
        assertThat(Stream.concat(first.stream(), second.stream()).map(u -> u.nodeId().id())).contains("small");
    }

    @Test
    void budget_followsTheSendersActualIdLength() {
        var shortId = PiggybackBuffer.piggybackBudgetFor(new NodeId("s".repeat(60)));
        var longId = PiggybackBuffer.piggybackBudgetFor(new NodeId("s".repeat(250)));

        assertThat(shortId - longId).isEqualTo(190);
        assertThat(PiggybackBuffer.piggybackBudgetFor(new NodeId("s".repeat(5000)))).as("floored, never negative").isZero();
    }

    private static MembershipUpdate update(String id, Map<String, String> labels) {
        return MembershipUpdate.membershipUpdate(new NodeId(id), MemberState.ALIVE, 0L, ADDRESS, 0L, labels);
    }
}
