// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

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

/// Differential test that proves [StatementSplitter] against a real Oracle engine.
///
/// Each Oracle migration script is split with [StatementSplitter#split] using [Dialects#ORACLE],
/// then every emitted [Statement] is executed in order against a live container — **one Oracle
/// command per execute** — so the server itself adjudicates the boundaries:
///
///   - **Over-splitting** (a PL/SQL block body cut at one of its internal `;`, leaving a half
///     statement) produces an `ORA-` syntax error and the execute fails.
///   - **Under-splitting** (two commands merged into one statement) is rejected by Oracle's JDBC
///     driver, which forwards a single command per execute: a merged `…; …` string trips
///     `ORA-00911: invalid character` (the trailing `;` after the first command), so a merged
///     two-command string fails loudly rather than silently succeeding.
///
/// The headline behavior is PL/SQL BLOCK MODE: the splitter recognizes a `CREATE [OR REPLACE]
/// PROCEDURE|FUNCTION|TRIGGER|PACKAGE` (and anonymous `BEGIN`/`DECLARE`) block, keeps its internal
/// `;` intact, and ends it only on a `/`-line — which the splitter consumes without emitting — so
/// the block reaches Oracle as a single statement (executed WITHOUT the trailing `/`, exactly as
/// the JDBC driver requires). Oracle alternative quoting `q'X…X'` is treated as an opaque span so a
/// `;` inside it never splits.
///
/// After execution each case asserts the expected catalog object exists (procedure/table present,
/// or row count), and case 1 additionally contrasts the splitter against a naive `split(";")` whose
/// body fragment Oracle rejects — the real-engine proof of the fix.
///
/// Each object is uniquely named and dropped after each test via [#dropCaseObjects], so cases stay
/// isolated within the container's single test schema (the test user owns it).
///
/// Heavy: needs a real Oracle engine and the right CPU arch, so the class is tagged `heavy-db` and
/// excluded from the default build. If Docker is unavailable the whole class is skipped via
/// [#dockerAvailable] rather than failing.
@Tag("heavy-db")
@Testcontainers
class StatementSplitterOracleDiffTest {
    private static final DialectSpec ORACLE = Dialects.ORACLE;

    @Container
    private static final OracleContainer ORACLE_DB =
        new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

    @BeforeAll
    static void requireDocker() {
        assumeTrue(dockerAvailable(), "Docker is not available — skipping real-Oracle differential test");
    }

