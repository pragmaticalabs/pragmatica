// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.db.migration.MigrationError;
import org.pragmatica.db.migration.MigrationScript;
import org.pragmatica.db.migration.ParsedMigration;
import org.pragmatica.db.migration.SchemaMigrations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Startup migrations for an exploded blueprint: schema/*.sql or schema/<datasource>/*.sql.
/// The process-wide queue serializes aliases too. It is released only after SQL and connector
/// cleanup settle; there is no timeout-driven retry.
///
/// Across processes (#2787), the database migration fence of the shared runner (FDB-2a, #2591) excludes: one instance
/// runs a datasource's migration while every other instance meets the fence held ([MigrationError.MigrationFencedInDatabase])
/// and waits for it under the A6 ruling, then re-runs and finds the history already applied.
public interface TerraMigrations {
    /// Zero means no statement bound on the pinned migration session (FDB-2a, #2591): Terra had no bound before the fence.
    /// On PostgreSQL it is set explicitly (`statement_timeout = 0`, `lock_timeout = 0`), overriding role defaults.
    TimeSpan STATEMENT_TIMEOUT = timeSpan(0).nanos();
    /// The A6 ruling's retry backoff, the same values as Aether's schema orchestrator (`SchemaOrchestratorService`):
    /// base, cap, exponent clamp, and the consecutive-Held count from which each further Held is logged as a warning.
    long HELD_BACKOFF_BASE_MS = 5000;
    long HELD_BACKOFF_CAP_MS = 45_000;
    int HELD_BACKOFF_MAX_EXPONENT = 3;
    int HELD_WARNING_THRESHOLD = 3;
    Promise<List<Report>> apply();

    /// The wait before re-running after the `held`th consecutive fenced-in-the-database outcome: 5 s x 3^held, the
    /// exponent clamped first, never above the 45 s cap (15 s, then 45 s).
    static TimeSpan heldBackoff(int held) {
        return timeSpan(Math.min(HELD_BACKOFF_BASE_MS * (long) Math.pow(3, Math.min(held, HELD_BACKOFF_MAX_EXPONENT)),
                                 HELD_BACKOFF_CAP_MS)).millis();
    }

    record Report(String datasource, SchemaMigrations.SchemaResult result) {}

    static TerraMigrations noMigrations() {
        return () -> Promise.success(List.of());
    }

    static Promise<TerraMigrations> fromDirectory(TerraBlueprint blueprint,
                                                  Path directory,
                                                  ConfigurationProvider config) {
        return Promise.lift(Causes::fromThrowable,
                            () -> readScripts(directory.resolve("schema")))
                      .flatMap(scripts -> assemble(blueprint, scripts, config).async());
    }

    private static Result<TerraMigrations> assemble(TerraBlueprint blueprint,
                                                    Map<String, List<MigrationScript>> scripts,
                                                    ConfigurationProvider config) {
        if (scripts.isEmpty()) {
            return Result.success(noMigrations());
        }

        var service = ProviderBasedConfigService.providerBasedConfigService(config);
        var provider = DatasourceConnectionProvider.datasourceConnectionProvider((section, type) -> service.config(section,
                                                                                                                   type));

        return blueprint.id()
                        .toResult(new TerraError.InvalidBlueprint("Schema migrations require a versioned blueprint id"))
                        .flatMap(id -> terraMigrations(id.substring(0,
                                                                    id.lastIndexOf(':')),
                                                       scripts,
                                                       provider));
    }

    static Result<TerraMigrations> terraMigrations(String ownerBase,
                                                   Map<String, List<MigrationScript>> scripts,
                                                   DatasourceConnectionProvider provider) {
        if (ownerBase.split(":", -1).length != 2 || java.util.Arrays.stream(ownerBase.split(":", -1))
                                                                    .anyMatch(String::isBlank)) {
            return new TerraError.InvalidBlueprint("Migration owner must be the versionless blueprint coordinate").result();
        }

        var tasks = scripts.entrySet()
                           .stream()
                           .sorted(Map.Entry.comparingByKey())
                           .map(entry -> MigrationTask.migrationTask(entry.getKey(),
                                                                     List.copyOf(entry.getValue())))
                           .toList();

        if (tasks.stream()
                 .anyMatch(task -> task.datasource()
                                       .isBlank() || task.scripts()
                                                         .isEmpty() || task.scripts()
                                                                           .stream()
                                                                           .map(MigrationScript::filename)
                                                                           .distinct()
                                                                           .count() != task.scripts()
                                                                                           .size())) {
            return new TerraError.InvalidBlueprint("Migration sets require a datasource and unique script filenames").result();
        }

        return Result.allOf(tasks.stream()
                                 .flatMap(task -> task.scripts()
                                                      .stream())
                                 .map(ParsedMigration::parsedMigration)
                                 .toList()).map(_ -> MigrationRun.migrationRun(ownerBase,
                                                                               tasks,
                                                                               provider,
                                                                               SchemaMigrations.schemaMigrations(STATEMENT_TIMEOUT),
                                                                               TerraMigrations::heldBackoff));
    }

    // Filesystem API boundary; callers lift all failures before provisioning any database.
    @Contract
    private static Map<String, List<MigrationScript>> readScripts(Path schema) throws java.io.IOException {
        var scripts = new LinkedHashMap<String, List<MigrationScript>>();

        if (Files.notExists(schema)) {
            return scripts;
        }

        try (var paths = Files.walk(schema)) {
            for (var path : paths.filter(Files::isRegularFile)
                                 .filter(path -> path.toString()
                                                     .endsWith(".sql"))
                                 .sorted()
                                 .toList()) {
                var relative = schema.relativize(path);
                var datasource = relative.getNameCount() == 1
                                 ? "database"
                                 : "database." + relative.getName(0);
                var sql = Files.readString(path, StandardCharsets.UTF_8);
                var crc = new CRC32();

                crc.update(sql.getBytes(StandardCharsets.UTF_8));
                scripts.computeIfAbsent(datasource,
                                        _ -> new ArrayList<>())
                       .add(MigrationScript.migrationScript(path.getFileName().toString(),
                                                            sql,
                                                            crc.getValue()));
            }
        }

        return scripts;
    }
}

record MigrationTask(String datasource, List<MigrationScript> scripts) {
    static MigrationTask migrationTask(String datasource, List<MigrationScript> scripts) {
        return new MigrationTask(datasource, scripts);
    }
}

/// `heldBackoff` maps the consecutive fenced-in-the-database count to the wait before the next run
/// ([TerraMigrations#heldBackoff] in production).
record MigrationRun(String owner,
                    List<MigrationTask> tasks,
                    DatasourceConnectionProvider provider,
                    SchemaMigrations engine,
                    Fn1<TimeSpan, Integer> heldBackoff) implements TerraMigrations {
    private static final Logger log = LoggerFactory.getLogger(TerraMigrations.class);
    private static final String NODE = "terra";

    static MigrationRun migrationRun(String owner,
                                     List<MigrationTask> tasks,
                                     DatasourceConnectionProvider provider,
                                     SchemaMigrations engine,
                                     Fn1<TimeSpan, Integer> heldBackoff) {
        return new MigrationRun(owner, tasks, provider, engine, heldBackoff);
    }

    public Promise<List<Report>> apply() {
        return MigrationQueue.submit(this::migrateAll);
    }

    /// The run outside this JVM's queue: what [#apply] queues. Package-private so a test can stand for a second process,
    /// which has a queue of its own.
    Promise<List<Report>> migrateAll() {
        var chain = Promise.success(List.<Report> of());

        for (var task : tasks) {
            chain = chain.flatMap(reports -> migrateOne(task).map(report -> append(reports, report)));
        }

        return chain;
    }

    private static List<Report> append(List<Report> reports, Report report) {
        var result = new ArrayList<>(reports);

        result.add(report);

        return List.copyOf(result);
    }

    private Promise<Report> migrateOne(MigrationTask task) {
        Promise<SqlConnector> acquisition = Result.lift(Causes::fromThrowable,
                                                        () -> provider.connector(task.datasource()))
                                                  .fold(Promise::failure, promise -> promise);

        return acquisition.flatMap(connector -> migrateWhileHeld(task, connector, 0))
                          .map(result -> new Report(task.datasource(),
                                                    result))
                          .fold(result -> release(task.datasource()).fold(cleanup -> complete(result, cleanup)));
    }

    /// A6 ruling (FDB-2a, #2591, know 8fc2af106e), reused for Terra (#2787): another database session holds the fence,
    /// so this run ran and read nothing. That is no failure of this instance: it waits [#heldBackoff] for the
    /// consecutive-Held count and re-runs on the same connector (each run opens a fresh pinned session), for as long as
    /// the holder lives; the holder's own run then shows in the history this run reads. From the
    /// [TerraMigrations#HELD_WARNING_THRESHOLD]th consecutive Held on, each one is logged as a warning naming the holder.
    /// Every other cause ends the run as before. Recovery: FER (degrade forward: wait, then run again), and it earns only
    /// "exactly one instance runs each script, and a waiting instance starts once the holder is gone", because the fence
    /// ends with the holder's session; there is no timeout, so a holder that never ends keeps this startup waiting.
    private Promise<SchemaMigrations.SchemaResult> migrateWhileHeld(MigrationTask task,
                                                                    SqlConnector connector,
                                                                    int held) {
        return engine.migrate(task.datasource(),
                              task.scripts(),
                              connector,
                              NODE,
                              owner)
                     .fold(outcome -> afterRun(task, connector, held, outcome));
    }

    private Promise<SchemaMigrations.SchemaResult> afterRun(MigrationTask task,
                                                            SqlConnector connector,
                                                            int held,
                                                            Result<SchemaMigrations.SchemaResult> outcome) {
        return outcome.fold(cause -> retryIfHeld(task, connector, held, cause), Promise::success);
    }

    private Promise<SchemaMigrations.SchemaResult> retryIfHeld(MigrationTask task,
                                                               SqlConnector connector,
                                                               int held,
                                                               Cause cause) {
        return cause instanceof MigrationError.MigrationFencedInDatabase
               ? waitThenRetry(task, connector, held + 1, cause)
               : cause.promise();
    }

    private Promise<SchemaMigrations.SchemaResult> waitThenRetry(MigrationTask task,
                                                                 SqlConnector connector,
                                                                 int held,
                                                                 Cause cause) {
        var delay = heldBackoff.apply(held);

        logHeld(task.datasource(), held, delay, cause);

        return Promise.promise(delay, Result::unitResult).flatMap(_ -> migrateWhileHeld(task, connector, held));
    }

    @Contract
    private static void logHeld(String datasource, int held, TimeSpan delay, Cause cause) {
        if (held >= HELD_WARNING_THRESHOLD) {
            log.warn("Schema migration for '{}' has been fenced in the database {} consecutive times; it waits and retries in {} ms: {}",
                     datasource,
                     held,
                     delay.millis(),
                     cause.message());
        } else {
            log.info("Schema migration for '{}' is fenced in the database ({}), retrying in {} ms (consecutive {})",
                     datasource,
                     cause.message(),
                     delay.millis(),
                     held);
        }
    }

    private Promise<Unit> release(String datasource) {
        return Result.lift(Causes::fromThrowable,
                           () -> provider.release(datasource))
                     .fold(Promise::failure, promise -> promise);
    }

    private static Promise<Report> complete(Result<Report> result, Result<Unit> cleanup) {
        return cleanup.fold(cleanupCause -> result.fold(cause -> Causes.composite(cause.result(),
                                                                                  cleanupCause.result())
                                                                       .promise(),
                                                        _ -> cleanupCause.promise()),
                            _ -> result.async());
    }
}

final class MigrationQueue {
    private static Promise<Unit> tail = Promise.unitPromise();

    private MigrationQueue() {}

    static synchronized Promise<List<TerraMigrations.Report>> submit(Fn0<Promise<List<TerraMigrations.Report>>> operation) {
        var previous = tail;
        var fence = Promise.<Unit> promise();
        var exposed = Promise.<List<TerraMigrations.Report>> promise();

        tail = fence;
        previous.flatMap(_ -> Result.lift(Causes::fromThrowable, operation::apply).fold(Promise::failure,
                                                                                        promise -> promise))
                .withResult(result -> {
                    fence.succeed(Unit.unit());
                    exposed.resolve(result);
                });

        return exposed;
    }
}
