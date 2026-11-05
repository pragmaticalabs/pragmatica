// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import org.pragmatica.cluster.replica.FeedFixtures.Key;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.state.kvstore.CommitRing;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.ReplicaKeyFilter;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.cluster.state.kvstore.ValueSizeTable;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.pragmatica.cluster.replica.FeedFixtures.IncKey;
import static org.pragmatica.cluster.replica.FeedFixtures.IncValue;
import static org.pragmatica.cluster.replica.FeedFixtures.SOURCE;

/// Fixtures of the replica feed client tests: stores whose snapshots round-trip (a map encodes to a handle into a side table, everything else
/// to its text), N cores each running the real [ReplicaFeedServer], one client, and an in-memory network the test pumps by hand.
final class ClientFixtures {
    private ClientFixtures() {}

    static final NodeId CLIENT = NodeId.nodeId("worker-1").unwrap();
    private static final Map<Long, Object> HANDLES = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_HANDLE = new AtomicLong(1);

    static final Serializer CODEC = new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {
            if (value instanceof Map<?, ?> map) {
                var handle = NEXT_HANDLE.getAndIncrement();

                HANDLES.put(handle, new HashMap<>(map));
                buffer.writeByte('H');
                buffer.writeLong(handle);
            } else {
                buffer.writeBytes(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            }
        }
    };

