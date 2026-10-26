// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.IncarnationSource;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.cluster.state.kvstore.ValueSizeTable;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

/// Fixtures of the replica feed server tests: a store with a stub incarnation key family and a readable text serializer, and a harness that
/// captures what the server sends and lets a test drive the gate, the peers and the clock.
final class FeedFixtures {
    private FeedFixtures() {}

    record Key(String id) implements StructuredKey {}

    record IncKey() implements StructuredKey {
        static final IncKey INSTANCE = new IncKey();
    }

    record IncValue(long incarnation, String id) {}

    static final NodeId CORE = NodeId.nodeId("core-1").unwrap();
    static final NodeId PEER = NodeId.nodeId("peer-1").unwrap();
    static final NodeId OTHER = NodeId.nodeId("peer-2").unwrap();
    static final NodeId THIRD = NodeId.nodeId("peer-3").unwrap();
    static final IncarnationTag TAG = IncarnationTag.incarnationTag(1, "A");

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

    static final Deserializer NOTHING = new Deserializer() {
        @Override
        public <T> T read(ByteBuf buffer) {
            return null;
        }
    };

    static KVStore<StructuredKey, Object> store() {
        return new KVStore<>(MessageRouter.mutable(),
                             TEXT,
                             NOTHING,
                             ReplicaKeyFilter.includeAll(),
                             SOURCE,
                             CommitRing.DEFAULT_BUDGET_BYTES,
                             ValueSizeTable.valueSizeTable(),
                             Runnable::run);
    }

    static KVCommand<StructuredKey> put(String key, Object value) {
        return new KVCommand.Put<>(new Key(key), value);
    }

    /// Commits one batch at `slot` the way the engine does: `processCommitted`, then the phase hook for the same slot.
    @SafeVarargs
    static void commit(KVStore<StructuredKey, Object> store, long slot, KVCommand<StructuredKey>... commands) {
        store.processCommitted(store.createBatch(List.of(commands)), slot + 1);
        store.onPhaseCompleted(slot + 1);
    }

    /// Slot 0 declares the incarnation `A` (the bump batch, not delivered).
    static KVStore<StructuredKey, Object> genesisStore() {
        var store = store();

        commit(store, 0, new KVCommand.Put<>(IncKey.INSTANCE, new IncValue(1, "A")));

        return store;
    }

    record Sent(NodeId peer, ProtocolMessage message) {}

    /// A server over a genesis store, with the gate, the eligible and connected peers and the clock under the test's control. Work handed to the
    /// serve executor runs on the calling thread unless a test replaces `executor`.
    static final class Harness {
        final KVStore<StructuredKey, Object> store = genesisStore();
        final List<Sent> sent = new CopyOnWriteArrayList<>();
        final AtomicBoolean serving = new AtomicBoolean(true);
        final Set<NodeId> eligible = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<NodeId> connected = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final AtomicReference<Executor> executor = new AtomicReference<>(Runnable::run);
        /// Runs after each message is captured, on the sending thread: lets a test interleave a write or a tick with the server's own sends.
        final AtomicReference<java.util.function.Consumer<Sent>> onSend = new AtomicReference<>(_ -> {});
        final ReplicaFeedServer<StructuredKey, Object> server;

        Harness() {
            this(ReplicaFeedLimits.DEFAULT);
        }

        Harness(ReplicaFeedLimits limits) {
            eligible.addAll(Set.of(PEER, OTHER, THIRD));
            connected.addAll(Set.of(PEER, OTHER, THIRD));
            server = ReplicaFeedServer.replicaFeedServer(CORE,
                                                         store,
                                                         ReplicaKeyFilter.includeAll(),
                                                         this::capture,
                                                         serving::get,
                                                         eligible::contains,
                                                         connected::contains,
                                                         task -> executor.get().execute(task),
                                                         nanos::get,
                                                         limits);
        }

        private void capture(NodeId peer, ProtocolMessage message) {
            var captured = new Sent(peer, message);

            sent.add(captured);
            onSend.get().accept(captured);
        }

        @SafeVarargs
        final void commit(long slot, KVCommand<StructuredKey>... commands) {
            FeedFixtures.commit(store, slot, commands);
        }

        <T extends ProtocolMessage> List<T> sentTo(NodeId peer, Class<T> type) {
            return sent.stream()
                       .filter(s -> s.peer().equals(peer))
                       .map(Sent::message)
                       .filter(type::isInstance)
                       .map(type::cast)
                       .toList();
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }
    }
}
