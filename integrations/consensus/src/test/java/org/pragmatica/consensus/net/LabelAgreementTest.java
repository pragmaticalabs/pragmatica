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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.testing.PollUntil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #3525 (L1) — the decisions of label agreement in the join guard: a symmetric comparison in which an absent label differs
/// from a present one, a quorum that is waited for without a deadline, and a notice that fires once per wait episode.
class LabelAgreementTest {
    private static final String LABEL = "backup";
    private static final NodeId A = nodeId("node-a").unwrap();
    private static final NodeId B = nodeId("node-b").unwrap();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(150).millis();
    private static final TimeSpan LONG = TimeSpan.timeSpan(30).seconds();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(5).seconds();
    private static final int OWN = JoinGenerationGuard.WIRE_GENERATION;

    private final Map<NodeId, Map<String, String>> handshaken = new ConcurrentHashMap<>();
    private final List<Set<NodeId>> notices = new CopyOnWriteArrayList<>();

    private final AtomicInteger lookups = new AtomicInteger();

    private Option<Map<String, String>> labelsOf(NodeId id) {
        lookups.incrementAndGet();

        return Option.option(handshaken.get(id));
    }

    /// Waits until the guard has polled several more times: a poll reads the members' labels, and polls are the unit in which
    /// "stays pending" is observed (a fixed sleep would guess how many polls it covers).
    private void awaitMorePolls() {
        var baseline = lookups.get();

        PollUntil.pollUntil(lookups::get, seen -> seen >= baseline + 30, Duration.ofSeconds(5)).assertMet("guard polls");
    }

    private static Map<String, String> hello(String value) {
        var generation = Integer.toString(OWN);

        return value == null
               ? Map.of(NodeInfo.LABEL_WIRE_GENERATION, generation)
               : Map.of(NodeInfo.LABEL_WIRE_GENERATION, generation, LABEL, value);
    }

    private LabelAgreement agreement(String ownValue, int peersNeeded) {
        return LabelAgreement.labelAgreement(hello(ownValue),
                                             Set.of(LABEL),
                                             peersNeeded,
                                             missing -> {
                                                 notices.add(missing);
                                                 return Unit.unit();
                                             });
    }

    private void assertRefused(String ownValue, String peerValue, Option<String> memberValue, Option<String> ownSeen) {
        handshaken.put(A, hello(peerValue));

        JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, OWN, agreement(ownValue, 1))
                           .await(AWAIT)
                           .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must be refused"))
                           .onFailure(cause -> assertThat(cause).isEqualTo(new ConsensusError.LabelAgreementRefused(A,
                                                                                                                  LABEL,
                                                                                                                  memberValue,
                                                                                                                  ownSeen)));
    }

    @Test
    void differentValues_refusedNamingMemberLabelAndBothValues() {
        assertRefused("on", "off", Option.some("off"), Option.some("on"));
        assertThat(new ConsensusError.LabelAgreementRefused(A, LABEL, Option.some("off"), Option.some("on")).message())
            .contains("node-a").contains(LABEL).contains("'off'").contains("'on'");
    }

    /// One direction of the absent-versus-present rule: this node has the label, the handshaken peer lacks it.
    @Test
    void peerLacksTheLabel_thisNodeHasIt_refused() {
        assertRefused("on", null, Option.none(), Option.some("on"));
    }

    /// The other direction: this node lacks the label, the handshaken peer has it. A one-way check passes one of the two.
    @Test
    void thisNodeLacksTheLabel_peerHasIt_refused() {
        assertRefused(null, "on", Option.some("on"), Option.none());
    }

    @Test
    void bothAbsent_agree_andTheQuorumDecides() {
        handshaken.put(A, hello(null));

        var result = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, SHORT, OWN, agreement(null, 1)).await(AWAIT);

        assertThat(result.isSuccess()).as("one handshaken peer is the quorum; the generation bound passes over the other").isTrue();
    }

    @Test
    void equalValues_allHandshaken_admitted() {
        handshaken.put(A, hello("on"));
        handshaken.put(B, hello("on"));

        assertThat(JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, OWN, agreement("on", 2)).await(AWAIT)
                                      .isSuccess()).isTrue();
    }

    /// The deadline does not pass agreement: with the quorum incomplete the guard stays pending long after the bound, and the
    /// generation-only guard (no labels) still passes at the bound as before.
    @Test
    void pastTheDeadline_quorumIncomplete_staysPending_whileNoLabelsPassesAtTheDeadline() {
        var pending = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, SHORT, OWN, agreement("on", 1));
        var noLabels = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, SHORT, OWN);

        assertThat(noLabels.await(AWAIT).isSuccess()).as("control: the generation wait still passes at its bound").isTrue();
        awaitMorePolls();

        assertThat(pending.isResolved()).as("agreement has no deadline pass-through").isFalse();

        handshaken.put(B, hello("on"));

        assertThat(pending.await(AWAIT).isSuccess()).as("the quorum arrives, the wait ends").isTrue();
    }

    /// The notice fires once however many polls find the quorum incomplete, and names exactly the peers not yet handshaken.
    @Test
    void incompleteQuorum_noticeOncePerEpisode_namingTheMissingPeers() {
        handshaken.put(A, hello("on"));

        var pending = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, SHORT, OWN, agreement("on", 2));

        PollUntil.pollUntil(notices::size, count -> count >= 1, Duration.ofSeconds(5)).assertMet("the notice");
        awaitMorePolls();

        assertThat(pending.isResolved()).isFalse();
        assertThat(notices).containsExactly(Set.of(B));
    }

    /// A refusal outranks waiting: a mismatch is reported at once even while the quorum is incomplete.
    @Test
    void mismatch_whileTheQuorumIsIncomplete_failsAtOnce() {
        handshaken.put(A, hello("off"));

        var result = JoinGenerationGuard.admit(Set.of(A, B), this::labelsOf, LONG, OWN, agreement("on", 2)).await(AWAIT);

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void none_isVacuous() {
        assertThat(LabelAgreement.none().reached(Set.of(A, B), this::labelsOf)).isTrue();
        assertThat(LabelAgreement.none().firstMismatch(Set.of(A, B), this::labelsOf).isEmpty()).isTrue();
    }
}
