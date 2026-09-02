// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #345 I4 — the driver that turns registered keyspaces into ticks.
///
/// The trap this suite exists to avoid is the one that has bitten this repo before: a feature wired
/// everywhere and inert in production. Every test below therefore asks the question the other way round —
/// **who never receives this signal?** — and asserts that an UNREGISTERED entity is untouched, that an
/// unregistered one stays untouched, and that a second registration does not double the rate. A suite that
/// only hand-fed the registered entity would pass against a driver that ignored its registry entirely.
class EntityTimerDriverTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITIONS = 2;
    private static final NodeId SELF = new NodeId("self-node");
    private static final Duration DELAY = Duration.ofSeconds(10);

    private RecordingSubstrate substrate;
    private EntityPartitionArc arc;

    @BeforeEach
    void setUp() {
        substrate = new RecordingSubstrate();
        arc = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITIONS);
    }

    @Test
    void tick_firesTheDueTimers_ofARegisteredKeyspace() {
        var driver = EntityTimerDriver.entityTimerDriver();
        var entity = scheduledEntity();

        driver.register(KEYSPACE, entity);
        driver.tick(farFuture());
        settle(entity);

        assertThat(read(entity)).isEqualTo(Option.some(42));
    }

    /// The inert-feature check. An entity the driver was never told about must be untouched — otherwise the
    /// registry is decoration and the tick is reaching entities by some other means.
    @Test
    void tick_leavesAnUnregisteredKeyspaceUntouched() {
        var entity = scheduledEntity();

        EntityTimerDriver.entityTimerDriver().tick(farFuture());
        settle(entity);

        assertThat(read(entity)).isEqualTo(Option.some(1));
        assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isZero();
    }

    /// Unregistering is what keeps the tick from reaching into an unloaded slice's classloader. If it did
    /// not actually detach, the tick would keep firing timers through an entity nothing else can reach.
    @Test
    void tick_leavesAnUnregisteredKeyspaceUntouched_afterUnregister() {
        var driver = EntityTimerDriver.entityTimerDriver();
        var entity = scheduledEntity();

        driver.register(KEYSPACE, entity);
        driver.unregister(KEYSPACE);
        driver.tick(farFuture());
        settle(entity);

        assertThat(read(entity)).isEqualTo(Option.some(1));
        assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isZero();
    }

    @Test
    void unregister_isANoOp_forAnUnknownKeyspace() {
        var driver = EntityTimerDriver.entityTimerDriver();

        driver.unregister("never-registered");

        var entity = scheduledEntity();

        driver.register(KEYSPACE, entity);
        driver.tick(farFuture());
        settle(entity);

        assertThat(read(entity)).isEqualTo(Option.some(42));
    }

    /// Idempotent registration. Registering twice and ticking once must fire ONCE — a doubled registration
    /// would mean two ticks racing the same due set on every interval, which the double-fire guard would
    /// absorb but which would still double the work forever.
    @Test
    void register_isIdempotent_soOneTickFiresOnce() {
        var driver = EntityTimerDriver.entityTimerDriver();
        var entity = scheduledEntity();

        driver.register(KEYSPACE, entity);
        driver.register(KEYSPACE, entity);
        driver.tick(farFuture());
        settle(entity);

        assertThat(read(entity)).isEqualTo(Option.some(42));
        assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isEqualTo(1);
    }

    /// The no-arg tick is the one a scheduler calls. It must read the clock itself rather than silently
    /// ticking at instant zero, which would make every timer permanently not-yet-due.
    @Test
    void tick_readsTheWallClock_whenGivenNoInstant() {
        var driver = EntityTimerDriver.entityTimerDriver();
        var entity = entityWithTimerDueImmediately();

        driver.register(KEYSPACE, entity);
        driver.tick();
        settle(entity);

        assertThat(read(entity)).isEqualTo(Option.some(42));
    }

    // --- fixtures ---

    private static long farFuture() {
        return System.currentTimeMillis() + DELAY.toMillis() + 1_000;
    }

    private PartitionFencedDurableEntity<String, Integer, IntOp> scheduledEntity() {
        return entityWithTimer(DELAY);
    }

    private PartitionFencedDurableEntity<String, Integer, IntOp> entityWithTimerDueImmediately() {
        return entityWithTimer(Duration.ZERO);
    }

    private PartitionFencedDurableEntity<String, Integer, IntOp> entityWithTimer(Duration delay) {
        var entity = entity();

        entity.create("k1", 1).await().onFailure(EntityTimerDriverTest::failCause);
        entity.scheduleTimer("k1", delay, new IntOp.Add(41)).await().onFailure(EntityTimerDriverTest::failCause);

        return entity;
    }

    /// Flush the key's serialization tail: the tick submits onto it and returns, so a read on the same key
    /// queues behind whatever it submitted and awaiting that read means the fire has finished.
    private static void settle(DurableEntity<String, Integer, IntOp> entity) {
        entity.get("k1").await().onFailure(EntityTimerDriverTest::failCause);
    }

    private static Option<Integer> read(DurableEntity<String, Integer, IntOp> entity) {
        return entity.get("k1").await().fold(cause -> fail(cause.message()), value -> value);
    }

    @SuppressWarnings("unchecked")
    private PartitionFencedDurableEntity<String, Integer, IntOp> entity() {
        return (PartitionFencedDurableEntity<String, Integer, IntOp>)
                   PartitionFencedDurableEntity.<String, Integer, IntOp> partitionFencedDurableEntity(KEYSPACE,
                                                                                                      substrate,
                                                                                                      arc,
                                                                                                      new IntSerializer(),
                                                                                                      new IntDeserializer(),
                                                                                                      SELF,
                                                                                                      (_, _) -> Option.some(new CommittedOwner(SELF, Epoch.ZERO)),
                                                                                                      Option.none(),
                                                                                                      Option.some((_, _) -> Promise.success(Unit.unit())));
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }

    private static final class RecordingSubstrate implements EntityLogSubstrate {
        private final Map<Integer, List<byte[]>> log = new ConcurrentHashMap<>();

        long opsOf(EntityLogRecord.Op op) {
            return log.values()
                      .stream()
                      .flatMap(List::stream)
                      .map(raw -> EntityLogRecord.decode(raw).fold(cause -> fail(cause.message()), record -> record))
                      .filter(record -> record.op() == op)
                      .count();
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            var records = log.computeIfAbsent(partition, _ -> new ArrayList<>());

            records.add(record);

            return Promise.success((long) records.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            var records = log.getOrDefault(partition, List.of());
            var start = (int) fromOffset;

            return Promise.success(start < 0 || start >= records.size()
                                   ? List.of()
                                   : List.copyOf(records.subList(start, Math.min(records.size(), start + maxRecords))));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).size() - 1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).isEmpty() ? -1L : 0L;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return true;
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(Option.none());
        }
    }

    private static final class IntSerializer implements Serializer {
        @Override
        public byte[] encode(Object value) {
            return switch (value) {
                case IntOp.Add add -> ("Add:" + add.delta()).getBytes(StandardCharsets.UTF_8);
                case null, default -> String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            };
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class IntDeserializer implements Deserializer {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            var text = new String(bytes, StandardCharsets.UTF_8);

            return text.startsWith("Add:")
                   ? (T) new IntOp.Add(Integer.parseInt(text.substring(4)))
                   : (T) Integer.valueOf(text);
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
