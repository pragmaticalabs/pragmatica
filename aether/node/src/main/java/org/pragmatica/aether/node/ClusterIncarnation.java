// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
/// - [#restoring] — the incarnation a restore (#1533) commits: the restored lineage, above every incarnation
///   the backup has recorded for it.
/// - [#superseding]/[#supersedeCommands] — what `aether backup declare-genesis` commits (#1532).
/// - [#replaceCommands] — the one write both of them use to replace a committed incarnation.
///
/// [ObservedIncarnation] mirrors [#current] from the key's notifications for threads that must not take the
/// `KVStore` monitor (network threads: the QUIC and SWIM loops); it is fed from here, never written independently.
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

    /// The incarnation a restore commits ([#replaceCommands]), after the restored state is in place: the
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
    /// It replaces whatever this cluster committed meanwhile (its own genesis, minted before the restore ran,
    /// or a leader's without a restore gate): not a successor step, so the successor fence is sidestepped
    /// by removing first ([#replaceCommands]). Monotonicity here comes from the floor.
    static ClusterIncarnationValue restoring(ClusterIncarnationValue restored,
                                             long highestRecordedForLineage,
                                             String incarnationId) {
        var next = Math.max(restored.incarnation(), highestRecordedForLineage) + 1;

        return ClusterIncarnationValue.clusterIncarnationValue(restored.lineageId(), next, incarnationId);
    }

    /// The incarnation `aether backup declare-genesis` moves this cluster to (#1532): its own lineage, past
    /// its own incarnation, the head's, AND every incarnation the backup has recorded for its lineage —
    /// the same floor a restore clears ([#restoring]). Never backwards — a cluster at L@9 declaring
    /// over another lineage's head at 3 goes to L@10, never L@4, which would reuse an incarnation L already
    /// ran; and a cluster at L@1 over a history that recorded L@7 goes to L@8, never L@4. The new
    /// incarnation gets a fresh `incarnationId` (#1625), never `current`'s: it is a different incarnation, and
    /// sharing the id would make the two compare equal.
    static ClusterIncarnationValue superseding(ClusterIncarnationValue current,
                                               long headIncarnation,
                                               long highestRecordedForLineage,
                                               String freshIncarnationId) {
        var next = Math.max(Math.max(current.incarnation(), headIncarnation),
                            highestRecordedForLineage) + 1;

        return ClusterIncarnationValue.clusterIncarnationValue(current.lineageId(), next, freshIncarnationId);
    }

    /// The declaration's write ([#replaceCommands]); it committed iff BOTH transactions are accepted, which the
    /// caller checks with [#supersedeAccepted], never a re-read that its own write could satisfy.
    static List<KVCommand<AetherKey>> supersedeCommands(LeaderValue leader,
                                                        String transactionId,
                                                        ClusterIncarnationValue current,
                                                        ClusterIncarnationValue next) {
        return replaceCommands(leader, transactionId, Option.some(current), next, List.of(), List.of());
    }

    /// Replaces the committed incarnation with `next`, which is not its successor: two leader transactions in ONE
    /// batch, applied in order. A transaction cannot remove and re-put one key (the applier refuses a key mutated
    /// twice), and folding the two into one mutation is refused by the successor fence (#1533), so:
    ///
    /// - **T1** removes the incarnation only while it still holds `current`, under `guards`; absent when nothing
    ///   is committed.
    /// - **T2** writes `next` only where T1 left the key empty (a first write), with `withPut`.
    ///
    /// **All or none.** One batch is one consensus decision, applied in one go and never interleaved with another
    /// batch, so nothing runs between T1 and T2. T2 carries no guard of its own when T1 is present, and every
    /// `withPut` mutation must expect a value T1's `guards` witness: then T1's acceptance establishes every
    /// precondition of T2 (same leader, the key empty, the witnessed values unchanged), and "T1 in, T2 out" cannot
    /// happen. A guard added to T2 alone would break that (pinned by `ClusterIncarnationTest` and
    /// `BackupRestoreCoordinatorTest`). Without T1, T2 carries the `guards` itself. Accepted iff
    /// [#replaceAccepted].
    static List<KVCommand<AetherKey>> replaceCommands(LeaderValue leader,
                                                      String transactionId,
                                                      Option<ClusterIncarnationValue> current,
                                                      ClusterIncarnationValue next,
                                                      List<KVCommand.ReadWitness<AetherKey>> guards,
                                                      List<KVCommand.Mutation<AetherKey, AetherValue>> withPut) {
        var put = transaction(leader,
                              transactionId + PUT_SUFFIX,
                              current.isPresent()
                              ? List.of()
                              : guards,
                              Stream.concat(Stream.of(incarnationMutation(Option.none(),
                                                                          Option.some(next))),
                                            withPut.stream())
                                    .toList());

        return current.map(committed -> List.of(transaction(leader,
                                                            transactionId + REMOVE_SUFFIX,
                                                            guards,
                                                            List.of(incarnationMutation(Option.some(committed),
                                                                                        Option.none()))),
                                                put))
                      .or(() -> List.of(put));
    }

    /// Whether every transaction of `commands` ([#replaceCommands]) was accepted.
    static boolean replaceAccepted(List<Object> results, List<KVCommand<AetherKey>> commands) {
        return commands.stream()
                       .filter(KVCommand.LeaderTransaction.class::isInstance)
                       .map(command -> ((KVCommand.LeaderTransaction<?, ?>) command).transactionId())
                       .allMatch(transactionId -> accepted(results, transactionId));
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
                                                    List<KVCommand.ReadWitness<AetherKey>> guards,
                                                    List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(ClusterIncarnationKey.clusterIncarnationKey(),
                                                                       transactionId,
                                                                       leader,
                                                                       guards,
                                                                       mutations);
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> incarnationMutation(Option<ClusterIncarnationValue> expected,
                                                                                  Option<ClusterIncarnationValue> replacement) {
        return new KVCommand.Mutation<>(ClusterIncarnationKey.clusterIncarnationKey(),
                                        expected.map(AetherValue.class::cast),
                                        replacement.map(AetherValue.class::cast));
    }

    private static KVCommand<AetherKey> put(ClusterIncarnationValue value) {
        return new KVCommand.Put<>(ClusterIncarnationKey.clusterIncarnationKey(), value);
    }

    record unused() implements ClusterIncarnation {}
}
