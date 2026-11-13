// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// The verdict of one F4 round, as a pure function of what the round recorded, so every mapping is unit-tested and no live round is needed to
/// reach it (vf4b: the inline verdict had four surviving mutants).
///
/// - **ERROR**: an arm threw. [#guarded] logs it and rethrows, so no verdict file claims PASS or VOID for a round that did not finish.
/// - **VOID**: a divergent control stayed silent, or the placement/quiescence precondition failed ([#of]). VOID beats DIVERGE: a round whose
///   instrument is not proven says nothing about FSM2.
/// - **DIVERGE**: no void, at least one divergence.
/// - **PASS**: no silent control, no void, no divergence.
enum EquivalenceVerdict {
    PASS,
    VOID,
    DIVERGE;

    static EquivalenceVerdict of(List<String> silentControls, List<String> voids, List<String> divergences) {
        if (!silentControls.isEmpty() || !voids.isEmpty()) {
            return VOID;
        }

        return divergences.isEmpty() ? PASS : DIVERGE;
    }

    /// The verdict the round's own findings demand, re-derived from the lists the round logs: PASS with a silent control, a void or a
    /// divergence on the record, VOID without one, or DIVERGE where a void is on the record, is a round whose verdict line contradicts its own
    /// log (f4fixB: a call site that dropped `voids` scored a non-quiescent round PASS). Returns `verdict` when it agrees, else throws.
    static EquivalenceVerdict wired(EquivalenceVerdict verdict, List<String> silentControls, List<String> voids, List<String> divergences) {
        var voided = !silentControls.isEmpty() || !voids.isEmpty();
        var demanded = voided ? VOID : divergences.isEmpty() ? PASS : DIVERGE;

        if (verdict != demanded) {
            throw new IllegalStateException("instrument: the round verdict " + verdict + " contradicts its findings (silent=" + silentControls
                                            + ", voids=" + voids + ", divergences=" + divergences + "), which demand " + demanded);
        }

        return verdict;
    }

    /// Runs one arm. An exception (or assertion failure) is passed to `onError` and rethrown: the ERROR outcome.
    static <T> T guarded(Supplier<T> arm, Consumer<Throwable> onError) {
        try {
            return arm.get();
        } catch (RuntimeException | AssertionError e) {
            onError.accept(e);
            throw e;
        }
    }
}
