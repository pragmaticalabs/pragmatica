// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.testing.PollUntil.pollUntil;

/// #3847: drives reconcile passes of a read-one-at-a-time source until the `count`-th read is sent, and returns only after the answer that
/// freed the slot has landed and been acted on.
///
/// The contract of such a source (`CommittedCursorRead.committedEpochs`): a pass fixes its lookup when it STARTS, so the lookup reflects exactly
/// the answers that had landed before that pass started. The slot frees when an answer lands, and a pass that starts earlier can win the slot
/// the answer freed without that answer being in its lookup. Waiting on "a read was sent" therefore proves only that SOME pass won the slot.
/// The winning pass acts on answers up to N-2; the answer N-1 that freed the slot is seen by the NEXT pass.
///
/// So after the count-th read is sent, one more pass runs. The slot is taken (the test has not answered that read), so it sends nothing, and it
/// starts after every answer that freed the slot, so its lookup, or what it acts on, reflects all of them. Production is not changed: a pass
/// that sees its own answer would break `readAnsweringInsideThePass_isSeenByTheNextPassOnly`.
public sealed interface SlotPasses {
    /// Runs `pass` until `sent` reaches `count`, bounded by `deadline`, then runs one more pass that must send nothing and returns ITS result.
    ///
    /// @param pass     one reconcile pass: returns its lookup, or `Unit` when it acts through its own side effects
    /// @param sent     how many reads the source has sent so far
    /// @param count    the read whose answer the caller is about to deliver
    /// @param deadline the bound on reaching `count` reads
    static <T> T passUntilSent(Supplier<T> pass, IntSupplier sent, int count, Duration deadline) {
        pollUntil(() -> passAndCount(pass, sent), reads -> reads >= count, deadline).assertMet(count + " reads sent");
        return passThatSendsNothing(pass, sent);
    }

    private static <T> int passAndCount(Supplier<T> pass, IntSupplier sent) {
        pass.get();
        return sent.getAsInt();
    }

    private static <T> T passThatSendsNothing(Supplier<T> pass, IntSupplier sent) {
        var before = sent.getAsInt();
        var result = pass.get();

        assertThat(sent.getAsInt()).describedAs("the pass after the winning one finds the slot taken and sends nothing")
                                   .isEqualTo(before);
        return result;
    }

    record unused() implements SlotPasses {}
}
