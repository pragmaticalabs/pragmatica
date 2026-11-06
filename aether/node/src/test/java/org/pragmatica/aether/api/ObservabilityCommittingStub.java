// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ObservabilityConfigValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.mockito.Mockito;

/// A write-side [ObservabilityConfigRegistry] over a node stub that behaves like the real one on commit: the
/// notification handler runs before the apply promise resolves (`KVStore.mutate` publishes inside the apply). The
/// registry's write methods no longer touch local state themselves (#2616), so a test that reads back what it just
/// wrote needs the commit's own notification to have happened - this stub supplies exactly that and nothing more.
final class ObservabilityCommittingStub {
    private ObservabilityCommittingStub() {}

    @SuppressWarnings("unchecked")
    static ObservabilityConfigRegistry registry(KVStore<AetherKey, AetherValue> kvStore, ObservabilityBaseline baseline) {
        var holder = new AtomicReference<ObservabilityConfigRegistry>();
        RabiaNode<KVCommand<AetherKey>> node = Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList()))
               .thenAnswer(invocation -> commit(holder.get(), invocation.getArgument(0)));
        holder.set(ObservabilityConfigRegistry.observabilityConfigRegistry(node, kvStore, baseline));

        return holder.get();
    }

    private static Promise<List<Object>> commit(ObservabilityConfigRegistry registry, List<KVCommand<AetherKey>> commands) {
        commands.forEach(command -> notifyCommitted(registry, command));

        return Promise.success(List.of(Unit.unit()));
    }

    private static void notifyCommitted(ObservabilityConfigRegistry registry, KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<?, ?> put) {
            registry.onObservabilityConfigPut(putNotification(put));
        } else if (command instanceof KVCommand.Remove<?> remove) {
            registry.onObservabilityConfigRemove(removeNotification(remove));
        }
    }

    @SuppressWarnings("unchecked")
    private static ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> putNotification(KVCommand.Put<?, ?> put) {
        return new ValuePut<>((KVCommand.Put<ObservabilityConfigKey, ObservabilityConfigValue>) put, Option.none());
    }

    @SuppressWarnings("unchecked")
    private static ValueRemove<ObservabilityConfigKey, ObservabilityConfigValue> removeNotification(KVCommand.Remove<?> remove) {
        return new ValueRemove<>((KVCommand.Remove<ObservabilityConfigKey>) remove, Option.none());
    }
}
