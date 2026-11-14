// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.pragmatica.aether.resource.Mutator;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.db.async.AsyncSqlConnectorFactory;
import org.pragmatica.aether.resource.entity.EntityError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testing.DockerRequirement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2100 E-B against a LIVE PostgreSQL 16: the store's per-operation guarantees, each pinned by a test that goes red
/// when the mechanism behind it is removed (drop `FOR UPDATE`, drop the version compare in the CAS, turn the create
/// `DO NOTHING` into an upsert). Self-skips when no Docker daemon is reachable.
class PgEntityStoreTest {
    private static final int CONTENDERS = 16;
    private static final long DEADLINE_MILLIS = 60_000;
    private static final Mutator<Long> INCREMENT = state -> state + 1;
    private static final StateCodec<Long> LONG_CODEC = new LongCodec();
    private static final AtomicInteger KEYS = new AtomicInteger();
    private static PostgreSQLContainer<?> postgres;
    private static SqlConnector connector;

    @BeforeAll
    static void startPostgres() throws IOException {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL store tests");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        var config = databaseConnectorConfigBuilder().withName("entity-pg")
                                                     .withAsyncUrl("postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName())
                                                     .withUsername(postgres.getUsername())
                                                     .withPassword(postgres.getPassword())
                                                     .build()
                                                     .unwrap();
        connector = new AsyncSqlConnectorFactory().provision(config).await().unwrap();
        connector.update(schemaDdl()).await().unwrap();
    }

    @AfterAll
    static void stopPostgres() {
        if (connector != null) {
            connector.stop().await();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void create_thenGet_returnsInitialAtVersionOne() {
        var store = store(connector);
        var key = freshKey();

        assertThat(store.create(key, 5L).await().unwrap()).isEqualTo(5L);
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.some(5L));
        assertThat(store.getVersioned(key).await().unwrap().map(Versioned::version)).isEqualTo(Option.some(1L));
    }

    @Test
    void get_absentKey_isNone() {
        assertThat(store(connector).get(freshKey()).await().unwrap()).isEqualTo(Option.none());
    }

    @Test
    void create_existingKey_isRefusedAndKeepsTheOriginalState() {
        var store = store(connector);
        var key = freshKey();
        store.create(key, 1L).await().unwrap();

        var cause = failureOf(store.create(key, 99L).await());

        assertThat(cause).isInstanceOf(EntityError.EntityAlreadyExists.class);
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.some(1L));
    }

    @Test
    void create_concurrentRace_exactlyOneSucceeds() {
        var store = store(connector);
        var key = freshKey();
        var counter = new AtomicInteger();

        var results = race(8, () -> store.create(key, counter.incrementAndGet() * 1000L));

        var winners = results.stream().filter(Result::isSuccess).count();
        assertThat(winners).isEqualTo(1);
        results.stream()
               .filter(Result::isFailure)
               .forEach(result -> assertThat(failureOf(result)).isInstanceOf(EntityError.EntityAlreadyExists.class));
        var winningState = results.stream().filter(Result::isSuccess).findFirst().orElseThrow().unwrap();
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.some(winningState));
    }

