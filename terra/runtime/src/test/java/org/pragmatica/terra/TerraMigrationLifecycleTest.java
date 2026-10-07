// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.db.migration.MigrationScript;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.utils.Causes.cause;

class TerraMigrationLifecycleTest {
    private static final ConfigurationProvider CONFIG = ConfigurationProvider.builder().build();
    private static final Map<String, List<MigrationScript>> SCRIPTS = Map.of("database", List.of(MigrationScript.migrationScript("V001__one.sql", "SELECT 1", 1)));
    private static final ResourceProviderFacade RESOURCES = new ResourceProviderFacade() {
        public <T> Promise<T> provide(Class<T> type, String section) { return cause("unexpected resource").promise(); }
        public <T> Promise<T> provide(Class<T> type, String section, org.pragmatica.aether.slice.ProvisioningContext context) { return provide(type, section); }
        public Promise<Unit> releaseAll(String scope) { return Promise.unitPromise(); }
    };

    @Test void start_migrationPending_waitsBeforeConstructingSlices() {
        var attempts = new AtomicInteger();
        var factory = factory(attempts);
        var pending = Promise.<List<TerraMigrations.Report>>promise();
        var startup = TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory), _ -> CONFIG, RESOURCES, () -> pending);
        assertThat(attempts).hasValue(0);
        pending.succeed(List.of());
        var app = startup.await(timeSpan(5).seconds()).unwrap();
        assertThat(attempts).hasValue(1);
        app.close().await(timeSpan(5).seconds()).unwrap();
    }

    @Test void start_failedMigration_preventsConstruction() {
        var attempts = new AtomicInteger();
        var factory = factory(attempts);
        var result = TerraApplication.start(new TerraBlueprint(List.of(factory.artifact())), List.of(factory), _ -> CONFIG, RESOURCES,
            () -> cause("migration failed").promise()).await(timeSpan(5).seconds());
        assertThat(result.isFailure()).isTrue();
        assertThat(attempts).hasValue(0);
    }

    @Test void start_invalidGraph_neverStartsMigrations() {
        var attempts = new AtomicInteger();
        var migrations = new AtomicInteger();
        var result = TerraApplication.start(new TerraBlueprint(List.of("g:missing:1")), List.of(factory(attempts)), _ -> CONFIG, RESOURCES,
            () -> { migrations.incrementAndGet(); return Promise.success(List.of()); }).await(timeSpan(5).seconds());
        assertThat(result.isFailure()).isTrue();
        assertThat(migrations).hasValue(0);
    }

    @Test void apply_callerTimesOut_keepsQueueUntilActualWorkAndCleanupFinish() {
        var acquired = Promise.<SqlConnector>promise();
        var released = Promise.<Unit>promise();
        var releaseStarted = Promise.<Unit>promise();
        var secondAcquisitions = new AtomicInteger();
        DatasourceConnectionProvider firstProvider = new DatasourceConnectionProvider() {
            public Promise<SqlConnector> connector(String name) { return acquired; }
            public Promise<Unit> release(String name) { releaseStarted.succeed(Unit.unit()); return released; }
            public Promise<Unit> releaseAll() { return release("database"); }
        };
        DatasourceConnectionProvider secondProvider = new DatasourceConnectionProvider() {
            public Promise<SqlConnector> connector(String name) { secondAcquisitions.incrementAndGet(); return cause("second acquisition failed").promise(); }
            public Promise<Unit> release(String name) { return Promise.unitPromise(); }
            public Promise<Unit> releaseAll() { return Promise.unitPromise(); }
        };
        var first = TerraMigrations.terraMigrations("g:app", SCRIPTS, firstProvider).unwrap().apply();
        first.timeout(timeSpan(10).millis());
        assertThat(first.await(timeSpan(5).seconds()).isFailure()).isTrue();
        var second = TerraMigrations.terraMigrations("g:other", SCRIPTS, secondProvider).unwrap().apply();
        assertThat(secondAcquisitions).hasValue(0);
        acquired.fail(cause("SQL acquisition failed"));
        releaseStarted.await(timeSpan(5).seconds()).unwrap();
        assertThat(secondAcquisitions).hasValue(0);
        released.fail(cause("cleanup failed"));
        assertThat(second.await(timeSpan(5).seconds()).isFailure()).isTrue();
        assertThat(secondAcquisitions).hasValue(1);
    }

    @Test void fromDirectory_migrationsWithoutIdentity_refuses(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve("schema"));
        Files.writeString(directory.resolve("schema/V001__one.sql"), "SELECT 1");
        var result = TerraMigrations.fromDirectory(new TerraBlueprint(List.of("g:a:1")), directory, CONFIG).await(timeSpan(5).seconds());
        assertThat(result.<String>fold(c -> c.message(), _ -> "unexpected success")).contains("blueprint id");
    }

    private static TerraFactory<Object> factory(AtomicInteger attempts) {
        return new TerraFactory<>() {
            public String artifact() { return "g:test:1"; }
            public Class<Object> sliceType() { return Object.class; }
            public List<Class<?>> dependencies() { return List.of(); }
            public Promise<Object> create(TerraContext context) { attempts.incrementAndGet(); return Promise.success(new Object()); }
        };
    }
}
