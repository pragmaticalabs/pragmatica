// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The round verdict mapping, offline (vf4b: the inline verdict and the ERROR rethrow had four surviving mutants; only a live round could reach
/// them). Every mapping is one test; each names the mutation that turns it red.
class EquivalenceVerdictTest {
    private static final List<String> NONE = List.of();
    private static final List<String> SOME = List.of("x");

    /// Mutation: a silent control scored PASS (the silent test dropped from `of`); goes red.
    @Test
    void aSilentControl_isVoid() {
        assertThat(EquivalenceVerdict.of(SOME, NONE, NONE)).isEqualTo(EquivalenceVerdict.VOID);
    }

    /// Mutation: a placement or quiescence void scored PASS (the voids test dropped from `of`); goes red.
    @Test
    void aVoid_isVoid() {
        assertThat(EquivalenceVerdict.of(NONE, SOME, NONE)).isEqualTo(EquivalenceVerdict.VOID);
    }

    /// Mutation: DIVERGE ahead of VOID (an unproven instrument must not accuse FSM2); goes red.
    @Test
    void voidBeatsDiverge() {
        assertThat(EquivalenceVerdict.of(SOME, NONE, SOME)).isEqualTo(EquivalenceVerdict.VOID);
        assertThat(EquivalenceVerdict.of(NONE, SOME, SOME)).isEqualTo(EquivalenceVerdict.VOID);
    }

    /// Mutation: a divergence scored PASS; goes red.
    @Test
    void aDivergence_isDiverge() {
        assertThat(EquivalenceVerdict.of(NONE, NONE, SOME)).isEqualTo(EquivalenceVerdict.DIVERGE);
    }

    @Test
    void onlyAnEmptyRound_isPass() {
        assertThat(EquivalenceVerdict.of(NONE, NONE, NONE)).isEqualTo(EquivalenceVerdict.PASS);
    }

    /// Mutation: the arm's exception swallowed (`throw e` replaced by a `null` result); the thrown-type assertion goes red.
    @Test
    void anArmError_isReportedAndRethrown() {
        var seen = new ArrayList<Throwable>();
        var boom = new IllegalStateException("arm boom");

        assertThatThrownBy(() -> EquivalenceVerdict.guarded(() -> {
            throw boom;
        }, seen::add)).isSameAs(boom);
        assertThat(seen).containsExactly(boom);
    }

    /// An `AssertionError` (a timed-out wait) is an ERROR too, not a pass-through.
    @Test
    void anAssertionFailureInAnArm_isReportedAndRethrown() {
        var seen = new ArrayList<Throwable>();
        var timeout = new AssertionError("F4 timed out waiting");

        assertThatThrownBy(() -> EquivalenceVerdict.guarded(() -> {
            throw timeout;
        }, seen::add)).isSameAs(timeout);
        assertThat(seen).containsExactly(timeout);
    }

    @Test
    void aSuccessfulArm_isReturnedAndNothingIsReported() {
        var seen = new ArrayList<Throwable>();

        assertThat(EquivalenceVerdict.guarded(() -> "arm", seen::add)).isEqualTo("arm");
        assertThat(seen).isEmpty();
    }

    /// Mutation: `wired` dropped, or its void test dropped; a round verdict that ignored the voids on its own record goes red.
    @Test
    void aVerdictContradictingTheRecordedFindings_isRejected() {
        assertThatThrownBy(() -> EquivalenceVerdict.wired(EquivalenceVerdict.PASS, NONE, SOME, NONE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EquivalenceVerdict.wired(EquivalenceVerdict.PASS, SOME, NONE, NONE)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EquivalenceVerdict.wired(EquivalenceVerdict.PASS, NONE, NONE, SOME)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EquivalenceVerdict.wired(EquivalenceVerdict.DIVERGE, NONE, SOME, SOME)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EquivalenceVerdict.wired(EquivalenceVerdict.VOID, NONE, NONE, NONE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aVerdictAgreeingWithTheFindings_passesThrough() {
        assertThat(EquivalenceVerdict.wired(EquivalenceVerdict.PASS, NONE, NONE, NONE)).isEqualTo(EquivalenceVerdict.PASS);
        assertThat(EquivalenceVerdict.wired(EquivalenceVerdict.VOID, SOME, SOME, SOME)).isEqualTo(EquivalenceVerdict.VOID);
        assertThat(EquivalenceVerdict.wired(EquivalenceVerdict.DIVERGE, NONE, NONE, SOME)).isEqualTo(EquivalenceVerdict.DIVERGE);
    }
}
