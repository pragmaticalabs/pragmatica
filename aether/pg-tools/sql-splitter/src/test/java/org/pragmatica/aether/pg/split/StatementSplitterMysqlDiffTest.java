// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/// Differential test that proves [StatementSplitter] against a real MySQL engine.
///
/// Each MySQL migration script is split with [StatementSplitter#split] using [Dialects#MYSQL],
/// then every emitted [Statement] is executed in order against a live container — **one MySQL
/// command per execute** — so the server itself adjudicates the boundaries:
///
///   - **Over-splitting** (e.g. a `DELIMITER`-wrapped procedure body cut at one of its internal
///     `;`, leaving a half statement) produces a syntax error and the execute fails.
///   - **Under-splitting** (two commands merged into one statement) is rejected because the
///     MySQL JDBC driver defaults to `allowMultiQueries=false`: a single execute carrying two
///     `;`-separated commands fails with a syntax error near the second command. So a merged
///     two-command string is caught loudly rather than silently succeeding.
///
/// The headline case is the stored procedure: the splitter STRIPS the `DELIMITER` directives, so
/// the `CREATE PROCEDURE … BEGIN … ; … ; … END` body reaches MySQL as a single normal statement
/// (no client-side `DELIMITER` support needed) — verified by querying `information_schema.routines`.
///
/// After execution each case asserts the expected catalog object exists (routine/table present, or
/// row count), and the procedure case additionally contrasts the splitter against a naive
/// `split(";")` whose body fragment MySQL rejects — the real-engine proof of the fix.
///
/// All cases share the container's single test database (the test user owns it but lacks
/// `CREATE DATABASE`), so each object is uniquely named to keep cases isolated; the suite drops
/// every case object after each test via [#dropCaseObjects].
///
/// If Docker is unavailable the whole class is skipped via [#dockerAvailable] rather than failing.
@Testcontainers
class StatementSplitterMysqlDiffTest {
    private static final DialectSpec MYSQL = Dialects.MYSQL;

    @Container
    private static final MySQLContainer<?> MY_SQL = new MySQLContainer<>("mysql:8.0");

    @BeforeAll
    static void requireDocker() {
        assumeTrue(dockerAvailable(), "Docker is not available — skipping real-MySQL differential test");
    }

