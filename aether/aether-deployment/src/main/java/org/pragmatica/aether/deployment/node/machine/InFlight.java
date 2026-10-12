// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;


/// The one observed write in flight for a key (spec R-3.4.9 (2)). `commands` is resubmitted unchanged on a retry, so a
/// retry is the same version-fenced Put (R-3.4.4); `cycle` counts FAILED-write cycles (N19).
public record InFlight(long correlation,
                       long version,
                       SliceObservedState state,
                       List<KVCommand<AetherKey>> commands,
                       int attempt,
                       Purpose purpose,
                       int cycle) {
    /// Why the write was made. Decides what its confirmation or its exhaustion leads to.
    public enum Purpose {
        /// A step of the live chain.
        CHAIN,
        /// A FAILED row (N3, N5, N6, N14, N19).
        FAILED,
        /// The witnessed Remove of the node's own tombstone (R-1.6.3).
        TOMBSTONE_REMOVE
    }

    public InFlight {
        commands = List.copyOf(commands);
    }

    public static InFlight inFlight(long correlation,
                                    long version,
                                    SliceObservedState state,
                                    List<KVCommand<AetherKey>> commands,
                                    Purpose purpose,
                                    int cycle) {
        return new InFlight(correlation, version, state, commands, 1, purpose, cycle);
    }

    public InFlight retried(long newCorrelation) {
        return new InFlight(newCorrelation, version, state, commands, attempt + 1, purpose, cycle);
    }

    public InFlight nextCycle(long newCorrelation) {
        return new InFlight(newCorrelation, version, state, commands, 1, purpose, cycle + 1);
    }
}
