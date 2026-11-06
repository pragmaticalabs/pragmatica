// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;

/// The commands a recording cluster node reports for a batch, with every [KVCommand.LeaderTransaction] replaced by its
/// mutations (#2584 P0). A committed leader record naming SELF is always present once Active is entered, so a rollback
/// is ONE transaction, never a plain batch; its mutations are the commands the plain batch used to carry, in the same
/// order and still atomic, which is what these fixtures' tests assert on.
final class TransactionEffects {
    private TransactionEffects() {}

    static List<KVCommand<AetherKey>> effective(List<KVCommand<AetherKey>> batch) {
        return batch.stream()
                    .flatMap(TransactionEffects::effective)
                    .toList();
    }

    @SuppressWarnings("unchecked")
    private static Stream<KVCommand<AetherKey>> effective(KVCommand<AetherKey> command) {
        return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
               ? ((KVCommand.LeaderTransaction<AetherKey, AetherValue>) transaction).mutations()
                                                                                   .stream()
                                                                                   .map(TransactionEffects::mutationCommand)
               : Stream.of(command);
    }

    private static KVCommand<AetherKey> mutationCommand(KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        return mutation.replacement()
                       .<KVCommand<AetherKey>> map(value -> new KVCommand.Put<>(mutation.key(), value))
                       .or(() -> new KVCommand.Remove<>(mutation.key()));
    }
}
