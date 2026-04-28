// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;


public final class SchemaRoutes implements RouteSource {
    private static final Cause SCHEMA_NOT_FOUND = Causes.cause("Schema status not found for datasource");

    private static final Cause SCHEMA_NOT_FAILED = Causes.cause("Schema is not in FAILED state — retry only applies to failed migrations");

    private final Supplier<ManageableNode> nodeSupplier;

    private SchemaRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static SchemaRoutes schemaRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new SchemaRoutes(nodeSupplier);
    }

    record SchemaStatusResponse(String datasource, int currentVersion, String lastMigration, String status) {
        static SchemaStatusResponse schemaStatusResponse(SchemaVersionValue v) {
            return new SchemaStatusResponse(v.datasourceName(),
                                            v.currentVersion(),
                                            v.lastMigration(),
                                            v.status().name());
        }
    }

    record SchemaStatusListResponse(List<SchemaStatusResponse> datasources){}

    record SchemaMigrateResponse(boolean success, String message) {
        static SchemaMigrateResponse schemaMigrateResponse(boolean success, String message) {
            return new SchemaMigrateResponse(success, message);
        }
    }

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<SchemaStatusListResponse>route(ManagementRoute.SCHEMA_STATUS_ALL)
                                         .toJson(this::allSchemaStatuses),
                         ManagementRoutes.<SchemaStatusResponse>route(ManagementRoute.SCHEMA_STATUS_ONE)
                                         .withPath(aString())
                                         .to(this::singleSchemaStatus)
                                         .asJson(),
                         ManagementRoutes.<SchemaStatusResponse>route(ManagementRoute.SCHEMA_HISTORY)
                                         .withPath(aString())
                                         .to(this::schemaHistory)
                                         .asJson(),
                         ManagementRoutes.<SchemaMigrateResponse>route(ManagementRoute.SCHEMA_MIGRATE)
                                         .withPath(aString())
                                         .to(this::triggerMigration)
                                         .asJson(),
                         ManagementRoutes.<SchemaMigrateResponse>route(ManagementRoute.SCHEMA_UNDO)
                                         .withPath(aString())
                                         .withQuery(QueryParameter.aString("targetVersion"))
                                         .to(this::undoMigration)
                                         .asJson(),
                         ManagementRoutes.<SchemaMigrateResponse>route(ManagementRoute.SCHEMA_BASELINE)
                                         .withPath(aString())
                                         .withQuery(QueryParameter.aString("version"))
                                         .to(this::baselineDatasource)
                                         .asJson(),
                         ManagementRoutes.<SchemaMigrateResponse>route(ManagementRoute.SCHEMA_RETRY)
                                         .withPath(aString())
                                         .to(this::retryMigration)
                                         .asJson());
    }

    private SchemaStatusListResponse allSchemaStatuses() {
        var entries = new ArrayList<SchemaStatusResponse>();
        nodeSupplier.get().kvStore()
                        .forEach(SchemaVersionKey.class,
                                 SchemaVersionValue.class,
                                 (_, value) -> entries.add(SchemaStatusResponse.schemaStatusResponse(value)));
        return new SchemaStatusListResponse(entries);
    }

    private Promise<SchemaStatusResponse> singleSchemaStatus(String datasource) {
        return lookupSchemaVersion(datasource).map(SchemaStatusResponse::schemaStatusResponse);
    }

    private Promise<SchemaStatusResponse> schemaHistory(String datasource) {
        return lookupSchemaVersion(datasource).map(SchemaStatusResponse::schemaStatusResponse);
    }

    private Promise<SchemaMigrateResponse> triggerMigration(String datasource) {
        return lookupSchemaVersion(datasource).flatMap(current -> writeMigratingStatus(current, datasource));
    }

    private Promise<SchemaMigrateResponse> undoMigration(String datasource, Option<String> targetVersionOpt) {
        var targetVersion = targetVersionOpt.map(SchemaRoutes::parseIntSafe).or(0);
        return lookupSchemaVersion(datasource).flatMap(current -> writeUndoStatus(current, datasource, targetVersion));
    }

    private Promise<SchemaMigrateResponse> baselineDatasource(String datasource, Option<String> versionOpt) {
        var version = versionOpt.map(SchemaRoutes::parseIntSafe).or(1);
        return writeBaselineStatus(datasource, version);
    }

    private Promise<SchemaVersionValue> lookupSchemaVersion(String datasource) {
        var key = SchemaVersionKey.schemaVersionKey(datasource);
        return nodeSupplier.get().kvStore()
                               .get(key)
                               .filter(v -> v instanceof SchemaVersionValue)
                               .map(v -> (SchemaVersionValue) v)
                               .async(SCHEMA_NOT_FOUND);
    }

    private Promise<SchemaMigrateResponse> writeMigratingStatus(SchemaVersionValue current, String datasource) {
        var updated = SchemaVersionValue.schemaVersionValue(datasource,
                                                            current.currentVersion(),
                                                            current.lastMigration(),
                                                            SchemaStatus.MIGRATING,
                                                            current.artifactCoords());
        return applySchemaUpdate(datasource, updated).map(_ -> SchemaMigrateResponse.schemaMigrateResponse(true,
                                                                                                           "Migration triggered for " + datasource));
    }

    private Promise<SchemaMigrateResponse> writeUndoStatus(SchemaVersionValue current,
                                                           String datasource,
                                                           int targetVersion) {
        var updated = SchemaVersionValue.schemaVersionValue(datasource,
                                                            targetVersion,
                                                            current.lastMigration(),
                                                            SchemaStatus.PENDING,
                                                            current.artifactCoords());
        return applySchemaUpdate(datasource, updated).map(_ -> SchemaMigrateResponse.schemaMigrateResponse(true,
                                                                                                           "Undo to version " + targetVersion + " initiated for " + datasource));
    }

    private Promise<SchemaMigrateResponse> writeBaselineStatus(String datasource, int version) {
        var baselined = SchemaVersionValue.schemaVersionValue(datasource,
                                                              version,
                                                              "V" + String.format("%03d", version) + "__baseline",
                                                              SchemaStatus.COMPLETED);
        return applySchemaUpdate(datasource, baselined).map(_ -> SchemaMigrateResponse.schemaMigrateResponse(true,
                                                                                                             "Baselined " + datasource + " at version " + version));
    }

    private Promise<List<Long>> applySchemaUpdate(String datasource, SchemaVersionValue value) {
        var key = SchemaVersionKey.schemaVersionKey(datasource);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
        return nodeSupplier.get().apply(List.of(command));
    }

    private Promise<SchemaMigrateResponse> retryMigration(String datasource) {
        return lookupSchemaVersion(datasource).flatMap(current -> writeRetryStatus(current, datasource))
                                  .onSuccess(_ -> AuditLog.schemaManualRetry(datasource));
    }

    private Promise<SchemaMigrateResponse> writeRetryStatus(SchemaVersionValue current, String datasource) {
        if (current.status() != SchemaStatus.FAILED) {return SCHEMA_NOT_FAILED.promise();}
        var updated = SchemaVersionValue.schemaVersionValue(datasource,
                                                            current.currentVersion(),
                                                            current.lastMigration(),
                                                            SchemaStatus.PENDING,
                                                            current.artifactCoords(),
                                                            0);
        return applySchemaUpdate(datasource, updated).map(_ -> SchemaMigrateResponse.schemaMigrateResponse(true,
                                                                                                           "Retry initiated for " + datasource));
    }

    private static int parseIntSafe(String value) {
        return Integer.parseInt(value);
    }
}
