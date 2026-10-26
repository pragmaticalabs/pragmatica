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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens.Admission;

import static org.assertj.core.api.Assertions.assertThat;

/// The boot-token rule in isolation: the table of `BootTokens.admit` outcomes that every admission path
/// (SWIM, the QUIC handshake, inbound dispatch) consults through one registry instance.
///
/// Rows: unknown / equal / different / retired / token `0`, each against a peer in the state that makes the
/// row mean something. The registry is process memory only, so the last rows pin what it does NOT promise:
/// a registry built after a restart has no memory of a retirement.
class BootTokensTest {
    private static final long SELF = 0x5E1FL;
    private static final long FIRST = 0xA1L;
    private static final long OTHER = 0xB2L;
    private static final long THIRD = 0xC3L;

    private final BootTokens registry = BootTokens.bootTokens(SELF);
    private final NodeId peer = NodeId.randomNodeId();

    @Test
    void self_returnsTheTokenTheRegistryWasBuiltWith() {
        assertThat(registry.self()).isEqualTo(SELF);
    }

    @Test
    void admit_admitsAnUnknownPeer_andRecordsItsToken() {
        assertThat(registry.admit(peer, FIRST)).isEqualTo(Admission.ADMITTED);

        assertThat(registry.tokenOf(peer)).isEqualTo(FIRST);
        assertThat(registry.isRetired(peer)).isFalse();
        assertThat(registry.refusals()).isZero();
    }

    @Test
    void admit_admitsTheEqualTokenAgain_withoutRefusal() {
        registry.admit(peer, FIRST);

        assertThat(registry.admit(peer, FIRST)).isEqualTo(Admission.ADMITTED);
        assertThat(registry.admit(peer, FIRST)).isEqualTo(Admission.ADMITTED);

        assertThat(registry.isRetired(peer)).isFalse();
        assertThat(registry.refusals()).isZero();
    }

    @Test
    void admit_conflicts_onADifferentTokenForAKnownPeer() {
        registry.admit(peer, FIRST);

        assertThat(registry.admit(peer, OTHER)).isEqualTo(Admission.CONFLICT);

        assertThat(registry.isRetired(peer)).isTrue();
        assertThat(registry.refusals()).isEqualTo(1);
    }

    @Test
    void admit_keepsTheFirstToken_afterAConflict() {
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);

        assertThat(registry.tokenOf(peer)).as("tokenOf only ever moves 0 -> t and never changes").isEqualTo(FIRST);
    }

    @Test
    void admit_refusesTheOriginalToken_onceRetired() {
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);

        assertThat(registry.admit(peer, FIRST)).as("the known process is refused too").isEqualTo(Admission.RETIRED);
    }

    @Test
    void admit_refusesTheConflictingToken_onceRetired() {
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);

        assertThat(registry.admit(peer, OTHER)).isEqualTo(Admission.RETIRED);
    }

    @Test
    void admit_refusesAThirdToken_onceRetired() {
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);

        assertThat(registry.admit(peer, THIRD)).isEqualTo(Admission.RETIRED);
    }

    @Test
    void admit_refusesTokenZero_onceRetired() {
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);

        assertThat(registry.admit(peer, 0L)).as("retirement outranks the token-0 pass").isEqualTo(Admission.RETIRED);
    }

    @Test
    void admit_countsEveryRefusal() {
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);
        registry.admit(peer, FIRST);
        registry.admit(peer, 0L);

        assertThat(registry.refusals()).as("one CONFLICT and two RETIRED").isEqualTo(3);
    }

    @Test
    void admit_admitsTokenZeroForAnUnknownPeer_withoutRecordingIt() {
        assertThat(registry.admit(peer, 0L)).isEqualTo(Admission.ADMITTED);

        assertThat(registry.tokenOf(peer)).as("token 0 carries no process identity").isZero();
        assertThat(registry.admit(peer, FIRST)).isEqualTo(Admission.ADMITTED);
        assertThat(registry.tokenOf(peer)).isEqualTo(FIRST);
    }

    @Test
    void admit_admitsTokenZeroForAKnownPeer_withoutChangingTheRecord() {
        registry.admit(peer, FIRST);

        assertThat(registry.admit(peer, 0L)).isEqualTo(Admission.ADMITTED);

        assertThat(registry.tokenOf(peer)).isEqualTo(FIRST);
        assertThat(registry.isRetired(peer)).isFalse();
    }

    @Test
    void admit_keepsPeersIndependent() {
        var untouched = NodeId.randomNodeId();

        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);

        assertThat(registry.admit(untouched, OTHER)).isEqualTo(Admission.ADMITTED);
        assertThat(registry.isRetired(untouched)).isFalse();
    }

    @Test
    void admit_reportsAdmittedOnlyForAdmitted() {
        assertThat(Admission.ADMITTED.admitted()).isTrue();
        assertThat(Admission.CONFLICT.admitted()).isFalse();
        assertThat(Admission.RETIRED.admitted()).isFalse();
    }

    @Test
    void onRetired_firesOnce_onTheAdmissionThatRetires() {
        List<NodeId> retired = new ArrayList<>();

        registry.onRetired(retired::add);
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);
        registry.admit(peer, OTHER);
        registry.admit(peer, FIRST);

        assertThat(retired).containsExactly(peer);
    }

    @Test
    void onRetired_staysSilent_forAdmissionsAndTokenZero() {
        List<NodeId> retired = new ArrayList<>();

        registry.onRetired(retired::add);
        registry.admit(peer, 0L);
        registry.admit(peer, FIRST);
        registry.admit(peer, FIRST);
        registry.admit(peer, 0L);

        assertThat(retired).isEmpty();
    }

    @Test
    void selfRefused_notifiesTheListenerOnce() {
        List<String> reasons = new ArrayList<>();

        registry.onSelfRefused(reasons::add);
        registry.selfRefused("first reason");
        registry.selfRefused("second reason");

        assertThat(reasons).containsExactly("first reason");
    }

    @Test
    void admit_afterARestart_admitsTheTokenThatWasRetired() {
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);

        var restarted = BootTokens.bootTokens(SELF);

        assertThat(restarted.isRetired(peer)).as("no memory of the retirement across a restart (not guaranteed)").isFalse();
        assertThat(restarted.admit(peer, FIRST)).as("the retired process's old token is first-seen again").isEqualTo(Admission.ADMITTED);
        assertThat(restarted.tokenOf(peer)).isEqualTo(FIRST);
    }

    @Test
    void admit_afterARestart_firstSeenTokenWins_whicheverProcessPresentsItFirst() {
        registry.admit(peer, FIRST);
        registry.admit(peer, OTHER);

        var restarted = BootTokens.bootTokens(SELF);

        assertThat(restarted.admit(peer, OTHER)).isEqualTo(Admission.ADMITTED);
        assertThat(restarted.admit(peer, FIRST)).as("now the earlier process is the conflicting one").isEqualTo(Admission.CONFLICT);
    }
}
