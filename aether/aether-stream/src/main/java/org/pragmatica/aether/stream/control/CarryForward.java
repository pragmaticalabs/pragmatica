// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import java.util.List;

import org.pragmatica.lang.Promise;


/// The owner-side carry-forward of a control log (#2590 P1, §5.2 item 4): the retention floor is the oldest live key's
/// latest record, so a key written once and never again would hold the floor, and every segment above it, forever. The
/// owner re-appends such a record as a CARRY of the identical admitted payload, which moves the key's latest record, and
/// so the floor, forward.
///
/// A CARRY bypasses the kind's admission predicate (it restates what was already admitted, so it cannot be refused for
/// what the record says), but it is still conditional on the record being its key's latest in the admitted view, and it
/// still takes the ownership fence, the fsync and the barrier. Clients cannot write one.
///
/// The policy that turns "older than `H`" into an offset is the caller's: `H` and its default are open (Q7), and P1 has
/// no caller.
@FunctionalInterface
public interface CarryForward {
    /// Carries every live latest record of `(streamName, partition)` at an offset below `belowOffset`, one outcome per
    /// candidate in offset order: [ControlOutcome.Admitted] at the CARRY's offset, or [ControlOutcome.Refused] with
    /// [ControlLogError.General#CARRY_SUPERSEDED] when a newer record of the key is already in flight. A partition whose
    /// admitted view is not usable answers one [ControlOutcome.IndexUnavailable].
    Promise<List<ControlOutcome>> carryForward(String streamName, int partition, long belowOffset);
}
