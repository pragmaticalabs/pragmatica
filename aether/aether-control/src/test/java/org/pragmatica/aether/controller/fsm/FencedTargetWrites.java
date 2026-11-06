// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

/// #2662: the autoscaler's target write is a leader transaction fenced on the value it read. A transaction is built only over a
/// committed leader and, for an owned slice, the owner's committed blueprint; the capturing cluster stubs answer it as the applier
/// would when nothing raced it.
final class FencedTargetWrites {
    private FencedTargetWrites() {}

    /// A store holding a committed leader and a committed blueprint for each owner.
    @SuppressWarnings({"unchecked", "rawtypes"})
    static KVStore<AetherKey, AetherValue> fencedStore(NodeId leader, BlueprintId... owners) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        store.process(store.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(leader, 1)))));
        Stream.of(owners).forEach(owner -> commitBlueprint(store, owner));

        return store;
    }

    private static void commitBlueprint(KVStore<AetherKey, AetherValue> store, BlueprintId owner) {
        var slice = ResolvedSlice.resolvedSlice(Artifact.artifact("org.example:owner-slice:1.0.0").unwrap(), 1, false).unwrap();
        var value = AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(owner, List.of(slice)));

        store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<AetherKey, AetherValue>(AppBlueprintKey.appBlueprintKey(owner),
                                                                                                                    value))));
    }

    /// The results of an apply in which every transaction was accepted, as the applier answers one nothing raced.
    static List<Object> acceptedResults(List<KVCommand<AetherKey>> batch) {
        return batch.stream().map(FencedTargetWrites::acceptedResult).toList();
    }

    private static Object acceptedResult(KVCommand<AetherKey> command) {
        return command instanceof KVCommand.LeaderTransaction<?, ?> transaction
               ? new KVCommand.TransactionResult(transaction.transactionId(), true)
               : Unit.unit();
    }

    /// The `SliceTargetValue`s written, whatever form the write took: a plain put, or the replacements of a transaction's mutations.
    static Stream<SliceTargetValue> targetValues(KVCommand<AetherKey> command) {
        return switch (command) {
            case KVCommand.Put<AetherKey, ?> put when put.value() instanceof SliceTargetValue value -> Stream.of(value);
            case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transaction.mutations()
                                                                                       .stream()
                                                                                       .flatMap(mutation -> mutation.replacement().stream())
                                                                                       .filter(SliceTargetValue.class::isInstance)
                                                                                       .map(SliceTargetValue.class::cast);
            default -> Stream.empty();
        };
    }

    /// The target keys written, whatever form the write took.
    static Stream<AetherKey> targetKeys(KVCommand<AetherKey> command) {
        return switch (command) {
            case KVCommand.Put<AetherKey, ?> put when put.value() instanceof SliceTargetValue -> Stream.of(put.key());
            case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transaction.mutations()
                                                                                       .stream()
                                                                                       .filter(mutation -> mutation.replacement()
                                                                                                                   .filter(SliceTargetValue.class::isInstance)
                                                                                                                   .isPresent())
                                                                                       .map(KVCommand.Mutation::key);
            default -> Stream.empty();
        };
    }

    static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {return null;}
        };
    }
}