    /// Whether a Docker daemon is reachable, probed without throwing so the suite skips cleanly.
    private static boolean dockerAvailable() {
        try {
            return instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ----- corpus: each script exercises a distinct MySQL split hazard ----------------

    /// Headline case: a `DELIMITER $$ … CREATE PROCEDURE … BEGIN …; …; END$$ DELIMITER ;` stored
    /// procedure. The splitter strips the directives and emits the procedure as ONE statement with
    /// its internal `;` intact, followed by a `CREATE TABLE`.
    @Test
    void split_executesCleanly_forDelimiterWrappedStoredProcedure() {
        var script = """
            DELIMITER $$
            CREATE PROCEDURE proc_inc(INOUT p INT)
            BEGIN
                SET p = p + 1;
                SET p = p + 1;
            END$$
            DELIMITER ;
            CREATE TABLE proc_t (id INT PRIMARY KEY);
            """;

        runScript(script, 2);

        assertThat(routineExists("proc_inc")).isTrue();
        assertThat(tableExists("proc_t")).isTrue();
    }

    /// Backtick-quoted identifier carrying a `;` inside the column name, plus a following statement.
    @Test
    void split_executesCleanly_forBacktickIdentifierWithSemicolon() {
        var script = """
            CREATE TABLE `weird;tbl` (`id;col` INT);
            CREATE TABLE plain_after (id INT);
            """;

        runScript(script, 2);

        assertThat(tableExists("weird;tbl")).isTrue();
        assertThat(tableExists("plain_after")).isTrue();
    }

    /// A `"a;b"` double-quote STRING literal (MySQL default, without `ANSI_QUOTES`) carrying a `;`.
    @Test
    void split_executesCleanly_forSemicolonInsideDoubleQuoteString() {
        var script = """
            CREATE TABLE dq_t (raw VARCHAR(32));
            INSERT INTO dq_t (raw) VALUES ("a;b");
            """;

        runScript(script, 2);

        assertThat(tableExists("dq_t")).isTrue();
        assertThat(rowCount("dq_t")).isEqualTo(1L);
    }

    /// A `#` line comment carrying a `;`, before the statement terminator.
    @Test
    void split_executesCleanly_forSemicolonInsideHashComment() {
        var script = """
            CREATE TABLE hash_t (id INT) # trailing ; comment
            ;
            CREATE TABLE hash_after (id INT);
            """;

        runScript(script, 2);

        assertThat(tableExists("hash_t")).isTrue();
        assertThat(tableExists("hash_after")).isTrue();
    }

    /// A flat (non-nesting) `/* a; b */` block comment carrying a `;`.
    @Test
    void split_executesCleanly_forSemicolonInsideBlockComment() {
        var script = """
            CREATE TABLE block_t (id INT) /* note ; with semicolon */;
            CREATE TABLE block_after (id INT);
            """;

        runScript(script, 2);

        assertThat(tableExists("block_t")).isTrue();
        assertThat(tableExists("block_after")).isTrue();
    }

    /// Plain multi-statement DDL — several `CREATE TABLE`s, no exotic spans.
    @Test
    void split_executesCleanly_forPlainMultiStatementDdl() {
        var script = """
            CREATE TABLE multi_a (id INT);
            CREATE TABLE multi_b (id INT);
            CREATE TABLE multi_c (id INT);
            """;

        runScript(script, 3);

        assertThat(tableExists("multi_a")).isTrue();
        assertThat(tableExists("multi_b")).isTrue();
        assertThat(tableExists("multi_c")).isTrue();
    }

    // ----- old-vs-new contrast: the real-engine proof of the fix ----------------------

    /// Splitting the procedure script naively on `;` cuts the body, yielding a fragment MySQL
    /// rejects — whereas [StatementSplitter] yields statements that all execute cleanly.
    @Test
    void split_proves_naiveSemicolonSplitProducesAFragmentRejectedByMysql() {
        var script = """
            DELIMITER $$
            CREATE PROCEDURE contrast_proc(INOUT p INT)
            BEGIN
                SET p = p + 1;
                SET p = p + 1;
            END$$
            DELIMITER ;
            CREATE TABLE contrast_t (id INT);
            """;

        var statements = splitStatements(script);
        assertThat(statements).hasSizeGreaterThan(1);

        assertThat(anyFragmentRejected(naiveSplitOnSemicolon(script)))
            .as("naive ';' split must produce at least one fragment MySQL rejects")
            .isTrue();
        assertThat(anyFragmentRejected(textsOf(statements)))
            .as("StatementSplitter must produce only statements MySQL accepts")
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
        return StatementSplitter.split(script, MYSQL)
                                .onFailure(cause -> fail("split failed: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> textsOf(List<Statement> statements) {
        return statements.stream().map(Statement::text).toList();
    }

    /// Naive `;`-split standing in for the OLD behavior: cuts blindly, dropping empties. Also
    /// strips `DELIMITER` directive lines, which the OLD naive path would otherwise feed to MySQL
    /// as bare unrecognized commands — so the rejected fragment is the cut procedure body, not the
    /// (trivially invalid) directive, making the contrast a real boundary-cut proof.
    private static List<String> naiveSplitOnSemicolon(String script) {
        return List.of(script.split(";")).stream()
                   .map(String::trim)
                   .filter(fragment -> !fragment.isEmpty())
                   .filter(fragment -> !fragment.toUpperCase(Locale.ROOT).startsWith("DELIMITER"))
                   .toList();
    }

    // ----- per-statement execution against the live engine ----------------------------

    /// Runs every statement against the test database in order (each as a single MySQL command),
    /// and fails the test if the engine rejects any statement.
    private void executeStatements(List<String> statements) {
        try (var connection = openConnection()) {
            for (var sql : statements) {
                executeOne(connection, sql);
            }
        } catch (SQLException e) {
            fail("statement execution failed: " + e.getMessage(), e);
        }
    }

    /// Executes a single split statement as exactly ONE MySQL command through a [PreparedStatement].
    ///
    /// With the driver default `allowMultiQueries=false`, a merged two-command string fails with a
    /// syntax error at the second command — this is what makes under-splitting fail loudly.
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
        return catalogFlag("SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                          table);
    }

    private boolean routineExists(String routine) {
        return catalogFlag("SELECT 1 FROM information_schema.routines WHERE routine_schema = ? AND routine_name = ?",
                          routine);
    }

    /// Runs a one-row existence probe parameterized by the test database and object name.
    private boolean catalogFlag(String query, String object) {
        try (var connection = openConnection();
             var statement = connection.prepareStatement(query)) {
            statement.setString(1, MY_SQL.getDatabaseName());
            statement.setString(2, object);
            return hasRow(statement);
        } catch (SQLException e) {
            return fail("catalog probe failed: " + e.getMessage(), e);
        }
    }

    private long rowCount(String table) {
        try (var connection = openConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM `" + table + "`")) {
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

    // ----- per-case isolation ---------------------------------------------------------

    /// Opens a working connection against the container's single test database — the only schema
    /// the test user owns, since it lacks `CREATE DATABASE`. Cases stay isolated through unique
    /// object names plus per-test teardown in [#dropCaseObjects].
    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(MY_SQL.getJdbcUrl(), MY_SQL.getUsername(), MY_SQL.getPassword());
    }

    /// Drops every object any case may have created so a re-run starts clean, regardless of which
    /// test ran. Best-effort: the shared container is discarded after the class anyway.
    @AfterEach
    void dropCaseObjects() {
        if (!dockerAvailable() || !MY_SQL.isRunning()) {
            return;
        }
        try (var connection = openConnection();
             var statement = connection.createStatement()) {
            for (var routine : CASE_ROUTINES) {
                statement.execute("DROP PROCEDURE IF EXISTS `" + routine + "`");
            }
            for (var table : CASE_TABLES) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
        } catch (SQLException ignored) {
            // best-effort teardown; the shared container is discarded after the class
        }
    }

    private static final List<String> CASE_ROUTINES = List.of("proc_inc", "contrast_proc");
    private static final List<String> CASE_TABLES = List.of(
        "proc_t", "weird;tbl", "plain_after", "dq_t", "hash_t", "hash_after",
        "block_t", "block_after", "multi_a", "multi_b", "multi_c", "contrast_t");
}
