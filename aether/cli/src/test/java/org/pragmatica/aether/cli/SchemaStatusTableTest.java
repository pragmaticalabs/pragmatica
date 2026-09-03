// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// #542/#550: `owningBlueprint` is the field that tells an operator WHOSE slices a non-COMPLETED
/// schema record is holding — a slice is withheld from activation if and only if its own blueprint
/// owns a datasource in PENDING, MIGRATING or FAILED.
///
/// #760/#724 review round 2 item k: `heldSlices` names WHICH of that blueprint's slices are being
/// withheld. `SchemaRoutes.heldSlices` returns `[]` for a COMPLETED record (nothing is blocking) and
/// a real artifact list for a blocking status — the `orders_db`/`billing_db` fixtures below mirror
/// that split rather than inventing a shape production never produces.
///
/// A `TableSpec` column with a wrong `jsonPath` does not fail: `OutputFormatter.navigateToField`
/// returns `""` for a missing node, so the column renders blank and the build stays green. Only a
/// test that actually RUNS the renderer over a real response body can catch that, which is why
/// these assertions go through `OutputFormatter.printQuery` with the production `TableSpec`
/// constants rather than re-declaring columns locally.
class SchemaStatusTableTest {
    private static final String STATUS_ALL_RESPONSE = """
                                                      {"datasources":[\
                                                      {"datasource":"orders_db","currentVersion":3,\
                                                      "lastMigration":"V003__add_index.sql","status":"COMPLETED",\
                                                      "heldSlices":[],\
                                                      "owningBlueprint":"org.example:my-app:1.0.0"},\
                                                      {"datasource":"billing_db","currentVersion":7,\
                                                      "lastMigration":"V007__add_ledger.sql","status":"FAILED",\
                                                      "heldSlices":["org.example:billing-worker:2.1.0"],\
                                                      "owningBlueprint":"org.example:billing:2.1.0"}]}""";

    private static final String STATUS_ONE_RESPONSE = """
                                                      {"datasource":"orders_db","currentVersion":3,\
                                                      "lastMigration":"V003__add_index.sql","status":"COMPLETED",\
                                                      "heldSlices":[],\
                                                      "owningBlueprint":"org.example:my-app:1.0.0"}""";

    /// #760/#724 review round 3 item 2(e): a FAILED record can hold MORE than one slice, and the
    /// rendered `heldSlices` JSON array (78 chars for these two artifacts) must not be silently cut
    /// by the `HELD SLICES` column's fixed width — the single-slice fixture above (36 rendered
    /// chars) never exceeded the old width and could not catch that truncation.
    private static final String STATUS_TWO_HELD_SLICES_RESPONSE = """
                                                                  {"datasources":[\
                                                                  {"datasource":"orders_db","currentVersion":3,\
                                                                  "lastMigration":"V003__add_index.sql","status":"COMPLETED",\
                                                                  "heldSlices":[],\
                                                                  "owningBlueprint":"org.example:my-app:1.0.0"},\
                                                                  {"datasource":"billing_db","currentVersion":7,\
                                                                  "lastMigration":"V007__add_ledger.sql","status":"FAILED",\
                                                                  "heldSlices":["org.example:billing-worker:2.1.0",\
                                                                  "org.example:billing-ledger-writer:2.1.0"],\
                                                                  "owningBlueprint":"org.example:billing:2.1.0"}]}""";

    private PrintStream originalOut;
    private ByteArrayOutputStream outCapture;

