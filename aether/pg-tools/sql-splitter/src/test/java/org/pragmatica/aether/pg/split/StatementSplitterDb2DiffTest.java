// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/// Differential test that proves [StatementSplitter] against a real DB2 engine.
///
/// Each DB2 migration script is split with [StatementSplitter#split] using [Dialects#DB2], then
/// every emitted [Statement] is executed in order against a live container — **one DB2 command per
/// execute** — so the server itself adjudicates the boundaries:
///
///   - **Over-splitting** (a `CREATE PROCEDURE` SQL-PL body cut at one of its internal `;`, leaving a
///     half statement) produces an `SQLCODE` syntax error and the execute fails.
///   - **Under-splitting** (two commands merged into one statement) is rejected because the DB2 JDBC
///     driver forwards a single command per execute: a merged `…; …` string trips an
///     `SQLCODE=-104` syntax error at the embedded `;`, so a merged two-command string fails loudly.
///
/// The headline behavior is the `--#SET TERMINATOR` redefinable terminator: `--#SET TERMINATOR @`
/// switches the active terminator to `@` so a `CREATE PROCEDURE … BEGIN … ; … END@` body keeps its
/// internal `;` and ends only on `@`, after which `--#SET TERMINATOR ;` restores `;`. The splitter
/// CONSUMES both directive lines (they are never sent to DB2) and strips the trailing `@`, so the
/// procedure reaches DB2 as a single compound statement. A `'a;b'` string literal and a
/// `"weird;id"` quoted identifier are opaque spans so a `;` inside either never splits.
///
/// After execution each case asserts the expected catalog object exists (procedure/table present),
/// and case 1 additionally contrasts the splitter against a naive `split(";")` whose body fragment
/// DB2 rejects — the real-engine proof of the fix.
///
/// Each object is uniquely named and dropped after each test via [#dropCaseObjects], so cases stay
/// isolated within the container's single test database (the test user owns it).
///
/// Heavy: needs a real DB2 engine, so the class is tagged `heavy-db` and excluded from the default
/// build. If Docker is unavailable the whole class is skipped via [#dockerAvailable] rather than
/// failing.
@Tag("heavy-db")
@Testcontainers
class StatementSplitterDb2DiffTest {
    private static final DialectSpec DB2 = Dialects.DB2;

    @Container
    private static final Db2Container DB2_DB =
        new Db2Container("icr.io/db2_community/db2:11.5.9.0").acceptLicense();

    @BeforeAll
    static void requireDocker() {
        assumeTrue(dockerAvailable(), "Docker is not available — skipping real-DB2 differential test");
        awaitConnectable();
    }

