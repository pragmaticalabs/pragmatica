// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GenerationSnapshotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GenerationSnapshotValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Option;


public record KvBackedGenerationSnapshotSource(KVStore<AetherKey, AetherValue> kvStore) implements GenerationSnapshotSource {
    public static KvBackedGenerationSnapshotSource kvBackedGenerationSnapshotSource(KVStore<AetherKey, AetherValue> kvStore) {
        return new KvBackedGenerationSnapshotSource(kvStore);
    }

    @Override
    public Option<MembershipView> currentMembershipView() {
        return readSnapshot().map(v -> SnapshotMembershipView.from(v.snapshot()));
    }

    @Override
    public long observedRabiaTerm() {
        return readSnapshot().map(v -> v.snapshot()
                                        .epoch()
                                        .rabiaTerm())
                           .or(0L);
    }

    private Option<GenerationSnapshotValue> readSnapshot() {
        return kvStore.getTyped(GenerationSnapshotKey.SINGLETON, GenerationSnapshotValue.class);
    }
}
