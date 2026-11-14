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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testing.DockerRequirement;

import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Shared instrument of the live-PostgreSQL store tests: one PostgreSQL 16 container per JVM (reaped by Testcontainers),
/// unique entity names and keys per test so no test reads another test's rows, and the contention helpers.
/// Self-skips (assumption) when no Docker daemon is reachable.
final class PgFixture {
    static final long DEADLINE_MILLIS = 60_000;
    static final Mutator<Long> INCREMENT = state -> state + 1;
    static final StateCodec<Long> LONG_CODEC = new LongCodec();
    private static final AtomicInteger KEYS = new AtomicInteger();
    private static final AtomicInteger ENTITIES = new AtomicInteger();
    private static SqlConnector connector;

    private PgFixture() {}

    static synchronized SqlConnector connector() throws IOException {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL store tests");
        if (connector == null) {
            connector = provision();
        }
        return connector;
    }

    private static SqlConnector provision() throws IOException {
        var postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        var config = databaseConnectorConfigBuilder().withName("entity-pg")
                                                     .withAsyncUrl("postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName())
                                                     .withUsername(postgres.getUsername())
                                                     .withPassword(postgres.getPassword())
                                                     .build()
                                                     .unwrap();
        var created = new AsyncSqlConnectorFactory().provision(config).await().unwrap();
        created.update(schemaDdl()).await().unwrap();
        return created;
    }

    static String freshKey() {
        return "k" + KEYS.incrementAndGet();
    }

    static String freshEntity() {
        return "e" + ENTITIES.incrementAndGet();
    }

    static List<Long> oneTo(int n) {
        var all = new ArrayList<Long>();
        for (long i = 1; i <= n; i++) {
            all.add(i);
        }
        return all;
    }

    private static String schemaDdl() throws IOException {
        try (var in = PgFixture.class.getResourceAsStream("/schema/V1__create_durable_entity_state.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static <T> Cause failureOf(Result<T> result) {
        return result.fold(cause -> cause,
                           value -> {
                               throw new AssertionError("expected a failure but got " + value);
                           });
    }

    /// `contenders` threads spin on a volatile gate and start their action together. Spin waits carry a deadline, and a
    /// worker records its failure instead of dying, so a broken mechanism fails the assertion instead of hanging the build.
    static <T> List<Result<T>> race(int contenders, Supplier<Promise<T>> action) {
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
            return state < 0
                   ? Causes.cause("negative state " + state).result()
                   : Result.success(ByteBuffer.allocate(Long.BYTES).putLong(state).array());
        }

        @Override
        public Result<Long> decode(byte[] bytes) {
            return bytes.length == Long.BYTES
                   ? Result.success(ByteBuffer.wrap(bytes).getLong())
                   : Causes.cause("corrupt state: " + bytes.length + " bytes").result();
        }
    }
}
