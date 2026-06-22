// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/// Differential test that proves [StatementSplitter] against a real PostgreSQL engine.
///
/// Each realistic migration script is split with [StatementSplitter#split], then every emitted
/// [Statement] is executed in order against a live container — **one PostgreSQL command per
/// execute** — so the server itself adjudicates the boundaries:
///
///   - **Over-splitting** (a `$$` function body cut at an internal `;`, leaving a half statement)
///     produces a syntax error and the execute fails.
///   - **Under-splitting** (two commands merged into one statement) is rejected by PostgreSQL's
///     **extended query protocol**: a `Parse` message may carry only a single command, so a
///     [PreparedStatement] over a two-command string fails with
///     `cannot insert multiple commands into a prepared statement`. (A plain `java.sql.Statement`
///     uses the *simple* protocol, which silently accepts multiple commands and would NOT catch
///     this — so non-`COPY` statements run through a [PreparedStatement] on purpose. See the header
///     of [#executeOne] for the per-statement dispatch.)
///
/// After execution each case asserts the expected catalog object exists (function/table/trigger
/// present, or COPY row count), and case 1 additionally contrasts the splitter against a naive
/// `split(";")` whose middle fragment fails against the same engine — the real-engine proof of fix.
///
/// If Docker is unavailable the whole class is skipped via [#dockerAvailable] rather than failing.
@Testcontainers
class StatementSplitterPostgresDiffTest {
    private static final DialectSpec PG = Dialects.POSTGRESQL;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @BeforeAll
    static void requireDocker() {
        assumeTrue(dockerAvailable(), "Docker is not available — skipping real-PostgreSQL differential test");
    }

