// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;

import java.util.ArrayList;
import java.util.List;

/// #1535 item 7: finds time stamps in committed placement commands that were NOT read from the injected
/// clock. The injected clock starts far below the wall clock, so any stamp above [#CEILING] came from
/// `System.currentTimeMillis()`.
final class PlacementStampAudit {
    /// First value the injected test clock hands out; the wall clock is about 1.7e12.
    static final long INJECTED_BASE = 5_000_000L;
    private static final long CEILING = 1_000_000_000L;

    private PlacementStampAudit() {}

    static List<String> wallClockStamps(List<? extends KVCommand<?>> commands) {
        var found = new ArrayList<String>();

        commands.forEach(command -> audit(command, found));
        return found;
    }

    private static void audit(KVCommand<?> command, List<String> found) {
        if (command instanceof KVCommand.Put<?, ?> put) {
            inspect(put.value(), found);
        } else if (command instanceof KVCommand.LeaderTransaction<?, ?> tx) {
            tx.mutations().forEach(mutation -> mutation.replacement().onPresent(value -> inspect(value, found)));
        }
    }

    private static void inspect(Object value, List<String> found) {
        switch (value) {
            case AetherValue.CommunityPlacementOperationValue op -> {
                flag(found, "operation.startedAt", op.startedAt());
                flag(found, "operation.phaseChangedAt", op.phaseChangedAt());
            }
            case AetherValue.CommunityPlacementAvailabilityValue availability -> flag(found, "availability.refusedAt", availability.refusedAt());
            case AetherValue.CommunityValue community -> community.dissolvedAt().onPresent(at -> flag(found, "community.dissolvedAt", at));
            default -> {}
        }
    }

    private static void flag(List<String> found, String field, long stamp) {
        if (stamp > CEILING) {
            found.add(field + "=" + stamp);
        }
    }
}
