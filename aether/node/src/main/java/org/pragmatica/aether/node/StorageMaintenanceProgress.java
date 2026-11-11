// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// #800: what the storage maintenance tick has done on this node, so a wedged or failing maintenance no longer looks the same
/// as an idle one. `lastRunMs` is when the latest tick finished (0 before the first), `outcome` is how it ended,
/// `consecutiveFailedTicks` counts the ticks in a row with a failed pass, `lastFailure` carries the cause of the latest failed
/// pass (kept after a recovery, until the next failure replaces it), `demoted` and `collected` are cumulative since boot.
public record StorageMaintenanceProgress(long lastRunMs,
                                         Outcome outcome,
                                         int consecutiveFailedTicks,
                                         Option<String> lastFailure,
                                         long demoted,
                                         long collected) {
    public enum Outcome {
        NEVER_RUN,
        OK,
        FAILED
    }

    public static StorageMaintenanceProgress storageMaintenanceProgress() {
        return new StorageMaintenanceProgress(0, Outcome.NEVER_RUN, 0, Option.none(), 0, 0);
    }

    /// The progress after a tick that ended at `nowMs` with the demotion and garbage-collection pass results given.
    StorageMaintenanceProgress after(long nowMs, Result<Integer> demotion, Result<Integer> collection) {
        var failure = failureOf(demotion).orElse(() -> failureOf(collection));

        return new StorageMaintenanceProgress(nowMs,
                                              failure.fold(() -> Outcome.OK, _ -> Outcome.FAILED),
                                              failure.fold(() -> 0,
                                                           _ -> consecutiveFailedTicks + 1),
                                              failure.orElse(lastFailure),
                                              demoted + demotion.or(0),
                                              collected + collection.or(0));
    }

    private static Option<String> failureOf(Result<Integer> pass) {
        return pass.fold(cause -> Option.some(cause.message()),
                         _ -> Option.none());
    }
}
