// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CoreVoterReconcilerTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");
    private static final VoterConfiguration CURRENT = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C)));

    @Test
    void replacesMissingVoterWithReadyCandidateButDoesNotInventCapacity() {
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, D), 3))
            .containsExactly(A, B, D);
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, D), 5)).isEmpty();
        assertThat(CoreVoterReconciler.selectVoters(A, CURRENT, Set.of(A, B, C, D), 3))
            .containsExactly(A, B, C);
    }

    @Test
    void unchangedRosterStillRequestsMissingInstallationProof() {
        var calls = new AtomicInteger();
        var pending = Promise.<Unit>promise();
        var reconciler = CoreVoterReconciler.coreVoterReconciler(A, () -> true,
            () -> Option.some(CURRENT), Option::none, () -> 3, () -> Set.of(A, B, C),
            roster -> { calls.incrementAndGet(); return pending; });
        reconciler.reconcile();
        reconciler.reconcile();
        assertThat(calls.get()).isEqualTo(1);
        pending.succeed(Unit.unit());
    }
}
