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

package org.pragmatica.consensus.net;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #2981 step 6 — wire-generation join guard decisions: a cold start is never refused, a handshaken lower member is
/// refused naming it, an unhandshaken member is unknown (waited for, then proceeded over).
class JoinGenerationGuardTest {
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(300).millis();
    private static final TimeSpan LONG = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(5).seconds();

    private final Map<NodeId, Map<String, String>> handshaken = new ConcurrentHashMap<>();

    private Option<Map<String, String>> labelsOf(NodeId id) {
        return Option.option(handshaken.get(id));
    }

    private static Map<String, String> generation(String value) {
        return Map.of(NodeInfo.LABEL_WIRE_GENERATION, value);
    }

    @Test
    void advertised_noLabel_stampsThisBinarysGeneration_keepingTheOtherLabels() {
        var advertised = JoinGenerationGuard.advertised(Map.of(NodeInfo.LABEL_VERSION, "1.0.0"));

        assertThat(advertised).containsEntry(NodeInfo.LABEL_WIRE_GENERATION,
                                             Integer.toString(JoinGenerationGuard.WIRE_GENERATION))
                              .containsEntry(NodeInfo.LABEL_VERSION, "1.0.0")
                              .hasSize(2);
        assertThat(JoinGenerationGuard.ownGeneration(Map.of())).isEqualTo(JoinGenerationGuard.WIRE_GENERATION);
    }

    @Test
    void advertised_explicitLabel_wins_andIsTheOwnGeneration() {
        assertThat(JoinGenerationGuard.advertised(generation("1"))).isEqualTo(generation("1"));
        assertThat(JoinGenerationGuard.ownGeneration(generation("1"))).isEqualTo(1);
    }

    @Test
    void coldStart_noMemberHandshaken_neverRefused_proceedsWhenTheWaitEnds() {
        var result = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, SHORT, 2).await(AWAIT);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void handshakenMemberWithoutLabel_isGenerationOne_refusedNamingMemberAndBothGenerations() {
        handshaken.put(A, Map.of());
        var promise = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, 2);

        promise.await(AWAIT)
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must be refused"))
               .onFailure(cause -> assertThat(cause).isEqualTo(new ConsensusError.WireGenerationRefused(A, 1, 2)));
        assertThat(new ConsensusError.WireGenerationRefused(A, 1, 2).message())
            .contains("node-a").contains("1").contains("2").contains("stop-the-world");
    }

    @Test
    void handshakenMemberWithExplicitLowerLabel_refused() {
        handshaken.put(B, generation("1"));

        JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, 3)
                           .await(AWAIT)
                           .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must be refused"))
                           .onFailure(cause -> assertThat(cause).isEqualTo(new ConsensusError.WireGenerationRefused(B, 1, 3)));
    }

    /// #3303 RA-3b bumped WIRE_GENERATION: a peer still at the previous generation cannot decode BackupRestoreValue's new
    /// shape, so it is refused at this binary's own generation.
    @Test
    void handshakenMemberAtThePreviousGeneration_isRefusedAtThisBinarysGeneration() {
        var previous = JoinGenerationGuard.WIRE_GENERATION - 1;
        handshaken.put(A, generation(Integer.toString(previous)));
        handshaken.put(B, generation(Integer.toString(JoinGenerationGuard.WIRE_GENERATION)));

        JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, JoinGenerationGuard.WIRE_GENERATION)
                           .await(AWAIT)
                           .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must be refused"))
                           .onFailure(cause -> assertThat(cause).isEqualTo(new ConsensusError.WireGenerationRefused(A,
                                                                                                                  previous,
                                                                                                                  JoinGenerationGuard.WIRE_GENERATION)));
    }

    @Test
    void unparseableLabel_countsAsGenerationOne() {
        assertThat(JoinGenerationGuard.generationOf(generation("two"))).isEqualTo(1);
        assertThat(JoinGenerationGuard.generationOf(Map.of())).isEqualTo(1);
        assertThat(JoinGenerationGuard.generationOf(generation("2"))).isEqualTo(2);
    }

    @Test
    void handshakenMemberWithNonIntegerLabel_isTreatedAsGenerationOne_andRefused() {
        handshaken.put(A, generation("two"));

        JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, 2)
                           .await(AWAIT)
                           .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must be refused"))
                           .onFailure(cause -> assertThat(cause).isEqualTo(new ConsensusError.WireGenerationRefused(A, 1, 2)));
    }

    @Test
    void everyMemberAtSameOrHigherGeneration_completesWellBeforeTheWait() {
        handshaken.put(A, generation("2"));
        handshaken.put(B, generation("3"));
        var start = System.nanoTime();

        var result = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, 2).await(AWAIT);

        assertThat(result.isSuccess()).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).as("ended early, not at the 30 s bound").isLessThan(5_000);
    }

    // admit() runs its first poll on the calling thread, so once it returns the guard has already observed B as not
    // handshaken and parked on the timer: "still waiting" is asserted against a wait far longer than the test.
    @Test
    void unknownMemberIsWaitedFor_untilItHandshakes() {
        handshaken.put(A, generation("2"));
        var promise = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, 2);

        assertThat(promise.isResolved()).as("B has not handshaken: the guard still waits").isFalse();

        handshaken.put(B, generation("2"));

        assertThat(promise.await(AWAIT).isSuccess()).as("all handshaken: proceeds before the bound").isTrue();
    }

    @Test
    void unknownMemberIsProceededOver_whenTheWaitEnds_decidingOnlyHandshakenMembers() {
        handshaken.put(A, generation("2"));

        assertThat(JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, SHORT, 2).await(AWAIT).isSuccess())
            .as("timeout proceeds").isTrue();
    }

    @Test
    void lowerMemberHandshakingDuringTheWait_isRefusedAtOnce() {
        var promise = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, 2);

        assertThat(promise.isResolved()).as("first poll saw nobody handshaken: waiting").isFalse();
        handshaken.put(B, Map.of());

        promise.await(AWAIT)
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must be refused"))
               .onFailure(cause -> assertThat(cause).isEqualTo(new ConsensusError.WireGenerationRefused(B, 1, 2)));
    }

    /// Pin: the generation is bumped to 9 by #3458 M-1b (ReadRefusal gained its `cause` slot, so an older node decodes the
    /// new shape's extra field as a malformed refusal). 8 was #3530 (four dead key types left the wire baseline); 7 was #3334
    /// S1a-1a (assignment flag day). A literal, so a revert to 8 goes red.
    @Test
    void thisBinarysGeneration_isNineAfterTheReadRefusalCauseSlot() {
        assertThat(JoinGenerationGuard.WIRE_GENERATION).isEqualTo(9);
    }

    @Test
    void emptyDecisionSet_completesImmediately() {
        assertThat(JoinGenerationGuard.admit(Set.of(), this::labelsOf, LONG, 2).await(AWAIT).isSuccess()).isTrue();
    }
}
