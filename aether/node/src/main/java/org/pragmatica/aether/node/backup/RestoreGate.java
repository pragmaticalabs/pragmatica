// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// #1533 — the central restore gate: a mechanism, not a convention.
///
/// On a node with `[backup]` enabled, every command batch this node submits to consensus passes through
/// here first (installed on the Rabia engine's single submit entry, `RabiaEngine#submitCommands`, which
/// both a local `apply` and a forwarded worker submission reach). Until the node holds a TERMINAL restore
/// decision ([BackupRestoreValue] under [BackupRestoreKey]), a batch that writes any backed-up key
/// ([BackupEntryCodec#isBackedUp]) — a `Put`, a `Remove`, or a `LeaderTransaction` mutation — is refused
/// whole with [RestorePending], a retryable cause. Every leader-gain seeder therefore backs off on its own
/// retry loop instead of racing the restore, whether or not it knows the restore exists.
///
/// Two exceptions, and only two: a `LeaderTransaction` whose id starts with [#RESTORE_TRANSACTION_PREFIX]
/// (the restore's own chunks and its final transaction), and anything touching only runtime keys (the
/// marker itself is one). Each node refuses its OWN submissions; nothing is enforced in the applier, where
/// a decision that differed across nodes would diverge the replicas.
public sealed interface RestoreGate {
    /// Transaction-id prefix of the restore's own writes — the only backed-up writes admitted before the
    /// decision is terminal.
    String RESTORE_TRANSACTION_PREFIX = "kv-restore:";

    /// A backed-up key was written before this node's restore decision committed. Retryable: the gate
    /// opens as soon as the decision lands.
    record RestorePending(String key, String message) implements Cause {
        static final Fn1<RestorePending, String> FACTORY = Causes.forOneValue("Write to '%s' refused: the cluster's backup restore decision has not committed yet; retry",
                                                                              RestorePending::new);
    }

    /// The guard installed on the consensus engine.
    static Function<List<KVCommand<AetherKey>>, Result<Unit>> restoreGate(KVStore<AetherKey, AetherValue> kvStore) {
        return commands -> admit(kvStore, commands);
    }

    /// Admit `commands` unless the gate is closed and one of them writes a backed-up key.
    static Result<Unit> admit(KVStore<AetherKey, AetherValue> kvStore, List<KVCommand<AetherKey>> commands) {
        return firstGatedWrite(commands).filter(_ -> !isOpen(kvStore))
                              .map(key -> RestorePending.FACTORY.apply(key.toString()).<Unit> result())
                              .or(Result::unitResult);
    }

    /// Open once this node holds a terminal restore decision.
    static boolean isOpen(KVStore<AetherKey, AetherValue> kvStore) {
        return decision(kvStore).filter(value -> value.outcome()
                                                      .isTerminal())
                       .isPresent();
    }

    /// The committed restore decision, if any.
    static Option<BackupRestoreValue> decision(KVStore<AetherKey, AetherValue> kvStore) {
        return kvStore.getTyped(BackupRestoreKey.backupRestoreKey(), BackupRestoreValue.class);
    }

    private static Option<AetherKey> firstGatedWrite(List<KVCommand<AetherKey>> commands) {
        return Option.from(commands.stream().flatMap(RestoreGate::gatedKeys).findFirst());
    }

    /// Keys are read as `Object`: the log also carries foreign-typed atoms under the `AetherKey` parameter
    /// (the leader election's `LeaderKey`), and a typed read would throw inside the submit path.
    private static Stream<AetherKey> gatedKeys(KVCommand<AetherKey> command) {
        return switch (command) {
            case KVCommand.Put<AetherKey, ?> put -> backedUp(keyOf(put));
            case KVCommand.Remove<AetherKey> remove -> backedUp(keyOf(remove));
            case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transactionKeys(transaction);
            case KVCommand.Get<AetherKey>_, KVCommand.Noop<AetherKey>_ -> Stream.empty();
        };
    }

    private static Stream<AetherKey> transactionKeys(KVCommand.LeaderTransaction<AetherKey, ?> transaction) {
        return transaction.transactionId()
                          .startsWith(RESTORE_TRANSACTION_PREFIX)
               ? Stream.empty()
               : transaction.mutations()
                            .stream()
                            .flatMap(mutation -> backedUp(keyOf(mutation)));
    }

    @SuppressWarnings("rawtypes")
    private static Object keyOf(KVCommand command) {
        return command.key();
    }

    @SuppressWarnings("rawtypes")
    private static Object keyOf(KVCommand.Mutation mutation) {
        return mutation.key();
    }

    private static Stream<AetherKey> backedUp(Object key) {
        return key instanceof AetherKey aetherKey && BackupEntryCodec.isBackedUp(aetherKey)
               ? Stream.of(aetherKey)
               : Stream.empty();
    }

    record unused() implements RestoreGate {}
}
