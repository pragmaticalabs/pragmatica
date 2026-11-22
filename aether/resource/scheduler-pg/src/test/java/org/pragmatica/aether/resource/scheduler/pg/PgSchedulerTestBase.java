// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.resource.scheduler.pg.PgSchedulerFixture.*;


/// Shared plumbing of the scheduler tests: schedulers started per test and closed after it, gates that hung handlers
/// wait on, a second connector for inspecting the table, and the table cleared before each test.
abstract class PgSchedulerTestBase {
    static final byte[] PAYLOAD = {1, 2, 3};

    static final Duration DUE = Duration.ofSeconds(-1);
    static final long DEADLINE_MS = 30_000;

    final List<PgScheduler> started = new ArrayList<>();
    // Written by scheduler threads (handlers call hang() while firings are in flight) and read by the test thread's
    // teardown: every access goes through `gateLock`. Once `stopping` is set a gate is created already open, so a
    // handler that fires after the teardown's opening pass never waits on a gate nobody will open.
    private final Object gateLock = new Object();
    private final List<Promise<Unit>> gates = new ArrayList<>();
    private boolean stopping;
    PgSqlConnector admin;

    @BeforeAll
    static void startPostgres() {
        PgSchedulerFixture.startPostgres();
    }

    @BeforeEach
    void emptyTable() {
        admin = newConnector();
        admin.update("DELETE FROM scheduler_job").await().unwrap();
    }

    @AfterEach
    void stopSchedulers() {
        openGates().forEach(PgSchedulerFixture::open);
        started.forEach(s -> s.close()
                              .await());
    }

    PgScheduler start(PgScheduler scheduler) {
        started.add(scheduler);
        scheduler.start().await().unwrap();

        return scheduler;
    }

    PgScheduler instance(String id,
                         long pollMs,
                         long leaseMs,
                         long retryMs,
                         long graceMs,
                         int batch,
                         Clock clock,
                         JobHandler handler) {
        return PgScheduler.pgScheduler(newConnector(),
                                       config(id, pollMs, leaseMs, retryMs, graceMs, batch),
                                       handler,
                                       clock);
    }

    Promise<Unit> hang() {
        Promise<Unit> gate = Promise.promise();

        synchronized (gateLock) {
            if (stopping) {
                PgSchedulerFixture.open(gate);
            } else {
                gates.add(gate);
            }
        }

        return gate;
    }

    private List<Promise<Unit>> openGates() {
        synchronized (gateLock) {
            stopping = true;

            return List.copyOf(gates);
        }
    }

    long count() {
        return admin.queryOne("SELECT count(*)::bigint AS n FROM scheduler_job",
                              row -> row.getLong("n"))
                    .await()
                    .unwrap();
    }

    long tokenOf(JobId id) {
        return admin.queryOne("SELECT fence_token FROM scheduler_job WHERE id = ?",
                              row -> row.getLong("fence_token"),
                              id.value())
                    .await()
                    .unwrap();
    }

    String statusOf(JobId id) {
        return admin.queryOne("SELECT status FROM scheduler_job WHERE id = ?",
                              row -> row.getString("status"),
                              id.value())
                    .await()
                    .unwrap();
    }

    JobId schedule(PgScheduler scheduler, String name, Duration delay) {
        return scheduler.schedule(name, delay, PAYLOAD)
                        .await()
                        .unwrap();
    }

    static Clock skewed(Duration by) {
        return Clock.fixed(Instant.now().plus(by),
                           ZoneOffset.UTC);
    }
}
