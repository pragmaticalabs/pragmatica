// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.testcontainers.DockerClientFactory.instance;

/// Differential test that proves [StatementSplitter] against a real SQL Server (T-SQL) engine.
///
/// Each T-SQL migration script is split with [StatementSplitter#split] using [Dialects#SQLSERVER],
/// then every emitted [Statement] is executed in order against a live container — **one T-SQL batch
/// per execute** — so the server itself adjudicates the boundaries:
///
///   - **Over-splitting** (a batch cut at one of its internal `;`, leaving a half batch) produces a
///     syntax error and the execute fails.
///   - **Under-splitting** (two `GO`-separated batches merged into one statement) is caught because
///     SQL Server forbids `GO` inside a single command — the `GO` is a client batch separator, not
///     T-SQL — so a merged string carrying a `GO` line fails with `Incorrect syntax near 'GO'`.
///
/// The headline behavior is `semicolonTerminates=false`: a top-level `;` NEVER splits, so a batch
/// like `SELECT 1; SELECT 2; SELECT 3;` reaches the splitter as ONE [Statement] and executes as a
/// single multi-statement T-SQL batch (multiple result sets in one `execute()`). Only a line-anchored
/// `GO` bounds a batch, and the splitter strips it (it is never sent to the driver). Bracket
/// identifiers (`[…]`), national-string literals (`N'…'`), and NESTED block comments are opaque spans
/// so a `;` inside any of them never splits.
///
/// After execution each case asserts the expected catalog object exists (table present, or row
/// count), and case 1 additionally contrasts the splitter against a naive `split(";")`: because `;`
/// does not bound a T-SQL batch, the naive split shreds a single batch into fragments and orphans the
/// `GO` line, at least one of which the engine rejects — the real-engine proof of the fix.
///
/// Each object is uniquely named and dropped after each test via [#dropCaseObjects], so cases stay
/// isolated within the container's `tempdb` working database.
///
/// Heavy: needs a real SQL Server engine, so the class is tagged `heavy-db` and excluded from the
/// default build. If Docker is unavailable the whole class is skipped via [#dockerAvailable] rather
/// than failing.
@Tag("heavy-db")
@Testcontainers
class StatementSplitterSqlServerDiffTest {
    private static final DialectSpec SQLSERVER = Dialects.SQLSERVER;

    @Container
    private static final MSSQLServerContainer<?> SQL_SERVER =
        new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    @BeforeAll
    static void requireDocker() {
        assumeTrue(dockerAvailable(), "Docker is not available — skipping real-SQLServer differential test");
    }

