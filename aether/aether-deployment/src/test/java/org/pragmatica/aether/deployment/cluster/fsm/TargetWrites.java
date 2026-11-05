// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;

/// The `SliceTargetValue`s a leader proposed for one target key, whatever form the write took. Since #2628 the target
/// writes of a blueprint are the mutations of a blueprint-fenced transaction; an unowned slice's resubmission is still
/// a plain put.
final class TargetWrites {
    private TargetWrites() {}

    static List<SliceTargetValue> proposed(List<KVCommand<AetherKey>> commands, SliceTargetKey key) {
        return commands.stream().flatMap(command -> values(command, key)).toList();
    }

    /// Whether any command removes the target key: a bare `Remove`, or a transaction mutation with no replacement.
    static boolean removes(List<KVCommand<AetherKey>> commands, SliceTargetKey key) {
        return commands.stream().anyMatch(command -> removes(command, key));
    }

    private static boolean removes(KVCommand<AetherKey> command, SliceTargetKey key) {
        return switch (command) {
            case KVCommand.Remove<AetherKey> remove -> key.equals(remove.key());
            case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transaction.mutations()
                                                                                       .stream()
                                                                                       .anyMatch(mutation -> key.equals(mutation.key())
                                                                                                             && mutation.replacement().isEmpty());
            default -> false;
        };
    }

    private static java.util.stream.Stream<SliceTargetValue> values(KVCommand<AetherKey> command, SliceTargetKey key) {
        return switch (command) {
            case KVCommand.Put<AetherKey, ?> put when key.equals(put.key()) && put.value() instanceof SliceTargetValue value -> java.util.stream.Stream.of(value);
            case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transaction.mutations()
                                                                                       .stream()
                                                                                       .filter(mutation -> key.equals(mutation.key()))
                                                                                       .flatMap(mutation -> mutation.replacement().stream())
                                                                                       .filter(SliceTargetValue.class::isInstance)
                                                                                       .map(SliceTargetValue.class::cast);
            default -> java.util.stream.Stream.empty();
        };
    }
}
