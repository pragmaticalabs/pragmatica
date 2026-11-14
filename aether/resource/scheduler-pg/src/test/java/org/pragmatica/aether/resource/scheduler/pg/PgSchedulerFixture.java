// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.scheduler.pg;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.async.PgSqlConnectorFactory;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.testing.DockerRequirement;

import org.testcontainers.containers.PostgreSQLContainer;

import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// One live PostgreSQL 16 shared by the scheduler tests, plus the small helpers they all use: separate connectors (one
/// per simulated instance), configs in milliseconds, deadline polling, and a gate a handler can hang on.
final class PgSchedulerFixture {
    private static PostgreSQLContainer<?> postgres;
    private static final List<PgSqlConnector> connectors = new ArrayList<>();

    private PgSchedulerFixture() {}

    static void startPostgres() {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL scheduler tests");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        PgScheduler.installSchema(newConnector()).await().unwrap();
    }

    static void stopPostgres() {
        connectors.forEach(c -> c.stop()
                                 .await());
        connectors.clear();
        if (postgres != null) {
            postgres.stop();
        }
    }

    /// A fresh connector, i.e. its own sessions: one simulated scheduler instance.
    static PgSqlConnector newConnector() {
        var config = databaseConnectorConfigBuilder().withName("scheduler-" + connectors.size())
                                                   .withAsyncUrl("postgresql://" + postgres.getHost()
                                                                + ":" + postgres.getMappedPort(5432)
                                                                + "/" + postgres.getDatabaseName())
                                                   .withUsername(postgres.getUsername())
                                                   .withPassword(postgres.getPassword())
                                                   .build()
                                                   .unwrap();
        var connector = new PgSqlConnectorFactory().provision(config).await().unwrap();

        connectors.add(connector);

        return connector;
    }

    static SchedulerConfig config(String instance, long pollMs, long leaseMs, long retryMs, long graceMs, int batch) {
        return SchedulerConfig.schedulerConfig(instance,
                                               timeSpan(pollMs).millis(),
                                               timeSpan(leaseMs).millis(),
                                               timeSpan(retryMs).millis(),
                                               timeSpan(graceMs).millis(),
                                               batch)
                              .unwrap();
    }

    static Promise<Unit> done() {
        return Promise.success(Unit.unit());
    }

    /// Completes after `ms` on the shared scheduler: a handler that is "running" for a while.
    static Promise<Unit> after(long ms) {
        Promise<Unit> p = Promise.promise();

        SharedScheduler.schedule(() -> p.resolve(Result.success(Unit.unit())),
                                 timeSpan(ms).millis());

        return p;
    }

    static void open(Promise<Unit> gate) {
        gate.resolve(Result.success(Unit.unit()));
    }

    /// Spins to a deadline: the condition must become true, or the test fails naming what never happened.
    static void awaitTrue(String what, long deadlineMs, BooleanSupplier condition) {
        var end = System.nanoTime() + deadlineMs * 1_000_000L;

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail("Timed out after " + deadlineMs + " ms waiting for: " + what);
            }

            Thread.onSpinWait();
            sleep(2);
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
