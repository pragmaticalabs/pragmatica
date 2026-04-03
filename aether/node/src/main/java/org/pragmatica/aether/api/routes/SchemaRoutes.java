/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.node.AetherNode;
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


/// Routes for datasource schema management: status, history, migrate, undo, baseline.
public final class SchemaRoutes implements RouteSource {
    private static final Cause SCHEMA_NOT_FOUND = Causes.cause("Schema status not found for datasource");

    private static final Cause SCHEMA_NOT_FAILED = Causes.cause("Schema is not in FAILED state — retry only applies to failed migrations");

    private final Supplier<AetherNode> nodeSupplier;

    private SchemaRoutes(Supplier<AetherNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static SchemaRoutes schemaRoutes(Supplier<AetherNode> nodeSupplier) {
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
        return Stream.of(Route.<SchemaStatusListResponse>get("/api/schema/status").toJson(this::allSchemaStatuses),
                         Route.<SchemaStatusResponse>get("/api/schema/status")
                              .withPath(aString())
                              .to(this::singleSchemaStatus)
                              .asJson(),
                         Route.<SchemaStatusResponse>get("/api/schema/history")
                              .withPath(aString())
                              .to(this::schemaHistory)
                              .asJson(),
                         Route.<SchemaMigrateResponse>post("/api/schema/migrate")
                              .withPath(aString())
                              .to(this::triggerMigration)
                              .asJson(),
                         Route.<SchemaMigrateResponse>post("/api/schema/undo")
                              .withPath(aString())
                              .withQuery(QueryParameter.aString("targetVersion"))
                              .to(this::undoMigration)
                              .asJson(),
                         Route.<SchemaMigrateResponse>post("/api/schema/baseline")
                              .withPath(aString())
                              .withQuery(QueryParameter.aString("version"))
                              .to(this::baselineDatasource)
                              .asJson(),
                         Route.<SchemaMigrateResponse>post("/api/schema/retry")
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
