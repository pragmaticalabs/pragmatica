// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;


/// Another node's observed row as the replica index holds it, with the boot token the delivering process's registry
/// knew for that node when the row arrived (`0` = unknown, spec C8). The token is part of the snapshot, so the
/// FSM judges staleness without reading a registry (spec R-3.1.1).
public record PeerRow(SliceObservedValue value, long knownToken) {
    public static PeerRow peerRow(SliceObservedValue value, long knownToken) {
        return new PeerRow(value, knownToken);
    }

    /// Stale on positive evidence only: both tokens are known and differ (spec R-1.6.2). Unknown reads as current.
    public boolean isStale() {
        var rowToken = value.incarnation().bootToken();

        return rowToken != 0L
               && knownToken != 0L
               && rowToken != knownToken;
    }
}
