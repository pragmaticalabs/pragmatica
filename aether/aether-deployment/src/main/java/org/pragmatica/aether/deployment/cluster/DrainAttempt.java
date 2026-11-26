// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The one pending drain or eviction of a target: the only place such an attempt exists (#3754, invariant D). The manager maps a
/// target to its attempt, and every effect of the attempt (registry write, failure untrack, grace reap) takes place only if the
/// map still holds an entry with this attempt's `seq`, reading `kind` and `reason` from that entry.
///
/// `seq` is the attempt's identity: one counter per manager instance. A raise replaces the entry with one of the same `seq`,
/// `epoch` and `outcome`, so identity is never record equality. `reason` is empty for an [Kind#EVICTION]. `outcome` is resolved
/// once, by the caller that opened the attempt.
record DrainAttempt(Kind kind, Option<DrainReason> reason, long epoch, long seq, Promise<Unit> outcome) {
    /// DRAIN writes the command and arms the grace reap; EVICTION writes the command only (an operator's node is never reaped).
    enum Kind {
        DRAIN,
        EVICTION
    }

    static DrainAttempt drainAttempt(Kind kind,
                                     Option<DrainReason> reason,
                                     long epoch,
                                     long seq,
                                     Promise<Unit> outcome) {
        return new DrainAttempt(kind, reason, epoch, seq, outcome);
    }

    DrainAttempt withKind(Kind newKind) {
        return drainAttempt(newKind, reason, epoch, seq, outcome);
    }

    DrainAttempt withReason(DrainReason newReason) {
        return drainAttempt(kind, Option.some(newReason), epoch, seq, outcome);
    }
}
