// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// One atomic write contract for hierarchy intent and observed placement facts.
/// Both current local activity and the committed leader are required; refusal is submitter-visible.
public record HierarchyStateWriter(Supplier<Option<LeaderValue>> authority,
                                   Function<AetherKey, Option<AetherValue>> read,
                                   Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply) {
    public enum Refusal implements Cause {
        INACTIVE,
        CONFLICT;
        @Override
        public String message() {
            return this == INACTIVE
                   ? "Active committed core authority is required"
                   : "Hierarchy state changed before conditional commit";
        }
    }

    public static HierarchyStateWriter hierarchyStateWriter(Supplier<Option<LeaderValue>> authority,
                                                            Function<AetherKey, Option<AetherValue>> read,
                                                            Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> apply) {
        return new HierarchyStateWriter(authority, read, apply);
    }

    public static HierarchyStateWriter unavailable() {
        return hierarchyStateWriter(Option::none, _ -> Option.none(), _ -> Refusal.INACTIVE.promise());
    }

    public HierarchyStateWriter whileActive(BooleanSupplier active) {
        return hierarchyStateWriter(() -> authority.get()
                                                   .filter(_ -> active.getAsBoolean()),
                                    read,
                                    apply);
    }

    public Promise<Unit> put(AetherKey key, Option<AetherValue> expected, AetherValue value) {
        return commit(List.of(new KVCommand.Mutation<>(key, expected, Option.some(value))),
                      List.of());
    }

    public Promise<Unit> commit(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations,
                                List<KVCommand.ReadWitness<AetherKey>> guards) {
        if (mutations.isEmpty()) {
            return Promise.unitPromise();
        }

        var key = mutations.getFirst().key();

        return authority.get()
                        .fold(() -> Refusal.INACTIVE.promise(),
                              leader -> {
                                  var id = UUID.randomUUID().toString();
                                  var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                        id,
                                                                                                        leader,
                                                                                                        guards,
                                                                                                        mutations);

                                  return apply.apply(List.of(command))
                                              .flatMap(results -> results.stream()
                                                                         .filter(KVCommand.TransactionResult.class::isInstance)
                                                                         .map(KVCommand.TransactionResult.class::cast)
                                                                         .anyMatch(result -> result.transactionId()
                                                                                                   .equals(id) && result.accepted())
                                                                  ? Promise.unitPromise()
                                                                  : Refusal.CONFLICT.promise());
                              });
    }
}