    /// Whether a Docker daemon is reachable, probed without throwing so the suite skips cleanly.
    private static boolean dockerAvailable() {
        try {
            return instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ----- corpus: each script exercises a distinct T-SQL split hazard ----------------

    /// Two `GO`-separated batches: the first creates a table, the second inserts a row. The splitter
    /// strips both `GO` lines and emits the two batches as two statements.
    @Test
    void split_executesCleanly_forTwoGoSeparatedBatches() {
        var script = """
            CREATE TABLE go_t (id INT);
            GO
            INSERT INTO go_t (id) VALUES (1);
            GO
            """;

        runScript(script, 2);

        assertThat(tableExists("go_t")).isTrue();
        assertThat(rowCount("go_t")).isEqualTo(1L);
    }

    /// A single batch with three `;`-separated commands. `semicolonTerminates=false`, so the splitter
    /// yields it as ONE [Statement], and that single multi-statement batch executes fine as one T-SQL
    /// batch (multiple result sets allowed in one `execute()`).
    @Test
    void split_yieldsSingleBatch_forMultipleSemicolonsInOneBatch() {
        var script = "SELECT 1; SELECT 2; SELECT 3;";

        var statements = splitStatements(script);
        assertThat(statements).hasSize(1);

        executeStatements(textsOf(statements));
    }

    /// A `CREATE TABLE [weird;name](…)` whose bracket identifier carries a `;`: the bracket span is
    /// opaque, so the statement splits as ONE and the table exists under its literal `;`-bearing name.
    @Test
    void split_executesCleanly_forBracketIdentifierWithSemicolon() {
        var script = """
            CREATE TABLE [weird;name] (id INT);
            GO
            """;

        runScript(script, 1);

        assertThat(tableExists("weird;name")).isTrue();
    }

    /// An `INSERT … VALUES (N'a;b')` national-string literal carrying a `;`: the `N'…'` span is
    /// opaque, so the statement splits as ONE and the literal `a;b` is inserted verbatim.
    @Test
    void split_executesCleanly_forNationalStringWithSemicolon() {
        var script = """
            CREATE TABLE ns_t (raw NVARCHAR(32));
            GO
            INSERT INTO ns_t (raw) VALUES (N'a;b');
            GO
            """;

        runScript(script, 2);

        assertThat(tableExists("ns_t")).isTrue();
        assertThat(rowCount("ns_t")).isEqualTo(1L);
        assertThat(singleString("SELECT raw FROM ns_t")).isEqualTo("a;b");
    }

    /// A NESTED block comment `/* outer /* inner */ still ; */` carrying a `;`: T-SQL nests, so the
    /// comment span swallows the inner `*/` and the trailing `;`, and does not cause a spurious split.
    @Test
    void split_executesCleanly_forNestedBlockCommentWithSemicolon() {
        var script = """
            /* outer /* inner */ still ; */
            CREATE TABLE nest_t (id INT);
            GO
            """;

        runScript(script, 1);

        assertThat(tableExists("nest_t")).isTrue();
    }

    // ----- old-vs-new contrast: the real-engine proof of the fix ----------------------

    /// Splitting the two-batch script naively on `;` shreds each batch and orphans the `GO` lines —
    /// yielding fragments SQL Server rejects (notably the bare `GO`) — whereas [StatementSplitter]
    /// yields batches that all execute cleanly.
    @Test
    void split_proves_naiveSemicolonSplitProducesAFragmentRejectedBySqlServer() {
        var script = """
            CREATE TABLE contrast_t (id INT);
            GO
            INSERT INTO contrast_t (id) VALUES (1);
            GO
            """;

        var statements = splitStatements(script);
        assertThat(statements).hasSize(2);

        assertThat(anyFragmentRejected(naiveSplitOnSemicolon(script)))
            .as("naive ';' split must produce at least one fragment SQL Server rejects")
            .isTrue();
        assertThat(anyFragmentRejected(textsOf(statements)))
            .as("StatementSplitter must produce only statements SQL Server accepts")
            .isFalse();
    }

    // ----- script execution -----------------------------------------------------------

    /// Splits the script, asserts success and the expected statement count, then executes every
    /// statement in order against the working database.
    ///
    /// @param script   the migration script
    /// @param expected the expected number of split statements
    private void runScript(String script, int expected) {
        var statements = splitStatements(script);

        assertThat(statements).hasSize(expected);
        executeStatements(textsOf(statements));
    }

    private static List<Statement> splitStatements(String script) {
        return StatementSplitter.split(script, SQLSERVER)
                                .onFailure(cause -> fail("split failed: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> textsOf(List<Statement> statements) {
        return statements.stream().map(Statement::text).toList();
    }

    /// Naive `;`-split standing in for the OLD behavior: cuts blindly on `;`, dropping empties. For
    /// T-SQL this is doubly wrong — `;` does not bound a batch — so the fragments include the orphaned
    /// `GO` lines the engine rejects, making the contrast a real boundary proof.
    private static List<String> naiveSplitOnSemicolon(String script) {
        return List.of(script.split(";")).stream()
                   .map(String::trim)
                   .filter(fragment -> !fragment.isEmpty())
                   .toList();
    }

    // ----- per-statement execution against the live engine ----------------------------

    /// Runs every statement against the working database in order (each as a single T-SQL batch), and
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

    /// Executes a single split statement as exactly ONE T-SQL batch through a [PreparedStatement]. A
    /// batch may legitimately carry several `;`-separated commands (multiple result sets); a merged
    /// string carrying a `GO` line fails with `Incorrect syntax near 'GO'` — this is what makes
    /// under-splitting across batch boundaries fail loudly.
    private void executeOne(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    /// Whether running every fragment as a single batch produces at least one engine rejection. Each
    /// fragment runs against the working database; the first rejection short-circuits.
    ///
    /// The whole pass runs inside an explicit transaction that is ALWAYS rolled back (SQL Server has
    /// transactional DDL), so any side effect a fragment commits-in-spirit — notably a successful
    /// `CREATE TABLE` ahead of the fragment that gets rejected — is discarded. This keeps two
    /// successive contrast passes against the same database independent: the naive pass's successful
    /// `CREATE` can no longer persist and pollute the splitter pass into an "already exists" rejection.
    private boolean anyFragmentRejected(List<String> fragments) {
        try (var connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                return fragments.stream().anyMatch(fragment -> rejects(connection, fragment));
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            return fail("contrast connection failed: " + e.getMessage(), e);
        }
    }

    /// Whether the engine rejects this fragment as a single batch.
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
        return catalogFlag("SELECT 1 FROM sys.tables WHERE name = ?", table);
    }

    /// Runs a one-row existence probe parameterized by the object name.
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
             var statement = connection.prepareStatement("SELECT count(*) FROM [" + table + "]")) {
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
            return rs.getString(1);
        }
    }

    // ----- per-case isolation ---------------------------------------------------------

    /// Opens a working connection against `tempdb` — a per-session scratch database SQL Server
    /// recreates on restart — so cases never pollute a durable schema. Cases stay isolated through
    /// unique object names plus per-test teardown in [#dropCaseObjects].
    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
            SQL_SERVER.getJdbcUrl() + ";databaseName=tempdb",
            SQL_SERVER.getUsername(),
            SQL_SERVER.getPassword());
    }

    /// Drops every object any case may have created so a re-run starts clean, regardless of which
    /// test ran. Best-effort: the shared container is discarded after the class anyway.
    @AfterEach
    void dropCaseObjects() {
        if (!dockerAvailable() || !SQL_SERVER.isRunning()) {
            return;
        }
        try (var connection = openConnection();
             var statement = connection.createStatement()) {
            for (var table : CASE_TABLES) {
                statement.execute("DROP TABLE IF EXISTS [" + table + "]");
            }
        } catch (SQLException ignored) {
            // best-effort teardown; the shared container is discarded after the class
        }
    }

    private static final List<String> CASE_TABLES = List.of(
        "go_t", "weird;name", "ns_t", "nest_t", "contrast_t");
}
