// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;


/// The committed cluster incarnation (#1529): the dominant component of every [org.pragmatica.aether.slice.generation.Epoch]
/// and [org.pragmatica.aether.slice.generation.RewindEpoch] this node mints, and the value workers learn
/// through the metadata channel. Minted at genesis (incarnation 1) and incremented by every restore.
///
/// STUB until #1529 part 1 lands: the committed `AetherKey.ClusterIncarnationKey` →
/// `AetherValue.ClusterIncarnationValue(lineageId, incarnation)` does not exist yet, so this reads as `0`
/// — the pre-genesis value, under which every epoch orders exactly as it did before #1529. Part 1
/// replaces the body with the committed read and keeps this signature.
public sealed interface ClusterIncarnation {
    /// The committed cluster incarnation in `kvStore`, or `0` before genesis mints one.
    static long current(KVStore<AetherKey, AetherValue> kvStore) {
        return 0L;
    }

    record unused() implements ClusterIncarnation {}
}
