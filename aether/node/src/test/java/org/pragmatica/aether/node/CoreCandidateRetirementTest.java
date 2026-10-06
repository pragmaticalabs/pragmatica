// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import static org.assertj.core.api.Assertions.assertThat;

class CoreCandidateRetirementTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId REPLACEMENT = new NodeId("replacement");
    private static final Set<NodeId> ORIGINAL = Set.of(A, B, C);

    @Test void replacementIsNotSurplusWhileInstalledVoterIsMissingOrUnready() {
        assertThat(AetherNode.retirementEligibleCore(REPLACEMENT, ORIGINAL, ORIGINAL, Set.of(A, C, REPLACEMENT))).isFalse();
        assertThat(AetherNode.retirementEligibleCore(REPLACEMENT, ORIGINAL, ORIGINAL, Set.of(A, C))).isFalse();
    }

    @Test void excludedHistoricalVoterCanRetireAfterCertifiedHandoff() {
        assertThat(AetherNode.retirementEligibleCore(B, Set.of(A, C, REPLACEMENT), ORIGINAL, Set.of(A, C))).isTrue();
    }

    @Test void genuinelySurplusCandidateCanRetireWhenInstalledRosterIsReady() {
        assertThat(AetherNode.retirementEligibleCore(REPLACEMENT, ORIGINAL, ORIGINAL, ORIGINAL)).isTrue();
    }

    @Test void installedVotersAndMissingAuthorityAreNeverRetirementCandidates() {
        assertThat(AetherNode.retirementEligibleCore(A, ORIGINAL, ORIGINAL, ORIGINAL)).isFalse();
        assertThat(AetherNode.retirementEligibleCore(REPLACEMENT, Set.of(), ORIGINAL, ORIGINAL)).isFalse();
    }

    /// #1804 — the retirement verdict the reaper consults, with the reason it is refused.
    private static final NodeId DEAD = new NodeId("dead");
    private static final Option<MemberDescriptor> UNTRACKED = Option.none();
    private static final Option<MemberDescriptor> TRACKED_CORE = Option.some(new MemberDescriptor(Option.none(), "core", "primary"));
    private static final Option<MemberDescriptor> TRACKED_WORKER = Option.some(new MemberDescriptor(Option.none(), "worker", "primary"));
    private static final Option<Set<NodeId>> ELECTORATE = Option.some(ORIGINAL);

    /// (ii): a new leader's activation replay selects nodes it does not track, so the verdict must not require a
    /// descriptor. An untracked node that voted in the past and is no longer a voter is retirable.
    @Test void untrackedFormerVoter_notInElectorate_isRetirable() {
        assertThat(AetherNode.retirementRefusal(UNTRACKED, ELECTORATE, Set.of(A, B, C, DEAD), ORIGINAL, true, DEAD).isEmpty()).isTrue();
    }

    /// (i): the refusal while a voter carries its reason, and clears once the node is out of the electorate.
    @Test void trackedCoreStillVoting_isRefusedWithReason_thenRetirableOnceVotedOut() {
        var voting = AetherNode.retirementRefusal(TRACKED_CORE, Option.some(Set.of(A, B, DEAD)), Set.of(A, B, DEAD), Set.of(A, B), true, DEAD);

        assertThat(voting.or("")).contains("installed voter");
        assertThat(AetherNode.retirementRefusal(TRACKED_CORE, Option.some(Set.of(A, B, C)), Set.of(A, B, C, DEAD), Set.of(A, B, C), true, DEAD).isEmpty()).isTrue();
    }

    /// Control: being untracked never makes a current voter retirable.
    @Test void untrackedCurrentVoter_isNeverRetirable() {
        assertThat(AetherNode.retirementRefusal(UNTRACKED, ELECTORATE, ORIGINAL, ORIGINAL, true, A).isPresent()).isTrue();
        assertThat(AetherNode.retirementRefusal(UNTRACKED, ELECTORATE, ORIGINAL, Set.of(), true, A).isPresent()).isTrue();
    }

    @Test void untrackedNeverVoter_isRefusedWhileInstalledRosterIsNotReady() {
        assertThat(AetherNode.retirementRefusal(UNTRACKED, ELECTORATE, ORIGINAL, Set.of(A, B), true, DEAD).isPresent()).isTrue();
        assertThat(AetherNode.retirementRefusal(UNTRACKED, ELECTORATE, ORIGINAL, ORIGINAL, true, DEAD).isEmpty()).isTrue();
    }

    @Test void withoutCertifiedElectorateOrWithDeployments_isRefused() {
        assertThat(AetherNode.retirementRefusal(UNTRACKED, Option.none(), Set.of(DEAD), ORIGINAL, true, DEAD).isPresent()).isTrue();
        assertThat(AetherNode.retirementRefusal(UNTRACKED, ELECTORATE, Set.of(DEAD), ORIGINAL, false, DEAD).isPresent()).isTrue();
        assertThat(AetherNode.retirementRefusal(TRACKED_CORE, ELECTORATE, Set.of(DEAD), ORIGINAL, false, DEAD).isPresent()).isTrue();
    }

    @Test void trackedWorker_isRetirable_unknownRoleIsNot() {
        assertThat(AetherNode.retirementRefusal(TRACKED_WORKER, Option.none(), Set.of(), Set.of(), true, DEAD).isEmpty()).isTrue();
        assertThat(AetherNode.retirementRefusal(Option.some(new MemberDescriptor(Option.none(), "governor", "primary")),
                                                ELECTORATE, Set.of(DEAD), ORIGINAL, true, DEAD).isPresent()).isTrue();
    }
    /// #1543 rule 1: a fresh replacement that is not yet a voter is today's genuine surplus (see
    /// `genuinelySurplusCandidateCanRetireWhenInstalledRosterIsReady`) and is reaped within a second. Paired as
    /// the incoming replacement of a live NodeReplacement record, it is not an excluded core.
    @Test void excludedCores_skipThePairedReplacement() {
        var counted = Set.of(A, B, C, REPLACEMENT);

        assertThat(AetherNode.excludedCores(counted, ORIGINAL, ORIGINAL, counted, Set.of())).containsExactly(REPLACEMENT);
        assertThat(AetherNode.excludedCores(counted, ORIGINAL, ORIGINAL, counted, Set.of(REPLACEMENT))).isEmpty();
    }

    /// The swapped-out original is protected until its pairing reaches RETIRING_OLD; other exclusions are unaffected.
    @Test void excludedCores_skipOnlyProtectedNodes() {
        var installed = Set.of(A, C, REPLACEMENT);
        var counted = Set.of(A, B, C, REPLACEMENT, DEAD);
        var history = Set.of(A, B, C, DEAD);

        assertThat(AetherNode.excludedCores(counted, installed, history, installed, Set.of(B))).containsExactly(DEAD);
        assertThat(AetherNode.excludedCores(counted, installed, history, installed, Set.of())).containsExactlyInAnyOrder(B, DEAD);
    }

    /// The backstop every CTM drain consults (the LeaderReconciler surplus drain picks a fresh ephemeral core first).
    @Test void retirementRefusal_pairedNode_isRefusedWithReason_unpairedVerdictUnchanged() {
        assertThat(AetherNode.retirementRefusal(Set.of(REPLACEMENT), TRACKED_CORE, ELECTORATE, ORIGINAL, ORIGINAL, true, REPLACEMENT).or(""))
            .contains("node replacement");
        assertThat(AetherNode.retirementRefusal(Set.of(REPLACEMENT), TRACKED_WORKER, Option.none(), Set.of(), Set.of(), true, REPLACEMENT).isPresent())
            .isTrue();
        assertThat(AetherNode.retirementRefusal(Set.of(), TRACKED_CORE, ELECTORATE, ORIGINAL, ORIGINAL, true, REPLACEMENT).isEmpty()).isTrue();
    }
}