    @Test
    void update_concurrentIncrements_areTotallyOrderedAndNoneIsLost() {
        var store = store(connector);
        var key = freshKey();
        store.create(key, 0L).await().unwrap();

        var results = race(CONTENDERS, () -> store.update(key, INCREMENT));

        assertThat(results).allMatch(Result::isSuccess);
        var returned = results.stream().map(result -> result.unwrap()).sorted().toList();
        assertThat(returned).as("each update saw a distinct predecessor: a total order 1..N").isEqualTo(oneTo(CONTENDERS));
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.some((long) CONTENDERS));
        assertThat(store.getVersioned(key).await().unwrap().map(Versioned::version)).isEqualTo(Option.some(CONTENDERS + 1L));
    }

    @Test
    void update_absentKey_isEntityNotFound() {
        assertThat(failureOf(store(connector).update(freshKey(), INCREMENT).await())).isInstanceOf(EntityError.EntityNotFound.class);
    }

    @Test
    void update_mutatorThrows_rollsBackAndLeavesTheRowUnchanged() {
        var store = store(connector);
        var key = freshKey();
        store.create(key, 7L).await().unwrap();

        var cause = failureOf(store.update(key, state -> {
            throw new IllegalStateException("boom");
        }).await());

        assertThat(cause).isInstanceOf(EntityError.StorageFailed.class);
        assertThat(((EntityError.StorageFailed) cause).cause()).isInstanceOf(PgEntityError.MutatorFailed.class);
        assertThat(store.getVersioned(key).await().unwrap()).isEqualTo(Option.some(Versioned.versioned(1L, 7L)));
    }

    @Test
    void update_rowLockMissing_theVersionCasStillLetsExactlyOneWrite() {
        var parties = 4;
        var store = store(GatedConnector.unlocked(connector, parties));
        var key = freshKey();
        store.create(key, 0L).await().unwrap();

        var results = race(parties, () -> store.update(key, INCREMENT));

        assertThat(results.stream().filter(Result::isSuccess).count()).as("every contender read version 1; one write may win").isEqualTo(1);
        results.stream()
               .filter(Result::isFailure)
               .forEach(result -> assertThat(((EntityError.StorageUnavailable) failureOf(result)).cause()).isInstanceOf(PgEntityError.VersionConflict.class));
        assertThat(store.getVersioned(key).await().unwrap()).isEqualTo(Option.some(Versioned.versioned(2L, 1L)));
    }

    @Test
    void delete_isRepeatable_firstDeletedThenAbsent() {
        var store = store(connector);
        var key = freshKey();
        store.create(key, 1L).await().unwrap();

        assertThat(store.delete(key).await().unwrap()).as("first delete").isEqualTo(DeleteOutcome.DELETED);
        assertThat(store.get(key).await().unwrap()).isEqualTo(Option.none());
        assertThat(store.delete(key).await().unwrap()).as("second delete").isEqualTo(DeleteOutcome.ABSENT);
    }

    @Test
    void delete_neverCreatedKey_isAbsent() {
        assertThat(store(connector).delete(freshKey()).await().unwrap()).isEqualTo(DeleteOutcome.ABSENT);
    }

    @Test
    void delete_hookFailure_rollsBackTheRowRemoval() {
        var hookFailure = Causes.cause("timer layer refused");
        var store = PgEntityStore.<String, Long, Mutator<Long>>pgEntityStore(connector,
                                                                             "counter",
                                                                             key -> key,
                                                                             LONG_CODEC,
                                                                             (_, _, _) -> hookFailure.<Unit>promise())
                                 .unwrap();
        var key = freshKey();
        store.create(key, 3L).await().unwrap();

        assertThat(store.delete(key).await().isFailure()).isTrue();
        assertThat(store.get(key).await().unwrap()).as("same transaction: the removal rolled back").isEqualTo(Option.some(3L));
    }

    @Test
    void entityNames_areSeparateNamespaces() {
        var counters = store(connector);
        var other = PgEntityStore.<String, Long, Mutator<Long>>pgEntityStore(connector, "other", key -> key, LONG_CODEC).unwrap();
        var key = freshKey();
        counters.create(key, 1L).await().unwrap();

        assertThat(other.get(key).await().unwrap()).isEqualTo(Option.none());
        assertThat(other.create(key, 2L).await().unwrap()).isEqualTo(2L);
    }

    @Test
    void get_corruptStoredBytes_isStorageFailed() {
        var store = store(connector);
        var key = freshKey();
        connector.update("INSERT INTO durable_entity_state (entity, entity_key, version, state) VALUES ('counter', ?, 1, ?)",
                         key,
                         new byte[]{1, 2, 3}).await().unwrap();

        assertThat(failureOf(store.get(key).await())).isInstanceOf(EntityError.StorageFailed.class);
    }

    @Test
    void pgEntityStore_blankEntityName_isRejected() {
        assertThat(PgEntityStore.<String, Long, Mutator<Long>>pgEntityStore(connector, " ", key -> key, LONG_CODEC).isFailure()).isTrue();
    }

    private static PgEntityStore<String, Long, Mutator<Long>> store(SqlConnector via) {
        return PgEntityStore.<String, Long, Mutator<Long>>pgEntityStore(via, "counter", key -> key, LONG_CODEC).unwrap();
    }

    private static String freshKey() {
        return "k" + KEYS.incrementAndGet();
    }

    private static List<Long> oneTo(int n) {
        var all = new ArrayList<Long>();
        for (long i = 1; i <= n; i++) {
            all.add(i);
        }
        return all;
    }

    private static String schemaDdl() throws IOException {
        try (var in = PgEntityStoreTest.class.getResourceAsStream("/schema/V1__create_durable_entity_state.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static <T> Cause failureOf(Result<T> result) {
        return result.fold(cause -> cause,
                           value -> {
                               throw new AssertionError("expected a failure but got " + value);
                           });
    }

    /// `contenders` threads spin on a volatile gate and start their action together. Spin waits carry a deadline, and a
    /// worker records its failure instead of dying, so a broken mechanism fails the assertion instead of hanging the build.
    private static <T> List<Result<T>> race(int contenders, Supplier<Promise<T>> action) {
        var ready = new AtomicInteger();
        var gate = new Gate();
        var results = new ArrayList<Result<T>>();
        var threads = new ArrayList<Thread>();
        for (var i = 0; i < contenders; i++) {
            results.add(null);
            var slot = i;
            var thread = new Thread(() -> runContender(ready, gate, results, slot, action));
            threads.add(thread);
            thread.start();
        }
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (ready.get() < contenders && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        gate.open = true;
        threads.forEach(thread -> joinWithin(thread, deadline));
        assertThat(results).as("every contender finished inside the deadline").doesNotContainNull();
        return results;
    }

    private static <T> void runContender(AtomicInteger ready, Gate gate, List<Result<T>> results, int slot, Supplier<Promise<T>> action) {
        ready.incrementAndGet();
        var deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (!gate.open && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        results.set(slot, awaitRecording(action));
    }

    private static <T> Result<T> awaitRecording(Supplier<Promise<T>> action) {
        try {
            return action.get().await(timeSpan(30).seconds());
        } catch (Throwable thrown) {
            return Causes.fromThrowable(thrown).result();
        }
    }

    private static void joinWithin(Thread thread, long deadline) {
        try {
            thread.join(Math.max(1, deadline - System.currentTimeMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Gate {
        volatile boolean open;
    }

    private static final class LongCodec implements StateCodec<Long> {
        @Override
        public Result<byte[]> encode(Long state) {
            return Result.success(ByteBuffer.allocate(Long.BYTES).putLong(state).array());
        }

        @Override
        public Result<Long> decode(byte[] bytes) {
            return bytes.length == Long.BYTES
                   ? Result.success(ByteBuffer.wrap(bytes).getLong())
                   : Causes.cause("corrupt state: " + bytes.length + " bytes").result();
        }
    }
}
