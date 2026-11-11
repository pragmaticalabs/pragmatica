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

    @Test
    void unknownMemberIsWaitedFor_thenProceededOver_decidingOnlyHandshakenMembers() throws InterruptedException {
        handshaken.put(A, generation("2"));
        var promise = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, SHORT, 2);

        Thread.sleep(100);
        assertThat(promise.isResolved()).as("B has not handshaken: the guard still waits").isFalse();
        assertThat(promise.await(AWAIT).isSuccess()).as("timeout proceeds").isTrue();
    }

    @Test
    void lowerMemberHandshakingDuringTheWait_isRefusedAtOnce() throws InterruptedException {
        var promise = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, 2);

        Thread.sleep(100);
        handshaken.put(B, Map.of());

        promise.await(AWAIT)
               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must be refused"))
               .onFailure(cause -> assertThat(cause).isEqualTo(new ConsensusError.WireGenerationRefused(B, 1, 2)));
    }

    @Test
    void emptyDecisionSet_completesImmediately() {
        assertThat(JoinGenerationGuard.admit(Set.of(), this::labelsOf, LONG, 2).await(AWAIT).isSuccess()).isTrue();
    }
}
