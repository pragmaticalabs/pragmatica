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
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;


/// The single authority for the cluster's lineage and incarnation (#1529 part 1). Everything that needs
/// either reads it here, from the committed [ClusterIncarnationKey] — there is no second source.
///
/// - [#current] — the committed incarnation, or `0` before genesis has minted one.
/// - [#currentId] — the committed per-incarnation ULID (#1625), absent before genesis.
/// - [#genesisCommand] — the leader's mint for a cluster that has none yet (incarnation 1, fresh lineage).
/// - [#restoreCommands] — what a restore (#1533) commits: the restored lineage, above every incarnation
///   the backup has recorded for it.
/// - [#superseding]/[#supersedeCommands] — what `aether backup declare-genesis` commits (#1532).
///
/// [ObservedIncarnation] mirrors [#current] from the key's notifications for threads that must not take the
/// `KVStore` monitor (the QUIC event loop); it is fed from here, never written independently.
public sealed interface ClusterIncarnation {
    /// The value [#current] answers before genesis.
    long NONE = 0L;
    String REMOVE_SUFFIX = ":remove";
    String PUT_SUFFIX = ":put";

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

    /// The incarnation `aether backup declare-genesis` moves this cluster to (#1532): its own lineage, past
    /// BOTH its own incarnation and the head's. Never backwards — a cluster at L@9 declaring over another
    /// lineage's head at 3 goes to L@10, never L@4, which would reuse an incarnation L already ran. The new
    /// incarnation gets a fresh `incarnationId` (#1625), never `current`'s: it is a different incarnation, and
    /// sharing the id would make the two compare equal.
    static ClusterIncarnationValue superseding(ClusterIncarnationValue current,
                                               long headIncarnation,
                                               String freshIncarnationId) {
        return ClusterIncarnationValue.clusterIncarnationValue(current.lineageId(),
                                                               Math.max(current.incarnation(), headIncarnation) + 1,
                                                               freshIncarnationId);
    }

    /// The declaration's write, as two leader transactions submitted in ONE batch and applied in order. The
    /// first removes the incarnation only while it still holds `current` — a read witness, so a concurrent
    /// restore or declaration makes it refuse instead of being overwritten. The second writes `next` only
    /// where the first left the key empty (the jump is not a successor step, so it must be a first write).
    /// The declaration committed iff BOTH are accepted; the caller checks [#supersedeAccepted], never a
    /// re-read that its own write could satisfy.
    static List<KVCommand<AetherKey>> supersedeCommands(LeaderValue leader,
                                                        String transactionId,
                                                        ClusterIncarnationValue current,
                                                        ClusterIncarnationValue next) {
        return List.of(transaction(leader, transactionId + REMOVE_SUFFIX, Option.some(current), Option.none()),
                       transaction(leader, transactionId + PUT_SUFFIX, Option.none(), Option.some(next)));
    }

    /// Whether both transactions of [#supersedeCommands] were accepted.
    static boolean supersedeAccepted(List<Object> results, String transactionId) {
        return accepted(results, transactionId + REMOVE_SUFFIX) && accepted(results, transactionId + PUT_SUFFIX);
    }

    private static boolean accepted(List<Object> results, String transactionId) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.transactionId()
                                                .equals(transactionId) && result.accepted());
    }

    private static KVCommand<AetherKey> transaction(LeaderValue leader,
                                                    String transactionId,
                                                    Option<ClusterIncarnationValue> expected,
                                                    Option<ClusterIncarnationValue> replacement) {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                                       transactionId,
                                                                       leader,
                                                                       List.of(),
                                                                       List.of(new KVCommand.Mutation<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                                                                        expected.map(AetherValue.class::cast),
                                                                                                        replacement.map(AetherValue.class::cast))));
    }

    private static KVCommand<AetherKey> put(ClusterIncarnationValue value) {
        return new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(), value);
    }

    record unused() implements ClusterIncarnation {}
}
