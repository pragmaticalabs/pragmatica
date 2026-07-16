// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.builder;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.lang.Result;


public final class MigrationProcessor {
    private final PostgresParser parser;

    private MigrationProcessor(PostgresParser parser) {
        this.parser = parser;
    }

    public static MigrationProcessor create() {
        return new MigrationProcessor(PostgresParser.create());
    }

    public Result<List<SchemaEvent>> analyzeScript(String sql) {
        return parser.parseCst(sql)
                     .flatMap(DdlAnalyzer::analyze);
    }

    public Result<Schema> processAll(List<String> migrationScripts) {
        var allEvents = new ArrayList<SchemaEvent>();

        for (var sql : migrationScripts) {
            var result = analyzeScript(sql);

            if (result.isFailure()) {
                return result.flatMap(_ -> Result.success(Schema.empty()));
            }

            allEvents.addAll(result.unwrap());
        }

        return SchemaBuilder.build(allEvents);
    }

    public Result<List<Schema>> processStepwise(List<String> migrationScripts) {
        var snapshots = new ArrayList<Schema>();
        var current = Schema.empty();

        for (var sql : migrationScripts) {
            var eventsResult = analyzeScript(sql);

            if (eventsResult.isFailure()) {
                return eventsResult.flatMap(_ -> Result.success(List.of()));
            }

            var schemaResult = SchemaBuilder.apply(current, eventsResult.unwrap());

            if (schemaResult.isFailure()) {
                return schemaResult.flatMap(_ -> Result.success(List.of()));
            }

            current = schemaResult.unwrap();
            snapshots.add(current);
        }

        return Result.success(snapshots);
    }
}
