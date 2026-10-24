// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

/// Shared fixtures of the replica ring tests: a stub incarnation key family, a text serializer whose output is deterministic and readable
/// (so a snapshot's content can be asserted), and helpers that commit a batch the way the engine does (`processCommitted`, then the phase hook).
final class RingFixtures {
    private RingFixtures() {}

    record Key(String id) implements StructuredKey {}

    /// The stand-in for the cluster incarnation key.
    record IncKey() implements StructuredKey {
        static final IncKey INSTANCE = new IncKey();
    }

    record IncValue(long incarnation, String id) {}

    static final IncarnationSource SOURCE = new IncarnationSource() {
        @Override
        public IncarnationTag read(Function<StructuredKey, Object> lookup) {
            return lookup.apply(IncKey.INSTANCE) instanceof IncValue value
                   ? IncarnationTag.incarnationTag(value.incarnation(), value.id())
                   : IncarnationTag.NONE;
        }

        @Override
        public boolean isIncarnationKey(StructuredKey key) {
            return key instanceof IncKey;
        }
    };

    /// Writes the map sorted by key text, so equal states give equal bytes.
    static final Serializer TEXT = new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {
            buffer.writeBytes(text(value).getBytes(StandardCharsets.UTF_8));
        }
    };

    static String text(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, String>();

            map.forEach((key, entry) -> sorted.put(String.valueOf(key), String.valueOf(entry)));

            return sorted.toString();
        }

        return String.valueOf(value);
    }

    static Deserializer deserializerReturning(Map<?, ?> map) {
        return new Deserializer() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T read(ByteBuf buffer) {
                return (T) map;
            }
        };
    }

    static KVStore<StructuredKey, Object> store() {
        return store(ReplicaKeyFilter.includeAll(), CommitRing.DEFAULT_BUDGET_BYTES);
    }

    /// Subscriber closes run on the thread that decided them, after the ring lock is released, so a test can assert them right after the
    /// write. The production default (an async close executor) is pinned by `KVStoreReplicaRingTest`.
    static KVStore<StructuredKey, Object> store(ReplicaKeyFilter filter, long ringBytes) {
        return new KVStore<>(MessageRouter.mutable(),
                             TEXT,
                             deserializerReturning(Map.of()),
                             filter,
                             SOURCE,
                             ringBytes,
                             ValueSizeTable.valueSizeTable(),
                             Runnable::run);
    }

    static KVCommand<StructuredKey> put(String key, Object value) {
        return new KVCommand.Put<>(new Key(key), value);
    }

    static KVCommand<StructuredKey> remove(String key) {
        return new KVCommand.Remove<>(new Key(key));
    }

    static KVCommand<StructuredKey> putIncarnation(long incarnation, String id) {
        return new KVCommand.Put<>(IncKey.INSTANCE, new IncValue(incarnation, id));
    }

    /// Commits one batch at `slot` the way the engine does: `processCommitted`, then the phase hook for the same slot.
    @SafeVarargs
    static void commit(KVStore<StructuredKey, Object> store, long slot, KVCommand<StructuredKey>... commands) {
        store.processCommitted(store.createBatch(List.of(commands)), slot + 1);
        store.onPhaseCompleted(slot + 1);
    }

    /// Slot 0 declares the incarnation: the bump batch (not delivered). Slots from 1 are ordinary.
    static KVStore<StructuredKey, Object> genesisStore() {
        var store = store();

        commit(store, 0, putIncarnation(1, "A"));

        return store;
    }
}
