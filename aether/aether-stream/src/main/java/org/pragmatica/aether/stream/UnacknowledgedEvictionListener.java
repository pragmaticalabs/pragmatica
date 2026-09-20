// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.lang.Contract;


/// Told by a ring when DROP_OLDEST reclaims events ABOVE its visible position (#1352): `[fromOffset, toOffset]`
/// were appended but never acknowledged by the stream's min-sync peers, so they are dropped — not handed to the
/// [EvictionListener], never sealed, definitively not in the log. The partition manager fails the publishers'
/// pending awaits for them. Runs inside the ring's append section, so it must not run foreign code or block.
@FunctionalInterface
public interface UnacknowledgedEvictionListener {
    @Contract
    void onUnacknowledgedEviction(String streamName, int partition, long fromOffset, long toOffset);

    UnacknowledgedEvictionListener NOOP = (_, _, _, _) -> {};
}
