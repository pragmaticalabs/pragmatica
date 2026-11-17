// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.aether.resource.db.DatabaseType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// T7 (#2100 E-B L1): with no schema every statement the history repository and the evolution renderers emit is
/// byte-identical to what they emitted before schema scoping existed, for EVERY [DatabaseType].
///
/// The fixture `history-statements.fixture` was CAPTURED from the unmodified code (the commit that adds it precedes
/// every behaviour change in this lane), so a green run proves backward compatibility rather than self-agreement.
/// To recapture deliberately, run with `-Dhistory.fixture.capture=<absolute path>` and review the diff.
class SchemaHistoryStatementsFixtureTest {
    private static final String FIXTURE = "history-statements.fixture";
    private static final String CAPTURE_PROPERTY = "history.fixture.capture";

    @Test
    void historyStatements_withoutSchema_matchTheCapturedFixture() throws IOException {
        var actual = render();
        var capturePath = System.getProperty(CAPTURE_PROPERTY);

        if (capturePath != null) {
            Files.writeString(Path.of(capturePath), actual, StandardCharsets.UTF_8);
            return;
        }

        assertThat(actual).isEqualTo(readFixture());
    }

    @Test
    void historyStatements_fixtureCoversEveryDialectAndEveryRepositoryOperation() throws IOException {
        var fixture = readFixture();

        for (var type : DatabaseType.values()) {
            assertThat(fixture).as("dialect " + type).contains("[" + type + "]");
        }

        for (var label : List.of("evolution.meta", "evolution.owner", "evolution.v1", "evolution.v2", "bootstrap", "bootstrapFrom1",
                                 "claimOwnership", "queryApplied", "recordMigration", "recordInProgress", "updateProgress",
                                 "markStatus", "finalizeSuccess", "queryProgress", "removeMigration", "queryRepeatableChecksum")) {
            assertThat(fixture).as("operation " + label).contains(" " + label + " ");
        }
    }

    private static String render() {
        var out = new StringBuilder();

        for (var type : DatabaseType.values()) {
            out.append('[').append(type).append("]\n");
            line(out, "evolution.meta", List.of(SchemaHistoryEvolution.createMetaTableSql(type)));
            line(out, "evolution.owner", List.of(SchemaHistoryEvolution.createOwnerTableSql(type)));
            line(out, "evolution.v1", SchemaHistoryEvolution.STEP_V1.ddl().render(type));
            line(out, "evolution.v2", SchemaHistoryEvolution.STEP_V2.ddl().render(type));
            captureOperations(out, type);
        }

        return out.toString();
    }

    private static void captureOperations(StringBuilder out, DatabaseType type) {
        HistoryOperations.record(type, SchemaHistoryRepository.schemaHistoryRepository())
                         .forEach((label, statements) -> line(out, label, statements));
    }

    private static void line(StringBuilder out, String label, List<String> statements) {
        out.append(' ').append(label).append(" \n");

        for (var statement : statements) {
            out.append("    ").append(statement.replace("\n", "\\n")).append('\n');
        }
    }

    private static String readFixture() throws IOException {
        try (InputStream in = SchemaHistoryStatementsFixtureTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture resource " + FIXTURE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
