// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Files;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.forward.AcceptingStreamForwardTransport;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.StorageInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward.readForward;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// #1759 (I-27, CTO D3): a replica that holds a partition without a ring, whose WAL starts above offset 0 because the prefix
/// below was sealed to the tier, serves a catch-up from 0 as the tier's sealed prefix followed by the WAL tail, as a
/// materialized partition serves its evicted prefix then its ring (#1383). `held` has forty 256 KiB events written by a
/// previous run with a four-event ring: offsets 0-35 sealed to the tier and truncated from the WAL (which compacts past
/// 8 MiB), 36-39 left in it. A partition not held
/// here is still answered unavailable, never an empty success.
class ReplicaWalTierPrefixCatchupTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER = NodeId.randomNodeId();
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final Epoch E2 = Epoch.epoch(0, 2, 0);
    private static final int EVENTS = 40;
    private static final int RING = 4;
    private static final int PAYLOAD_BYTES = 256 * 1024;
    private static final long SEALED_THROUGH = EVENTS - RING - 1L;
    private static final long AWAIT_MS = 10_000;
    private static final long POLL_NANOS = 10_000_000;
    private static final int PAGE = 64;

    @TempDir
    Path walDir;

    @TempDir
    Path storageDir;

    private final ConcurrentHashMap<String, Role> roles = new ConcurrentHashMap<>();
    private final List<ReadForwardResponse> answers = new CopyOnWriteArrayList<>();
    private final AtomicReference<Runnable> onTierRead = new AtomicReference<>();
    private SegmentIndex segmentIndex;
    private StorageInstance storage;
    private StreamPartitionManager manager;
    private StreamForwardHandler handler;

    @BeforeEach
    void sealAPrefixInAPreviousRun_thenHoldThePartitionPaced() {
        storage = DurableTestStorage.durableStorage("tier-prefix", storageDir);
        var index = new SegmentIndex();

        segmentIndex = index;
        var previous = streamPartitionManager(Long.MAX_VALUE,
                                              segmentSealer(storageSegmentSink(storage, index)),
                                              Option.some(walDir),
                                              index::lastSealedOffset);

        previous.createStream(heldConfig()).onFailure(cause -> fail(cause.message()));
        IntStream.range(0, EVENTS).forEach(i -> previous.publishLocal("held", 0, payload(i), 1000L + i).onFailure(cause -> fail(cause.message())));
        awaitCondition(() -> index.lastSealedOffset("held", 0) == SEALED_THROUGH);
        previous.truncateWalsToSealed();
        previous.close();
        assertThat(AppendLog.readRange(walFile(), 0, 1, 0).unwrap().records()).as("the WAL starts above the sealed prefix")
                            .extracting(AppendLog.WalRecord::offset)
                            .containsExactly(SEALED_THROUGH + 1);
        recordEpochStart();
        var registry = ReplicaRegistry.replicaRegistry();

        List.of("held", "elsewhere").forEach(stream -> registry.registerReplica(stream, 0, PEER));
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         EvictionListener.NOOP,
                                         ReplicationManager.replicationManager(SELF, registry),
                                         Option.some(walDir),
                                         index::lastSealedOffset);
        manager.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
        List.of(config("busy", 2), heldConfig(), config("elsewhere", 1)).forEach(c -> manager.onStreamConfigPut(configPut(c)));
        roles.put("busy", Role.REPLICA);
        roles.put("held", Role.REPLICA);
        manager.materializePartition("busy", 0).onFailure(_ -> fail("first slot should materialize"));
        manager.materializePartition("busy", 1).onFailure(_ -> fail("second slot should materialize"));
        manager.materializePartition("held", 0).onSuccess(_ -> fail("held[0] must be paced"));
        handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                            manager,
                                                            AcceptingStreamForwardTransport.accepting((_, message) -> record(message)),
                                                            64L * 1024 * 1024,
                                                            StreamReadForwardMetrics.NOOP,
                                                            Option.none(),
                                                            Option.some(tieredStreamReader(index, hookedTier(storage))));
    }

    @AfterEach
    void stop() {
        manager.close();
        storage.shutdown();
    }

    @Test
    void catchupRead_fromBelowTheWal_isTheTiersSealedPrefixThenTheWalTail() {
        var answer = catchupRead(0L, PAGE);

        assertThat(answer.success()).as("answer %s", answer.errorMessage()).isTrue();
        assertThat(answer.events()).extracting(RawEventDto::offset).containsExactlyElementsOf(LongStream.range(0, EVENTS).boxed().toList());
        assertThat(answer.events().get(0).data()).as("from the tier").isEqualTo(payload(0));
        assertThat(answer.events().get(EVENTS - 1).data()).as("from the WAL").isEqualTo(payload(EVENTS - 1));
        assertThat(manager.partitionBuffer("held", 0).isEmpty()).as("no ring is created").isTrue();
    }

    /// A3 (CTO), WAL path: the epoch history is read BEFORE the page. A materialization that recovers the log and extends its
    /// history after the page began (here: between the WAL scan and the tier read of the sealed prefix) must not relabel the
    /// page: the answer carries the history the page was vouched by when the read started.
    @Test
    void catchupRead_whoseWalHistoryChangesAfterThePageBegan_carriesTheHistoryReadBeforeIt() {
        onTierRead.set(this::extendTheWalHistory);

        var answer = catchupRead(0L, PAGE);

        assertThat(answer.success()).as("answer %s", answer.errorMessage()).isTrue();
        assertThat(onTierRead.get()).as("the tier was read, and the hook fired").isNull();
        assertThat(answer.history()).extracting(ProvenanceEntry::rank).contains(E1).doesNotContain(E2);
    }

    /// A3 (CTO), ring path: the same for a partition that materialized (the node restarted with a free slot), whose history
    /// is its open log's.
    @Test
    void catchupRead_ofAMaterializedPartition_whoseHistoryChangesAfterThePageBegan_carriesTheHistoryReadBeforeIt() {
        manager.close();
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica("held", 0, PEER);
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         EvictionListener.NOOP,
                                         ReplicationManager.replicationManager(SELF, registry),
                                         Option.some(walDir),
                                         segmentIndex::lastSealedOffset);
        manager.placementRoleSupplier((stream, _) -> roles.getOrDefault(stream, Role.NONE));
        manager.onStreamConfigPut(configPut(heldConfig()));
        manager.materializePartition("held", 0).onFailure(cause -> fail(cause.message()));
        handler = StreamForwardHandler.streamForwardHandler(SELF,
                                                            manager,
                                                            AcceptingStreamForwardTransport.accepting((_, message) -> record(message)),
                                                            64L * 1024 * 1024,
                                                            StreamReadForwardMetrics.NOOP,
                                                            Option.none(),
                                                            Option.some(tieredStreamReader(segmentIndex, hookedTier(storage))));
        onTierRead.set(this::extendTheMaterializedHistory);

        var answer = catchupRead(0L, PAGE);

        assertThat(answer.success()).as("answer %s", answer.errorMessage()).isTrue();
        assertThat(onTierRead.get()).as("the tier was read, and the hook fired").isNull();
        assertThat(manager.epochHistory("held", 0).unwrap()).as("the log's history did change").extracting(ProvenanceEntry::rank).contains(E2);
        assertThat(answer.history()).extracting(ProvenanceEntry::rank).contains(E1).doesNotContain(E2);
    }

    /// What a new owner epoch starting past the head does to the open log's history: its own history, plus the new epoch.
    private void extendTheMaterializedHistory() {
        var claimed = Stream.concat(manager.epochHistory("held", 0).unwrap().stream(),
                                    Stream.of(ProvenanceEntry.provenanceEntry(E2, EVENTS)))
                            .toList();

        manager.installProvenance("held", 0, EVENTS, EVENTS, claimed).onFailure(cause -> fail(cause.message()));
    }

    private void extendTheWalHistory() {
        var wal = AppendLog.open(walFile()).unwrap();

        wal.recordEpochStart(ProvenanceEntry.provenanceEntry(E2, 20).key().unwrap(), 20, ProvenanceEntry.ORDER)
           .onFailure(cause -> fail(cause.message()));
        wal.close();
    }

    /// The storage the tier reads through, which runs [#onTierRead] once, at the first call.
    private StorageInstance hookedTier(StorageInstance real) {
        InvocationHandler hook = (_, method, args) -> invokeAfterHook(real, method, args);

        return (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                        new Class<?>[]{StorageInstance.class},
                                                        hook);
    }

    private Object invokeAfterHook(StorageInstance real, Method method, Object[] args) throws Throwable {
        Option.option(onTierRead.getAndSet(null)).onPresent(Runnable::run);

        try {
            return method.invoke(real, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void catchupRead_ofAPartitionNotHeldHere_isAnsweredUnavailable_neverAnEmptySuccess() {
        var answer = catchupRead("elsewhere", 0L, PAGE);

        assertThat(answer.success()).isFalse();
        assertThat(answer.events()).isEmpty();
    }

    private ReadForwardResponse catchupRead(long from, int max) {
        return catchupRead("held", from, max);
    }

    private ReadForwardResponse catchupRead(String stream, long from, int max) {
        handler.onReadForward(readForward(PEER, "corr", stream, 0, from, max, false, true));

        return answers.getLast();
    }

    private void record(Object message) {
        if (message instanceof ReadForwardResponse response) {
            answers.add(response);
        }
    }

    private void recordEpochStart() {
        var wal = AppendLog.open(walFile()).unwrap();

        wal.recordEpochStart(ProvenanceEntry.provenanceEntry(E1, 0).key().unwrap(), 0, ProvenanceEntry.ORDER)
           .onFailure(cause -> fail(cause.message()));
        wal.close();
    }

    private Path walFile() {
        try (Stream<Path> files = Files.walk(walDir)) {
            var wals = files.filter(file -> file.getFileName().toString().endsWith(".wal"))
                            .filter(file -> file.toString().contains("held"))
                            .toList();

            assertThat(wals).as("one WAL of held").hasSize(1);

            return wals.getFirst();
        } catch (Exception e) {
            return fail(e);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }

        assertThat(condition.getAsBoolean()).as("condition within %d ms", AWAIT_MS).isTrue();
    }

    private static byte[] payload(int i) {
        var payload = new byte[PAYLOAD_BYTES];

        Arrays.fill(payload, (byte) (i * 31 + 7));
        payload[0] = (byte) i;

        return payload;
    }

    private static StreamConfig heldConfig() {
        return StreamConfig.streamConfig("held",
                                         1,
                                         RetentionPolicy.retentionPolicy(RING, 2L * RING * PAYLOAD_BYTES, 600_000L),
                                         "earliest");
    }

    private static StreamConfig config(String name, int partitions) {
        return StreamConfig.streamConfig(name,
                                         partitions,
                                         RetentionPolicy.retentionPolicy(100, 64 * 1024L, 3_600_000L),
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         1,
                                         1,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }
}
