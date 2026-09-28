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
/// - [#currentId] — the committed per-incarnation ULID (#1625), absent before genesis.
/// - [#genesisCommand] — the leader's mint for a cluster that has none yet (incarnation 1, fresh lineage).
/// - [#restoreCommands] — what a restore (#1533) commits: the restored lineage, above every incarnation
///   the backup has recorded for it.
public sealed interface ClusterIncarnation {
    /// The value [#current] answers before genesis.
    long NONE = 0L;

    /// The committed incarnation, or [#NONE] before genesis.
    static long current(KVStore<AetherKey, AetherValue> kvStore) {
        return committed(kvStore).map(ClusterIncarnationValue::incarnation)
                        .or(NONE);
    }

    /// The committed per-incarnation ULID, when genesis has happened. An identity for equality only.
    static Option<String> currentId(KVStore<AetherKey, AetherValue> kvStore) {
        return committed(kvStore).map(ClusterIncarnationValue::incarnationId);
    }

    /// The committed lineage and incarnation, when genesis has happened.
    static Option<ClusterIncarnationValue> committed(KVStore<AetherKey, AetherValue> kvStore) {
        return kvStore.getTyped(ClusterIncarnationKey.clusterIncarnationKey(), ClusterIncarnationValue.class);
    }

    /// The genesis write for a cluster with no committed incarnation; absent once one exists. Racing
    /// mints resolve first-wins in the applier ([ClusterIncarnationValue] is version-fenced), so a caller
    /// confirms by re-reading [#committed] rather than trusting that its own write landed.
    static Option<KVCommand<AetherKey>> genesisCommand(KVStore<AetherKey, AetherValue> kvStore,
                                                       Supplier<String> freshLineageId,
                                                       Supplier<String> freshIncarnationId) {
        return committed(kvStore).isPresent()
               ? Option.none()
               : Option.some(put(ClusterIncarnationValue.genesis(freshLineageId.get(), freshIncarnationId.get())));
    }

    /// The commands a restore applies in ONE batch, after the restored state is in place: keep the
    /// restored lineage at `max(restored, highestRecordedForLineage) + 1`, under the fresh
    /// `incarnationId` — never the restored one, so a reused number cannot pass for the old incarnation.
    ///
    /// The floor is what makes a restore monotonic. Restoring an OLDER backup of the lineage (L@3 while
    /// the backup store has recorded L@7) must not go back to L@4, and restoring L@5 must not reuse L@6,
    /// which already names a different history — both land at L@8. `highestRecordedForLineage` is the
    /// highest incarnation the backup store has ever recorded for that lineage across its whole history
    /// (#1533 scans it); a caller without that scan passes `restored.incarnation()`.
    ///
    /// **Residual `[unverified]`:** an incarnation that ran but whose key never reached the backup before a
    /// crash is invisible to the floor and can be reused. #1532 narrows the window by flushing an
    /// incarnation change immediately, as the first commit of the new incarnation.
    ///
    /// The value is removed first so the Put is a first write: it must land even when this cluster
    /// already minted its own genesis before the restore ran, which the successor fence would refuse.
    /// This bypasses the fence by design; monotonicity here comes from the floor.
    static List<KVCommand<AetherKey>> restoreCommands(ClusterIncarnationValue restored,
                                                      long highestRecordedForLineage,
                                                      String incarnationId) {
        var next = Math.max(restored.incarnation(), highestRecordedForLineage) + 1;

        return List.of(new KVCommand.Remove<>(ClusterIncarnationKey.clusterIncarnationKey()),
                       put(ClusterIncarnationValue.clusterIncarnationValue(restored.lineageId(), next, incarnationId)));
    }

    private static KVCommand<AetherKey> put(ClusterIncarnationValue value) {
        return new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(), value);
    }

    record unused() implements ClusterIncarnation {}
}