    @BeforeEach
    void redirectOut() {
        originalOut = System.out;
        outCapture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    @Test
    void printQuery_rendersEveryColumn_forStatusListResponse() {
        var exit = OutputFormatter.printQuery(STATUS_ALL_RESPONSE,
                                              parseOptions("--format", "table"),
                                              AetherCli.SchemaCommand.SCHEMA_STATUS_ALL_TABLE);

        assertThat(exit).isEqualTo(ExitCode.SUCCESS);
        assertThat(captured()).contains("DATASOURCE")
                              .contains("STATUS")
                              .contains("HELD SLICES")
                              .contains("VERSION")
                              .contains("LAST MIGRATION")
                              .contains("OWNING BLUEPRINT")
                              .contains("orders_db")
                              .contains("COMPLETED")
                              .contains("V003__add_index.sql")
                              .contains("org.example:my-app:1.0.0");
    }

    /// The diagnostic the recovery workflow depends on: the held blueprint must be readable off the
    /// FAILED row, and it must be the failing record's OWN owner — not the other row's.
    @Test
    void printQuery_namesTheHoldingBlueprint_forFailedRecord() {
        OutputFormatter.printQuery(STATUS_ALL_RESPONSE,
                                   parseOptions("--format", "table"),
                                   AetherCli.SchemaCommand.SCHEMA_STATUS_ALL_TABLE);

        var failedRow = rowContaining("billing_db");

        assertThat(failedRow).contains("FAILED")
                             .contains("org.example:billing:2.1.0")
                             .doesNotContain("org.example:my-app:1.0.0");
    }

    /// #760/#724 review round 2 item k: `heldSlices` names the actual slice artifacts a blocking
    /// status is withholding, distinct from `owningBlueprint` (whose deploy is stuck). A COMPLETED
    /// record never has one — `SchemaRoutes.heldSlices` short-circuits to `[]` for non-blocking
    /// statuses — so the healthy row must NOT carry the FAILED row's held slice name.
    @Test
    void printQuery_rendersHeldSlices_forFailedRecordOnly() {
        OutputFormatter.printQuery(STATUS_ALL_RESPONSE,
                                   parseOptions("--format", "table"),
                                   AetherCli.SchemaCommand.SCHEMA_STATUS_ALL_TABLE);

        var failedRow = rowContaining("billing_db");
        var completedRow = rowContaining("orders_db");

        assertThat(failedRow).contains("org.example:billing-worker:2.1.0");
        assertThat(completedRow).doesNotContain("org.example:billing-worker:2.1.0");
    }

    /// #760/#724 review round 3 item 2(e): `OutputFormatter.extractColumnValue` hard-truncates any
    /// value longer than the column width with no wrapping, so a record holding more than one slice
    /// silently lost artifact names off the end of the `HELD SLICES` column.
    @Test
    void printQuery_rendersHeldSlices_untruncated_forMultipleHeldSlices() {
        OutputFormatter.printQuery(STATUS_TWO_HELD_SLICES_RESPONSE,
                                   parseOptions("--format", "table"),
                                   AetherCli.SchemaCommand.SCHEMA_STATUS_ALL_TABLE);

        var failedRow = rowContaining("billing_db");

        assertThat(failedRow).contains("org.example:billing-worker:2.1.0")
                             .contains("org.example:billing-ledger-writer:2.1.0");
    }

    @Test
    void printQuery_rendersOwningBlueprint_forSingleDatasourceResponse() {
        var exit = OutputFormatter.printQuery(STATUS_ONE_RESPONSE,
                                              parseOptions("--format", "table"),
                                              AetherCli.SchemaCommand.SCHEMA_STATUS_ONE_TABLE);

        assertThat(exit).isEqualTo(ExitCode.SUCCESS);
        assertThat(captured()).contains("OWNING BLUEPRINT")
                              .contains("orders_db")
                              .contains("org.example:my-app:1.0.0");
    }

    @Test
    void printQuery_emitsOwningBlueprintColumn_forCsvFormat() {
        var exit = OutputFormatter.printQuery(STATUS_ALL_RESPONSE,
                                              parseOptions("--format", "csv"),
                                              AetherCli.SchemaCommand.SCHEMA_STATUS_ALL_TABLE);

        assertThat(exit).isEqualTo(ExitCode.SUCCESS);
        assertThat(captured().lines().toList()).hasSize(3)
                                               .anySatisfy(line -> assertThat(line).endsWith("OWNING BLUEPRINT"))
                                               .anySatisfy(line -> assertThat(line).endsWith("org.example:billing:2.1.0"))
                                               .anySatisfy(line -> assertThat(line).contains("HELD SLICES"))
                                               .anySatisfy(line -> assertThat(line).contains("org.example:billing-worker:2.1.0"));
    }

    @Test
    void printQuery_extractsOwningBlueprint_forFieldSelector() {
        var exit = OutputFormatter.printQuery(STATUS_ONE_RESPONSE,
                                              parseOptions("--field", "owningBlueprint"),
                                              AetherCli.SchemaCommand.SCHEMA_STATUS_ONE_TABLE);

        assertThat(exit).isEqualTo(ExitCode.SUCCESS);
        assertThat(captured().strip()).isEqualTo("org.example:my-app:1.0.0");
    }

    private String captured() {
        return outCapture.toString(StandardCharsets.UTF_8);
    }

    private String rowContaining(String marker) {
        return captured().lines()
                         .filter(line -> line.contains(marker))
                         .findFirst()
                         .orElse("");
    }

    private static OutputOptions parseOptions(String... args) {
        var options = new OutputOptions();

        new CommandLine(new Holder(options)).parseArgs(args);

        return options;
    }

    @CommandLine.Command(name = "holder")
    private static class Holder {
        @CommandLine.Mixin
        private final OutputOptions options;

        Holder(OutputOptions options) {
            this.options = options;
        }
    }
}