    /// Whether a Docker daemon is reachable, probed without throwing so the suite skips cleanly.
    private static boolean dockerAvailable() {
        try {
            return instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ----- corpus: each script exercises a distinct Oracle split hazard ---------------

    /// Headline case: a `CREATE OR REPLACE PROCEDURE … BEGIN …; …; END;` block with internal `;`,
    /// terminated by a `/`-line the splitter consumes, followed by a plain `CREATE TABLE`. The
    /// splitter emits the block (internal `;` intact) as ONE statement and the table as a second.
    @Test
    void split_executesCleanly_forCreateOrReplaceProcedureBlockThenTable() {
        var script = """
            CREATE OR REPLACE PROCEDURE proc_inc(p IN OUT NUMBER) AS
            BEGIN
                p := p + 1;
                p := p + 1;
            END;
            /
            CREATE TABLE proc_t (id NUMBER PRIMARY KEY);
            """;

        runScript(script, 2);

        assertThat(objectExists("PROCEDURE", "PROC_INC")).isTrue();
        assertThat(tableExists("PROC_T")).isTrue();
    }

    /// An anonymous `BEGIN … INSERT …; … END;` block terminated by a `/`-line, inserting into a
    /// pre-created table. The splitter emits the table create and the block as two statements; the
    /// block's two inserts land two rows.
    @Test
    void split_executesCleanly_forAnonymousBeginBlock() {
        var script = """
            CREATE TABLE anon_t (id NUMBER);
            BEGIN
                INSERT INTO anon_t VALUES (1);
                INSERT INTO anon_t VALUES (2);
            END;
            /
            """;

        runScript(script, 2);

        assertThat(tableExists("ANON_T")).isTrue();
        assertThat(rowCount("ANON_T")).isEqualTo(2L);
    }

    /// A `q'[a;b]'` alt-quoted literal carrying a `;`: the splitter treats the whole `q'…'` span as
    /// opaque, so the statement splits as ONE and the literal `a;b` is inserted verbatim.
    @Test
    void split_executesCleanly_forAltQuotedLiteralWithSemicolon() {
        var script = """
            CREATE TABLE altq_t (payload VARCHAR2(32));
            INSERT INTO altq_t (payload) VALUES (q'[a;b]');
            """;

        runScript(script, 2);

        assertThat(tableExists("ALTQ_T")).isTrue();
        assertThat(rowCount("ALTQ_T")).isEqualTo(1L);
        assertThat(singleString("SELECT payload FROM altq_t")).isEqualTo("a;b");
    }

    /// Plain multi-statement DDL — several `CREATE TABLE`s split on `;`, no block, no exotic spans.
    @Test
    void split_executesCleanly_forPlainMultiStatementDdl() {
        var script = """
            CREATE TABLE multi_a (id NUMBER);
            CREATE TABLE multi_b (id NUMBER);
            CREATE TABLE multi_c (id NUMBER);
            """;

        runScript(script, 3);

        assertThat(tableExists("MULTI_A")).isTrue();
        assertThat(tableExists("MULTI_B")).isTrue();
        assertThat(tableExists("MULTI_C")).isTrue();
    }

    /// Known limitation (#337 tail): `CREATE TYPE … AS OBJECT` matches the PL/SQL block-start
    /// keywords, so the splitter enters BLOCK MODE and over-captures everything up to the next
    /// `/`-line — folding a following plain statement into the type body. This test DOCUMENTS the
    /// edge (it is disabled, not asserting correctness) so the gap stays visible in the suite.
    @Test
    @Disabled("known limitation: CREATE TYPE ... AS OBJECT over-captured as a PL/SQL block — #337 tail")
    void split_overCapturesCreateTypeAsObject_knownLimitation() {
        var script = """
            CREATE TYPE addr_typ AS OBJECT (street VARCHAR2(40), city VARCHAR2(40));
            /
            CREATE TABLE type_t (id NUMBER);
            """;

        runScript(script, 2);

        assertThat(objectExists("TYPE", "ADDR_TYP")).isTrue();
        assertThat(tableExists("TYPE_T")).isTrue();
    }

    // ----- old-vs-new contrast: the real-engine proof of the fix ----------------------

    /// Splitting the procedure script naively on `;` cuts the block body, yielding a fragment Oracle
    /// rejects — whereas [StatementSplitter] yields statements that all execute cleanly.
    @Test
    void split_proves_naiveSemicolonSplitProducesAFragmentRejectedByOracle() {
        var script = """
            CREATE OR REPLACE PROCEDURE contrast_proc(p IN OUT NUMBER) AS
            BEGIN
                p := p + 1;
                p := p + 1;
            END;
            /
            CREATE TABLE contrast_t (id NUMBER);
            """;

        var statements = splitStatements(script);
        assertThat(statements).hasSizeGreaterThan(1);

        assertThat(anyFragmentRejected(naiveSplitOnSemicolon(script)))
            .as("naive ';' split must produce at least one fragment Oracle rejects")
            .isTrue();
        assertThat(anyFragmentRejected(textsOf(statements)))
            .as("StatementSplitter must produce only statements Oracle accepts")
            .isFalse();
    }

    // ----- script execution -----------------------------------------------------------

    /// Splits the script, asserts success and the expected statement count, then executes every
    /// statement in order against the container's test schema.
    ///
    /// @param script   the migration script
    /// @param expected the expected number of split statements
    private void runScript(String script, int expected) {
        var statements = splitStatements(script);

        assertThat(statements).hasSize(expected);
        executeStatements(textsOf(statements));
    }

    private static List<Statement> splitStatements(String script) {
        return StatementSplitter.split(script, ORACLE)
                                .onFailure(cause -> fail("split failed: " + cause.message()))
                                .or(List.of());
    }

    private static List<String> textsOf(List<Statement> statements) {
        return statements.stream().map(Statement::text).toList();
    }

    /// Naive `;`-split standing in for the OLD behavior: cuts blindly, dropping empties. Also strips
    /// lone `/` block-line terminators, which the OLD naive path would otherwise feed to Oracle as
    /// bare unrecognized commands — so the rejected fragment is the cut block body, not the
    /// (trivially invalid) `/`, making the contrast a real boundary-cut proof.
    private static List<String> naiveSplitOnSemicolon(String script) {
        return List.of(script.split(";")).stream()
                   .map(String::trim)
                   .filter(fragment -> !fragment.isEmpty())
                   .filter(fragment -> !fragment.equals("/"))
                   .toList();
    }

    // ----- per-statement execution against the live engine ----------------------------

    /// Runs every statement against the test schema in order (each as a single Oracle command), and
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

    /// Executes a single split statement as exactly ONE Oracle command through a [PreparedStatement].
    ///
    /// A PL/SQL block reaches the driver as `BEGIN … END;` with internal `;` intact and NO trailing
    /// `/` (the splitter consumed it), which is exactly the form the JDBC driver accepts. A merged
    /// two-command string fails with `ORA-00911: invalid character` at the embedded `;` — this is
    /// what makes under-splitting fail loudly.
    private void executeOne(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    /// Whether running every fragment as a single command produces at least one engine rejection.
    /// Each fragment runs against the test schema; the first rejection short-circuits.
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
        return catalogFlag("SELECT 1 FROM user_tables WHERE table_name = ?", table);
    }

    private boolean objectExists(String type, String name) {
        try (var connection = openConnection();
             var statement = connection.prepareStatement(
                 "SELECT 1 FROM user_objects WHERE object_type = ? AND object_name = ?")) {
            statement.setString(1, type);
            statement.setString(2, name);
            return hasRow(statement);
        } catch (SQLException e) {
            return fail("catalog probe failed: " + e.getMessage(), e);
        }
    }

    /// Runs a one-row existence probe parameterized by the object name (uppercased for Oracle's
    /// case-folding data dictionary).
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
            return rs.getString(1);
        }
    }