    /// Whether a Docker daemon is reachable, probed without throwing so the suite skips cleanly.
    private static boolean dockerAvailable() {
        try {
            return instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ----- corpus: each script exercises a distinct split hazard ----------------------

    /// Case 1: `$$`-bodied function with internal `;` followed by a `CREATE TABLE`.
    @Test
    void split_executesCleanly_forDollarQuotedFunctionBodyWithInternalSemicolons() {
        var script = """
            CREATE FUNCTION case1_inc(p int) RETURNS int AS $$
            BEGIN
                p := p + 1;
                RETURN p;
            END;
            $$ LANGUAGE plpgsql;
            CREATE TABLE case1_t (id int PRIMARY KEY);
            """;

        runScript("case1", script, 2);

        assertThat(functionExists("case1", "case1_inc")).isTrue();
        assertThat(tableExists("case1", "case1_t")).isTrue();
    }

    /// Case 2: tagged `$func$ … ; … $func$` body.
    @Test
    void split_executesCleanly_forTaggedDollarQuoteBody() {
        var script = """
            CREATE FUNCTION case2_pick() RETURNS int AS $func$
            BEGIN
                PERFORM 1; PERFORM 2;
                RETURN 7;
            END;
            $func$ LANGUAGE plpgsql;
            CREATE TABLE case2_t (id int);
            """;

        runScript("case2", script, 2);

        assertThat(functionExists("case2", "case2_pick")).isTrue();
        assertThat(tableExists("case2", "case2_t")).isTrue();
    }

    /// Case 3: anonymous `DO $$ BEGIN … ; … END $$;` block.
    @Test
    void split_executesCleanly_forAnonymousDoBlock() {
        var script = """
            CREATE TABLE case3_t (id int);
            DO $$
            BEGIN
                INSERT INTO case3_t VALUES (1);
                INSERT INTO case3_t VALUES (2);
            END $$;
            """;

        runScript("case3", script, 2);

        assertThat(tableExists("case3", "case3_t")).isTrue();
        assertThat(rowCount("case3", "case3_t")).isEqualTo(2L);
    }

    /// Case 4: nested block comment `/* outer /* inner */ outer */` before a statement.
    @Test
    void split_executesCleanly_forNestedBlockComment() {
        var script = """
            /* outer ; /* inner ; */ still outer ; */
            CREATE TABLE case4_t (id int);
            """;

        runScript("case4", script, 1);

        assertThat(tableExists("case4", "case4_t")).isTrue();
    }

    /// Case 5: `E'…;…'` and `'it''s; ok'` literals carrying `;` inside string values.
    @Test
    void split_executesCleanly_forSemicolonsInsideStringLiterals() {
        var script = """
            CREATE TABLE case5_t (raw text, esc text);
            INSERT INTO case5_t (raw, esc) VALUES ('it''s; ok', E'tab\\tand; semi');
            """;

        runScript("case5", script, 2);

        assertThat(tableExists("case5", "case5_t")).isTrue();
        assertThat(rowCount("case5", "case5_t")).isEqualTo(1L);
    }

    /// Case 6: `COPY t FROM STDIN;` with data lines containing `;`, a `\\.` terminator, and a
    /// following statement.
    @Test
    void split_executesCleanly_forCopyFromStdinBlock() {
        var script = """
            CREATE TABLE case6_t (a int, b text);
            COPY case6_t (a, b) FROM STDIN;
            1\twith ; semicolon
            2\tplain
            3\tanother ; one
            \\.
            CREATE TABLE case6_after (id int);
            """;

        runScript("case6", script, 3);

        assertThat(tableExists("case6", "case6_t")).isTrue();
        assertThat(tableExists("case6", "case6_after")).isTrue();
        assertThat(rowCount("case6", "case6_t")).isEqualTo(3L);
    }

    /// Case 7: trigger function plus the `CREATE TRIGGER` that binds it.
    @Test
    void split_executesCleanly_forTriggerFunctionCombo() {
        var script = """
            CREATE TABLE case7_t (id int, touched boolean DEFAULT false);
            CREATE FUNCTION case7_touch() RETURNS trigger AS $$
            BEGIN
                NEW.touched := true;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            CREATE TRIGGER case7_trg BEFORE INSERT ON case7_t
                FOR EACH ROW EXECUTE FUNCTION case7_touch();
            """;

        runScript("case7", script, 3);

        assertThat(functionExists("case7", "case7_touch")).isTrue();
        assertThat(triggerExists("case7", "case7_trg")).isTrue();
    }

    /// Case 8: plain multi-statement DDL — several `CREATE TABLE`s, no exotic spans.
    @Test
    void split_executesCleanly_forPlainMultiStatementDdl() {
        var script = """
            CREATE TABLE case8_a (id int);
            CREATE TABLE case8_b (id int);
            CREATE TABLE case8_c (id int);
            """;

        runScript("case8", script, 3);

        assertThat(tableExists("case8", "case8_a")).isTrue();
        assertThat(tableExists("case8", "case8_b")).isTrue();
        assertThat(tableExists("case8", "case8_c")).isTrue();
    }

    // ----- old-vs-new contrast: the real-engine proof of the fix ----------------------

    /// Splitting case 1 naively on `;` cuts the `$$` body, yielding a middle fragment that the
    /// engine rejects — whereas [StatementSplitter] yields statements that all execute cleanly.
    @Test
    void split_proves_naiveSemicolonSplitProducesAFragmentRejectedByPostgres() {
        var script = """
            CREATE FUNCTION contrast_inc(p int) RETURNS int AS $$
            BEGIN
                p := p + 1;
                RETURN p;
            END;
            $$ LANGUAGE plpgsql;
            CREATE TABLE contrast_t (id int);
            """;

        var statements = splitStatements(script);
        assertThat(statements).hasSizeGreaterThan(1);

        assertThat(anyFragmentRejected("contrast_old", naiveSplitOnSemicolon(script)))
            .as("naive ';' split must produce at least one fragment PostgreSQL rejects")
            .isTrue();
        assertThat(anyFragmentRejected("contrast_new", textsOf(statements)))
            .as("StatementSplitter must produce only statements PostgreSQL accepts")
            .isFalse();
    }

    // ----- script execution -----------------------------------------------------------

    /// Splits the script, asserts success and the expected statement count, then executes every
    /// statement in order inside a fresh schema so each case is isolated.
    ///
    /// @param schema   the per-case schema to create, use, and own the objects
    /// @param script   the migration script
    /// @param expected the expected number of split statements
    private void runScript(String schema, String script, int expected) {
        var statements = splitStatements(script);

        assertThat(statements).hasSize(expected);
        executeInSchema(schema, textsOf(statements));
    }

    private static List<Statement> splitStatements(String script) {
        return StatementSplitter.split(script, PG)
                                .onFailure(cause -> fail("split failed: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> textsOf(List<Statement> statements) {
        return statements.stream().map(Statement::text).toList();
    }

    /// Naive `;`-split standing in for the OLD behavior: cuts blindly, dropping empties.
    private static List<String> naiveSplitOnSemicolon(String script) {
        return List.of(script.split(";")).stream()
                   .map(String::trim)
                   .filter(fragment -> !fragment.isEmpty())
                   .toList();
    }

    // ----- per-statement execution against the live engine ----------------------------

    /// Creates a fresh schema, runs every statement against it in order (each as a single PG
    /// command), and fails the test if the engine rejects any statement. Schemas are dropped after
    /// each test by [#dropCaseSchemas].
    private void executeInSchema(String schema, List<String> statements) {
        try (var connection = openConnection(schema)) {
            for (var sql : statements) {
                executeOne(connection, sql);
            }
        } catch (SQLException | IOException e) {
            fail("statement execution failed in schema " + schema + ": " + e.getMessage(), e);
        }
    }

    /// Executes a single split statement as exactly ONE PostgreSQL command.
    ///
    /// `COPY … FROM STDIN` carries inline data and cannot ride the extended protocol, so it is fed
    /// through the driver's COPY API ([#executeCopy]). Every other statement runs through a
    /// [PreparedStatement] (extended protocol) so that a merged two-command string is rejected with
    /// `cannot insert multiple commands into a prepared statement` — this is what makes
    /// under-splitting fail loudly instead of silently succeeding under the simple protocol.
    private void executeOne(Connection connection, String sql) throws SQLException, IOException {
        if (isCopyFromStdin(sql)) {
            executeCopy(connection, sql);
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    /// Replays a single bounded `COPY … FROM STDIN` statement: the header line drives the COPY and
    /// the body lines between the header and the `\\.` terminator are streamed as the STDIN data.
    ///
    /// The verbatim statement may begin with blank/indented lines (the splitter preserves leading
    /// whitespace from the previous boundary), so the header is located by content — the first line
    /// whose `COPY … FROM STDIN` command appears — rather than by a fixed index.
    private void executeCopy(Connection connection, String sql) throws SQLException, IOException {
        var lines = sql.split("\n", -1);
        var header = headerLineIndex(lines);
        var command = stripTrailingSemicolon(lines[header].strip());
        var body = copyBody(lines, header + 1);

        connection.unwrap(PGConnection.class).getCopyAPI().copyIn(command, new StringReader(body));
    }

    /// Index of the `COPY … FROM STDIN` header line within the verbatim statement.
    private static int headerLineIndex(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (isCopyFromStdin(lines[i])) {
                return i;
            }
        }
        return fail("COPY statement has no 'COPY … FROM STDIN' header line");
    }

    private static String stripTrailingSemicolon(String command) {
        return command.endsWith(";")
               ? command.substring(0, command.length() - 1)
               : command;
    }

    /// Joins the COPY data lines (from the line after the header up to, but excluding, the `\\.`
    /// terminator) back into a newline-terminated STDIN payload.
    private static String copyBody(String[] lines, int from) {
        var builder = new StringBuilder();

        for (int i = from; i < lines.length && !lines[i].strip().equals("\\."); i++) {
            builder.append(lines[i]).append('\n');
        }
        return builder.toString();
    }

    /// Whether running every fragment as a single command produces at least one engine rejection.
    /// Each fragment runs in its own schema-scoped connection; the first rejection short-circuits.
    private boolean anyFragmentRejected(String schema, List<String> fragments) {
        try (var connection = openConnection(schema)) {
            return fragments.stream().anyMatch(fragment -> rejects(connection, fragment));
        } catch (SQLException e) {
            return fail("contrast connection failed: " + e.getMessage(), e);
        }
    }

    /// Whether the engine rejects this fragment as a single extended-protocol command.
    private static boolean rejects(Connection connection, String fragment) {
        try (PreparedStatement statement = connection.prepareStatement(fragment)) {
            statement.execute();
            return false;
        } catch (SQLException ignored) {
            return true;
        }
    }

    private static boolean isCopyFromStdin(String sql) {
        var upper = sql.stripLeading().toUpperCase(Locale.ROOT);
        return upper.startsWith("COPY ") && upper.contains("FROM STDIN");
    }

    // ----- catalog assertions ---------------------------------------------------------

    private boolean tableExists(String schema, String table) {
        return catalogFlag(schema,
                           "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                           table);
    }

    private boolean functionExists(String schema, String function) {
        return catalogFlag(schema,
                           "SELECT 1 FROM information_schema.routines WHERE routine_schema = ? AND routine_name = ?",
                           function);
    }

    private boolean triggerExists(String schema, String trigger) {
        return catalogFlag(schema,
                           "SELECT 1 FROM information_schema.triggers WHERE trigger_schema = ? AND trigger_name = ?",
                           trigger);
    }

    /// Runs a one-row existence probe parameterized by schema and object name.
    private boolean catalogFlag(String schema, String query, String object) {
        try (var connection = openConnection(schema);
             var statement = connection.prepareStatement(query)) {
            statement.setString(1, schema);
            statement.setString(2, object);
            return hasRow(statement);
        } catch (SQLException e) {
            return fail("catalog probe failed: " + e.getMessage(), e);
        }
    }

    private long rowCount(String schema, String table) {
        try (var connection = openConnection(schema);
             var statement = connection.prepareStatement("SELECT count(*) FROM " + schema + "." + table)) {
            return firstLong(statement);
        } catch (SQLException e) {
            return fail("row-count probe failed: " + e.getMessage(), e);
        }
    }

    private static boolean hasRow(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            return rs.next();
        }
    }

    private static long firstLong(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ----- per-case schema isolation --------------------------------------------------

    /// Opens a working connection for a case whose `search_path` is a freshly created, empty schema,
    /// so each case owns its objects in isolation.
    ///
    /// The schema is created on a throwaway bootstrap connection, then the working connection sets
    /// its `search_path` through the startup `options` property rather than a runtime `SET`. A
    /// runtime `SET search_path` makes the server emit an asynchronous `ParameterStatus` message that
    /// desyncs a later `COPY … FROM STDIN` on the same connection (pgjdbc: "Unexpected packet type
    /// during copy"); resolving the schema at startup consumes that message during connect.
    private Connection openConnection(String schema) throws SQLException {
        createSchema(schema);

        var properties = new Properties();
        properties.setProperty("user", POSTGRES.getUsername());
        properties.setProperty("password", POSTGRES.getPassword());
        properties.setProperty("options", "-c search_path=" + schema);
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), properties);
    }

    private static void createSchema(String schema) throws SQLException {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                                                          POSTGRES.getUsername(),
                                                          POSTGRES.getPassword());
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        }
    }

    @AfterEach
    void dropCaseSchemas() {
        if (!dockerAvailable() || !POSTGRES.isRunning()) {
            return;
        }
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                                                          POSTGRES.getUsername(),
                                                          POSTGRES.getPassword());
             java.sql.Statement statement = connection.createStatement()) {
            dropAllCaseSchemas(statement);
        } catch (SQLException ignored) {
            // best-effort teardown; the shared container is discarded after the class
        }
    }

    private static void dropAllCaseSchemas(java.sql.Statement statement) throws SQLException {
        for (var schema : List.of("case1", "case2", "case3", "case4", "case5", "case6", "case7", "case8",
                                  "contrast_old", "contrast_new")) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
