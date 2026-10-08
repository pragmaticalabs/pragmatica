// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.db.migration.MigrationScript;
import org.pragmatica.db.migration.ParsedMigration;
import org.pragmatica.db.migration.SchemaMigrations;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Startup migrations for an exploded blueprint: schema/*.sql or schema/<datasource>/*.sql.
/// The process-wide queue serializes aliases too. It is released only after SQL and connector
/// cleanup settle; there is no timeout-driven retry or claim of cross-process exclusion.
public interface TerraMigrations {
    Promise<List<Report>> apply();

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
                           .map(entry -> new MigrationTask(entry.getKey(),
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
                                 .toList()).map(_ -> new MigrationRun(ownerBase,
                                                                      tasks,
                                                                      provider,
                                                                      SchemaMigrations.schemaMigrations()));
    }

    // Filesystem API boundary; callers lift all failures before provisioning any database.
    @org.pragmatica.lang.Contract
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

record MigrationTask(String datasource, List<MigrationScript> scripts) {}

record MigrationRun(String owner,
                    List<MigrationTask> tasks,
                    DatasourceConnectionProvider provider,
                    SchemaMigrations engine) implements TerraMigrations {
    public Promise<List<Report>> apply() {
        return MigrationQueue.submit(this::migrateAll);
    }

    private Promise<List<Report>> migrateAll() {
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
        Promise<org.pragmatica.aether.resource.db.SqlConnector> acquisition = Result.lift(Causes::fromThrowable,
                                                                                          () -> provider.connector(task.datasource()))
                                                                                    .fold(Promise::failure,
                                                                                          promise -> promise);

        return acquisition.flatMap(connector -> engine.migrate(task.datasource(),
                                                               task.scripts(),
                                                               connector,
                                                               "terra",
                                                               owner))
                          .map(result -> new Report(task.datasource(),
                                                    result))
                          .fold(result -> release(task.datasource()).fold(cleanup -> complete(result, cleanup)));
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