    // ----- per-case isolation ---------------------------------------------------------

    /// Opens a working connection against the container's test schema — the only schema the test
    /// user owns. Cases stay isolated through unique object names plus per-test teardown in
    /// [#dropCaseObjects].
    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
            ORACLE_DB.getJdbcUrl(), ORACLE_DB.getUsername(), ORACLE_DB.getPassword());
    }

    /// Drops every object any case may have created so a re-run starts clean, regardless of which
    /// test ran. Best-effort: the shared container is discarded after the class anyway.
    @AfterEach
    void dropCaseObjects() {
        if (!dockerAvailable() || !ORACLE_DB.isRunning()) {
            return;
        }
        try (var connection = openConnection();
             var statement = connection.createStatement()) {
            for (var procedure : CASE_PROCEDURES) {
                dropQuietly(statement, "DROP PROCEDURE " + procedure);
            }
            for (var table : CASE_TABLES) {
                dropQuietly(statement, "DROP TABLE " + table + " CASCADE CONSTRAINTS PURGE");
            }
            for (var type : CASE_TYPES) {
                dropQuietly(statement, "DROP TYPE " + type + " FORCE");
            }
        } catch (SQLException ignored) {
            // best-effort teardown; the shared container is discarded after the class
        }
    }

    /// Oracle has no `DROP … IF EXISTS`, so each drop is attempted and its `ORA-` "does not exist"
    /// failure swallowed — teardown is best-effort and order-independent.
    private static void dropQuietly(java.sql.Statement statement, String ddl) {
        try {
            statement.execute(ddl);
        } catch (SQLException ignored) {
            // object absent for this case — nothing to drop
        }
    }

    private static final List<String> CASE_PROCEDURES = List.of("PROC_INC", "CONTRAST_PROC");
    private static final List<String> CASE_TABLES = List.of(
        "PROC_T", "ANON_T", "ALTQ_T", "MULTI_A", "MULTI_B", "MULTI_C", "TYPE_T", "CONTRAST_T");
    private static final List<String> CASE_TYPES = List.of("ADDR_TYP");
}