    static final Deserializer DECODE = new Deserializer() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf buffer) {
            buffer.readByte();

            return (T) HANDLES.get(buffer.readLong());
        }
    };

    static KVStore<StructuredKey, Object> store() {
        return store(MessageRouter.mutable());
    }

    static KVStore<StructuredKey, Object> store(MessageRouter router) {
        return new KVStore<>(router,
                             CODEC,
                             DECODE,
                             ReplicaKeyFilter.includeAll(),
                             SOURCE,
                             CommitRing.DEFAULT_BUDGET_BYTES,
                             ValueSizeTable.valueSizeTable(),
                             Runnable::run);
    }

    static KVCommand<StructuredKey> incarnation(long incarnation, String id) {
        return new KVCommand.Put<>(IncKey.INSTANCE, new IncValue(incarnation, id));
    }

    static KVCommand<StructuredKey> remove(String key) {
        return new KVCommand.Remove<>(new Key(key));
    }

    record Out(NodeId target, ProtocolMessage message) {}

    static final class Core {
        final NodeId id;
        final KVStore<StructuredKey, Object> store = store();
        final AtomicBoolean serving = new AtomicBoolean(true);
        final AtomicBoolean up = new AtomicBoolean(true);
        ReplicaFeedServer<StructuredKey, Object> server;

        Core(NodeId id) {
            this.id = id;
        }
    }

    /// N cores and one client on a manual clock and a manual network: `pump()` runs everything queued, `step()` also ticks every server and the
    /// client once (one heartbeat flush), `advance(ms)` moves the clock.
    static final class Net {
        final AtomicLong nanos = new AtomicLong(1_000_000_000L);
        final List<Core> cores = new ArrayList<>();
        final List<Out> outbox = new ArrayList<>();
        final List<ReplicaSnapshotChunk> heldChunks = new ArrayList<>();
        final AtomicBoolean holdChunks = new AtomicBoolean();
        final AtomicBoolean workerRole = new AtomicBoolean(true);
        final AtomicBoolean directoryInstalled = new AtomicBoolean(true);
        final AtomicInteger installs = new AtomicInteger();
        final List<Boolean> published = new ArrayList<>();
        final MessageRouter.MutableRouter clientRouter = MessageRouter.mutable();
        final KVStore<StructuredKey, Object> clientStore = store(clientRouter);
        final ReplicaApplier<StructuredKey, Object> applier;
        final ReplicaReadiness readiness;
        final ReplicaFeedClient<StructuredKey, Object> client;
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        Net(int coreCount) {
            this(coreCount, ReplicaClientLimits.DEFAULT, ReplicaFeedLimits.DEFAULT);
        }

        Net(int coreCount, ReplicaClientLimits limits, ReplicaFeedLimits serverLimits) {
            for (var i = 1; i <= coreCount; i++) {
                var core = new Core(NodeId.nodeId("core-" + i).unwrap());

                core.server = ReplicaFeedServer.replicaFeedServer(core.id,
                                                                  core.store,
                                                                  ReplicaKeyFilter.includeAll(),
                                                                  (peer, message) -> fromCore(core, message),
                                                                  core.serving::get,
                                                                  peer -> true,
                                                                  peer -> true,
                                                                  Runnable::run,
                                                                  nanos::get,
                                                                  serverLimits);
                cores.add(core);
            }

            applier = ReplicaApplier.replicaApplier(clientStore, limits.bufferBytes());
            readiness = ReplicaReadiness.replicaReadiness(nanos::get,
                                                          applier::position,
                                                          workerRole::get,
                                                          directoryInstalled::get,
                                                          published::add,
                                                          limits);
            client = ReplicaFeedClient.replicaFeedClient(CLIENT,
                                                         applier,
                                                         readiness,
                                                         this::fromClient,
                                                         () -> cores.stream().map(core -> core.id).toList(),
                                                         queue::add,
                                                         nanos::get,
                                                         limits,
                                                         installs::incrementAndGet,
                                                         new ReplicaFeedClient.GovernorArm(() -> false, Option::none));
        }

        Core core(int index) {
            return cores.get(index);
        }

        private void fromClient(NodeId target, ProtocolMessage message) {
            outbox.add(new Out(target, message));
            cores.stream()
                 .filter(core -> core.id.equals(target) && core.up.get())
                 .findFirst()
                 .ifPresent(core -> deliverToCore(core, message));
        }

        private void deliverToCore(Core core, ProtocolMessage message) {
            switch (message) {
                case ReplicaSubscribe subscribe -> core.server.onSubscribe(subscribe);
                case ReplicaSnapshotRequest request -> core.server.onSnapshotRequest(request);
                default -> throw new IllegalStateException("unexpected " + message);
            }
        }

        private void fromCore(Core core, ProtocolMessage message) {
            if (!core.up.get()) {
                return;
            }

            switch (message) {
                case ReplicaEntries entries -> client.onEntries(entries);
                case ReplicaHeartbeat beat -> client.onHeartbeat(beat);
                case ReplicaRefused refused -> client.onRefused(refused);
                case ReplicaSnapshotChunk chunk -> chunkOrHold(chunk);
                default -> throw new IllegalStateException("unexpected " + message);
            }
        }

        private void chunkOrHold(ReplicaSnapshotChunk chunk) {
            if (holdChunks.get()) {
                heldChunks.add(chunk);
            } else {
                client.onSnapshotChunk(chunk);
            }
        }

        /// Delivers the chunks held back, in order.
        void releaseChunks() {
            holdChunks.set(false);
            List.copyOf(heldChunks).forEach(client::onSnapshotChunk);
            heldChunks.clear();
        }

        void pump() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }

        /// One round: the client ticks, then every serving core drains its subscribers and flushes one heartbeat.
        void step() {
            client.tick();
            pump();
            cores.forEach(core -> {
                core.server.tick();
                core.server.heartbeat();
                core.server.tick();
            });
            pump();
        }

        void advance(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }

        /// Commits one batch on every core, the way the engine does (one shared batch, so every core records the same batch id).
        @SafeVarargs
        final void commitAll(long slot, KVCommand<StructuredKey>... commands) {
            var batch = cores.get(0).store.createBatch(List.of(commands));

            cores.forEach(core -> commitOn(core, batch, slot));
        }

        private static void commitOn(Core core, org.pragmatica.consensus.StateMachine.Batch<KVCommand<StructuredKey>> batch, long slot) {
            core.store.processCommitted(batch, slot + 1);
            core.store.onPhaseCompleted(slot + 1);
        }

        /// The genesis every test starts from: slot 0 declares incarnation `1/A`, slots 1.. carry data.
        void genesis() {
            commitAll(0, incarnation(1, "A"));
        }

        List<ReplicaSubscribe> subscribes() {
            return outbox.stream().map(Out::message).filter(ReplicaSubscribe.class::isInstance).map(ReplicaSubscribe.class::cast).toList();
        }

        /// The cores the subscribes went to, in order.
        List<NodeId> subscribeTargets() {
            return outbox.stream().filter(out -> out.message() instanceof ReplicaSubscribe).map(Out::target).toList();
        }

        long snapshotRequests() {
            return outbox.stream().filter(out -> out.message() instanceof ReplicaSnapshotRequest).count();
        }

        String valueOf(String key) {
            return clientStore.get(new Key(key)).map(String::valueOf).or("<absent>");
        }
    }
}
