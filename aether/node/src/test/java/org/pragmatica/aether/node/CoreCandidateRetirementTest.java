// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
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
}
