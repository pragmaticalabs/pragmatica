// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.util.List;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.db.migration.MigrationScript;
import org.pragmatica.db.migration.MigrationError;
import org.pragmatica.db.migration.SchemaMigrations;
import org.pragmatica.db.migration.SchemaHistoryRepository;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;


public interface AetherSchemaManager {
    /// `owner` is the blueprint whose artifact declared these scripts. Every entry point claims
    /// migration ownership of the PHYSICAL database
    /// ([SchemaHistoryRepository#claimOwnership]) right after bootstrap and before reading applied
    /// history, so a database already claimed by a different blueprint is refused with
    /// [SchemaError.PhysicalDatasourceOwnershipConflict] having written nothing.
    Promise<SchemaResult> migrate(String datasource,
                                  List<MigrationEntry> scripts,
                                  SqlConnector connector,
                                  String nodeId,
                                  BlueprintId owner);

    Promise<SchemaResult> undo(String datasource,
                               int targetVersion,
                               List<MigrationEntry> scripts,
                               SqlConnector connector,
                               String nodeId,
                               BlueprintId owner);

    Promise<SchemaResult> baseline(String datasource,
                                   int baselineVersion,
                                   List<MigrationEntry> scripts,
                                   SqlConnector connector,
                                   String nodeId,
                                   BlueprintId owner);

    static AetherSchemaManager aetherSchemaManager(SchemaPolicy policy) {
        return new SharedSchemaAdapter(policy, SchemaMigrations.schemaMigrations());
    }

    /// Exposes the [SchemaPolicy] this manager was built with, so a caller that must bound its OWN
    /// operation against the same policy (#760/#724 review round 2 item c —
    /// [SchemaOrchestratorService]'s per-attempt migration timeout) reads the single source of truth
    /// instead of duplicating the bound. Defaults to [SchemaPolicy#schemaPolicy()] so existing test
    /// doubles that implement this interface without a policy of their own keep compiling.
    default SchemaPolicy policy() {
        return SchemaPolicy.schemaPolicy();
    }

    record SchemaResult(int appliedCount, int currentVersion, long totalMs) {
        public static SchemaResult schemaResult(int appliedCount, int currentVersion, long totalMs) {
            return new SchemaResult(appliedCount, currentVersion, totalMs);
        }
    }
}

record SharedSchemaAdapter(SchemaPolicy policy, SchemaMigrations engine) implements AetherSchemaManager {
    public Promise<SchemaResult> migrate(String datasource,
                                         List<MigrationEntry> scripts,
                                         SqlConnector connector,
                                         String nodeId,
                                         BlueprintId owner) {
        return adapt(engine.migrate(datasource,
                                    scripts(scripts),
                                    connector,
                                    nodeId,
                                    owner.base().asString()));
    }

    public Promise<SchemaResult> undo(String datasource,
                                      int version,
                                      List<MigrationEntry> scripts,
                                      SqlConnector connector,
                                      String nodeId,
                                      BlueprintId owner) {
        return adapt(engine.undo(datasource,
                                 version,
                                 scripts(scripts),
                                 connector,
                                 nodeId,
                                 owner.base().asString()));
    }

    public Promise<SchemaResult> baseline(String datasource,
                                          int version,
                                          List<MigrationEntry> scripts,
                                          SqlConnector connector,
                                          String nodeId,
                                          BlueprintId owner) {
        return adapt(engine.baseline(datasource,
                                     version,
                                     scripts(scripts),
                                     connector,
                                     nodeId,
                                     owner.base().asString()));
    }

    private static List<MigrationScript> scripts(List<MigrationEntry> scripts) {
        return scripts.stream()
                      .map(entry -> MigrationScript.migrationScript(entry.filename(),
                                                                    entry.sql(),
                                                                    entry.checksum()))
                      .toList();
    }

    private static Promise<SchemaResult> adapt(Promise<SchemaMigrations.SchemaResult> result) {
        return result.map(value -> SchemaResult.schemaResult(value.appliedCount(),
                                                             value.currentVersion(),
                                                             value.totalMs()))
                     .mapError(SharedSchemaAdapter::aetherError);
    }

    private static Cause aetherError(Cause cause) {
        return switch (cause) {
            case Causes.CompositeCause composite -> mapComposite(composite);
            case MigrationError.ChecksumMismatch e -> SchemaError.ChecksumMismatch.checksumMismatch(e.datasource(),
                                                                                                    e.version(),
                                                                                                    e.expected(),
                                                                                                    e.actual());
            case MigrationError.BaselineConflict e -> SchemaError.BaselineConflict.baselineConflict(e.datasource(),
                                                                                                    e.existingVersion());
            case MigrationError.UndoNotAvailable e -> SchemaError.UndoNotAvailable.undoNotAvailable(e.datasource(),
                                                                                                    e.version());
            case MigrationError.InvalidMigrationFormat e -> SchemaError.InvalidMigrationFormat.invalidMigrationFormat(e.filename(),
                                                                                                                      e.detail());
            case MigrationError.PhysicalDatasourceOwnershipConflict e -> SchemaError.PhysicalDatasourceOwnershipConflict.physicalDatasourceOwnershipConflict(e.datasource(),
                                                                                                                                                             e.currentOwnerBase(),
                                                                                                                                                             e.rejectedBase());
            default -> cause;
        };
    }

    private static Cause mapComposite(Causes.CompositeCause composite) {
        var mapped = Causes.composite();

        composite.stream().map(SharedSchemaAdapter::aetherError).forEach(mapped::append);

        return mapped;
    }
}
