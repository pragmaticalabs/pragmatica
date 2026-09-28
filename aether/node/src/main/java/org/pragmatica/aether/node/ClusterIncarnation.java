// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;


/// The single authority for the cluster's lineage and incarnation (#1529 part 1). Everything that needs
/// either reads it here, from the committed [ClusterIncarnationKey] — there is no second source.
///
/// - [#current] — the committed incarnation, or `0` before genesis has minted one.
/// - [#genesisCommand] — the leader's mint for a cluster that has none yet (incarnation 1, fresh lineage).
/// - [#restoreCommands] — what a restore (#1533) commits: the restored lineage, one incarnation later.
public sealed interface ClusterIncarnation {
    /// The value [#current] answers before genesis.
    long NONE = 0L;

    /// The committed incarnation, or [#NONE] before genesis.
    static long current(KVStore<AetherKey, AetherValue> kvStore) {
        return committed(kvStore).map(ClusterIncarnationValue::incarnation)
                        .or(NONE);
    }

    /// The committed lineage and incarnation, when genesis has happened.
    static Option<ClusterIncarnationValue> committed(KVStore<AetherKey, AetherValue> kvStore) {
        return kvStore.getTyped(ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.class);
    }

    /// The genesis write for a cluster with no committed incarnation; absent once one exists. Racing
    /// mints resolve first-wins in the applier ([ClusterIncarnationValue] is version-fenced), so a caller
    /// confirms by re-reading [#committed] rather than trusting that its own write landed.
    static Option<KVCommand<AetherKey>> genesisCommand(KVStore<AetherKey, AetherValue> kvStore,
                                                       Supplier<String> freshLineageId) {
        return committed(kvStore).isPresent()
               ? Option.none()
               : Option.some(put(ClusterIncarnationValue.genesis(freshLineageId.get())));
    }

    /// The commands a restore applies in ONE batch, after the restored state is in place: keep the
    /// restored lineage and move to the next incarnation. The value is removed first so the increment is
    /// a first write — it must land even when this cluster already minted its own genesis before the
    /// restore ran, which the successor fence would otherwise refuse.
    static List<KVCommand<AetherKey>> restoreCommands(ClusterIncarnationValue restored) {
        return List.of(new KVCommand.Remove<>(ClusterIncarnationKey.clusterIncarnationKey()), put(restored.next()));
    }

    private static KVCommand<AetherKey> put(ClusterIncarnationValue value) {
        return new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(), value);
    }

    record unused() implements ClusterIncarnation {}
}