    /// Whether a Docker daemon is reachable, probed without throwing so the suite skips cleanly.
    private static boolean dockerAvailable() {
        try {
            return instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /// Blocks until DB2 actually accepts JDBC connections, not merely until the container reports
    /// "started". The [Db2Container] readiness wait is a log-message match (~36s), but DB2 does not
    /// activate its database and open the listener until some time AFTER that line is logged — so the
    /// first connections race the activation and are bounced with `[jcc][2057] The application server
    /// rejected establishment of the connection`. This gate retries an actual open + [Connection#isValid]
    /// until one succeeds, sleeping ~3s between attempts up to a ~4-minute deadline, and SKIPS the test
    /// (aborts the assumption) with the last [SQLException] if DB2 never becomes connectable — an
    /// unreachable engine is an unmet precondition (harness/environment), not a splitter defect.
    private static void awaitConnectable() {
        var deadlineNanos = System.nanoTime() + READINESS_DEADLINE.toNanos();
        var lastFailure = "no connection attempt completed";
        while (System.nanoTime() < deadlineNanos) {
            try (var connection = openConnection()) {
                if (connection.isValid(5)) {
                    return;
                }
                lastFailure = "connection opened but isValid(5) returned false";
            } catch (SQLException e) {
                lastFailure = e.getMessage();
            }
            sleepBetweenAttempts();
        }
        abort("DB2 never became connectable within " + READINESS_DEADLINE
              + " — skipping real-DB2 differential test (engine unavailable, not a splitter failure): "
              + lastFailure);
    }

    /// Sleeps between readiness attempts, restoring the interrupt flag if interrupted so the JUnit
    /// runner can still observe cancellation.
    private static void sleepBetweenAttempts() {
        try {
            Thread.sleep(READINESS_RETRY_INTERVAL.toMillis());
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final Duration READINESS_DEADLINE = Duration.ofMinutes(4);
    private static final Duration READINESS_RETRY_INTERVAL = Duration.ofSeconds(3);

    // ----- corpus: each script exercises a distinct DB2 split hazard ------------------

    /// Headline case: `--#SET TERMINATOR @` then a `CREATE PROCEDURE` whose SQL-PL body carries
    /// internal `;`, terminated by `@`; then `--#SET TERMINATOR ;` and a plain `CREATE TABLE`. The
    /// splitter consumes both directives, strips the `@`, and emits the procedure (internal `;` intact)
    /// and the table as two statements.
    @Test
    void split_executesCleanly_forSetTerminatorProcedureThenTable() {
        var script = """
            --#SET TERMINATOR @
            CREATE PROCEDURE proc_inc()
            BEGIN
                DECLARE x INT;
                SET x = 1;
            END@
            --#SET TERMINATOR ;
            CREATE TABLE proc_t (id INT NOT NULL PRIMARY KEY);
            """;

        runScript(script, 2);

        assertThat(procedureExists("PROC_INC")).isTrue();
        assertThat(tableExists("PROC_T")).isTrue();
    }

    /// Plain multi-statement DDL — several `CREATE TABLE`s split on the default `;`, no directive, no
    /// exotic spans.
    @Test
    void split_executesCleanly_forPlainMultiStatementDdl() {
        var script = """
            CREATE TABLE multi_a (id INT);
            CREATE TABLE multi_b (id INT);
            CREATE TABLE multi_c (id INT);
            """;

        runScript(script, 3);

        assertThat(tableExists("MULTI_A")).isTrue();
        assertThat(tableExists("MULTI_B")).isTrue();
        assertThat(tableExists("MULTI_C")).isTrue();
    }

    /// A `'a;b'` string literal carrying a `;`: the string span is opaque, so the statement splits as
    /// ONE and the literal `a;b` is inserted verbatim.
    @Test
    void split_executesCleanly_forStringLiteralWithSemicolon() {
        var script = """
            CREATE TABLE str_t (payload VARCHAR(32));
            INSERT INTO str_t (payload) VALUES ('a;b');
            """;

        runScript(script, 2);

        assertThat(tableExists("STR_T")).isTrue();
        assertThat(rowCount("STR_T")).isEqualTo(1L);
        assertThat(singleString("SELECT payload FROM str_t")).isEqualTo("a;b");
    }

    /// A `"weird;id"` double-quote quoted identifier carrying a `;`: the identifier span is opaque, so
    /// the statement splits as ONE and the table exists under its literal `;`-bearing name.
    @Test
    void split_executesCleanly_forQuotedIdentifierWithSemicolon() {
        var script = """
            CREATE TABLE "weird;id" (id INT);
            CREATE TABLE plain_after (id INT);
            """;

        runScript(script, 2);

        assertThat(tableExists("weird;id")).isTrue();
        assertThat(tableExists("PLAIN_AFTER")).isTrue();
    }

    // ----- old-vs-new contrast: the real-engine proof of the fix ----------------------

    /// Splitting the procedure script naively on `;` cuts the SQL-PL body, yielding a fragment DB2
    /// rejects — whereas [StatementSplitter] yields statements that all execute cleanly.
    @Test
    void split_proves_naiveSemicolonSplitProducesAFragmentRejectedByDb2() {
        var script = """
            --#SET TERMINATOR @
            CREATE PROCEDURE contrast_proc()
            BEGIN
                DECLARE x INT;
                SET x = 1;
            END@
            --#SET TERMINATOR ;
            CREATE TABLE contrast_t (id INT);
            """;

        var statements = splitStatements(script);
        assertThat(statements).hasSizeGreaterThan(1);

        assertThat(anyFragmentRejected(naiveSplitOnSemicolon(script)))
            .as("naive ';' split must produce at least one fragment DB2 rejects")
            .isTrue();
        assertThat(anyFragmentRejected(textsOf(statements)))
            .as("StatementSplitter must produce only statements DB2 accepts")
            .isFalse();
    }

    // ----- script execution -----------------------------------------------------------

    /// Splits the script, asserts success and the expected statement count, then executes every
    /// statement in order against the container's test database.
    ///
    /// @param script   the migration script
    /// @param expected the expected number of split statements
    private void runScript(String script, int expected) {
        var statements = splitStatements(script);

        assertThat(statements).hasSize(expected);
        executeStatements(textsOf(statements));
    }

    private static List<Statement> splitStatements(String script) {
        return StatementSplitter.split(script, DB2)
                                .onFailure(cause -> fail("split failed: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> textsOf(List<Statement> statements) {
        return statements.stream().map(Statement::text).toList();
    }

    /// Naive `;`-split standing in for the OLD behavior: cuts blindly on `;`, dropping empties. Also
    /// strips `--#SET TERMINATOR` directive lines, which the OLD naive path would otherwise feed to
    /// DB2 as bare unrecognized commands — so the rejected fragment is the cut SQL-PL body, not the
    /// (trivially invalid) directive, making the contrast a real boundary-cut proof.
    private static List<String> naiveSplitOnSemicolon(String script) {
        return List.of(script.split(";")).stream()
                   .map(String::trim)
                   .filter(fragment -> !fragment.isEmpty())
                   .filter(fragment -> !fragment.toUpperCase(Locale.ROOT).startsWith("--#SET TERMINATOR"))
                   .toList();
    }

    // ----- per-statement execution against the live engine ----------------------------

    /// Runs every statement against the test database in order (each as a single DB2 command), and
    /// fails the test if the engine rejects any statement.
    private void executeStatements(List<String> statements) {
        try (var connection = openConnection()) {
            for (var sql : statements) {
                executeOne(connection, sql);
            }
        } catch (SQLException e) {
            fail("statement execution failed: " + e.getMessage(), e);
        }
    }

    /// Executes a single split statement as exactly ONE DB2 command through a [PreparedStatement].
    ///
    /// A `CREATE PROCEDURE` reaches the driver as a single compound statement with internal `;` intact
    /// and NO trailing `@` (the splitter consumed it). A merged two-command string fails with an
    /// `SQLCODE=-104` syntax error at the embedded `;` — this is what makes under-splitting fail
    /// loudly.
    private void executeOne(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    /// Whether running every fragment as a single command produces at least one engine rejection.
    /// Each fragment runs against the test database; the first rejection short-circuits.
    private boolean anyFragmentRejected(List<String> fragments) {
        try (var connection = openConnection()) {
            return fragments.stream().anyMatch(fragment -> rejects(connection, fragment));
        } catch (SQLException e) {
            return fail("contrast connection failed: " + e.getMessage(), e);
        }
    }

    /// Whether the engine rejects this fragment as a single command.
    private static boolean rejects(Connection connection, String fragment) {
        try (PreparedStatement statement = connection.prepareStatement(fragment)) {
            statement.execute();
            return false;
        } catch (SQLException ignored) {
            return true;
        }
    }

    // ----- catalog assertions ---------------------------------------------------------

    private boolean tableExists(String table) {
        return catalogFlag("SELECT 1 FROM syscat.tables WHERE tabname = ?", table);
    }

    private boolean procedureExists(String procedure) {
        return catalogFlag("SELECT 1 FROM syscat.routines WHERE routinename = ? AND routinetype = 'P'",
                          procedure);
    }

    /// Runs a one-row existence probe parameterized by the object name. DB2 folds unquoted identifiers
    /// to UPPERCASE in its catalog, while a quoted `"weird;id"` keeps its exact case — callers pass
    /// the catalog-stored form.
    private boolean catalogFlag(String query, String object) {
        try (var connection = openConnection();
             var statement = connection.prepareStatement(query)) {
            statement.setString(1, object);
            return hasRow(statement);
        } catch (SQLException e) {
            return fail("catalog probe failed: " + e.getMessage(), e);
        }
    }

    private long rowCount(String table) {
        try (var connection = openConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM " + table)) {
            return firstLong(statement);
        } catch (SQLException e) {
            return fail("row-count probe failed: " + e.getMessage(), e);
        }
    }

    private String singleString(String query) {
        try (var connection = openConnection();
             var statement = connection.prepareStatement(query)) {
            return firstString(statement);
        } catch (SQLException e) {
            return fail("string probe failed: " + e.getMessage(), e);
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

    private static String firstString(PreparedStatement statement) throws SQLException {
        try (ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getString(1).strip();
        }
    }

    // ----- per-case isolation ---------------------------------------------------------

    /// Opens a working connection against the container's single test database — the only schema the
    /// test user owns. Cases stay isolated through unique object names plus per-test teardown in
    /// [#dropCaseObjects].
    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
            DB2_DB.getJdbcUrl(), DB2_DB.getUsername(), DB2_DB.getPassword());
    }

    /// Drops every object any case may have created so a re-run starts clean, regardless of which test
    /// ran. Best-effort: the shared container is discarded after the class anyway.
    @AfterEach
    void dropCaseObjects() {
        if (!dockerAvailable() || !DB2_DB.isRunning()) {
            return;
        }
        try (var connection = openConnection();
             var statement = connection.createStatement()) {
            for (var procedure : CASE_PROCEDURES) {
                dropQuietly(statement, "DROP PROCEDURE " + procedure);
            }
            for (var table : CASE_TABLES) {
                dropQuietly(statement, "DROP TABLE " + table);
            }
        } catch (SQLException ignored) {
            // best-effort teardown; the shared container is discarded after the class
        }
    }

    /// DB2's `DROP … IF EXISTS` support varies by version, so each drop is attempted and its "does
    /// not exist" failure swallowed — teardown is best-effort and order-independent.
    private static void dropQuietly(java.sql.Statement statement, String ddl) {
        try {
            statement.execute(ddl);
        } catch (SQLException ignored) {
            // object absent for this case — nothing to drop
        }
    }

    private static final List<String> CASE_PROCEDURES = List.of("PROC_INC", "CONTRAST_PROC");
    private static final List<String> CASE_TABLES = List.of(
        "PROC_T", "MULTI_A", "MULTI_B", "MULTI_C", "STR_T", "\"weird;id\"", "PLAIN_AFTER", "CONTRAST_T");
}
