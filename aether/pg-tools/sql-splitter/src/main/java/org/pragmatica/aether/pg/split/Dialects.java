// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.pragmatica.aether.pg.split.DialectSpec.CommentRules;
import org.pragmatica.aether.pg.split.DialectSpec.CopyDataRules;
import org.pragmatica.aether.pg.split.DialectSpec.DollarQuoteRules;
import org.pragmatica.aether.pg.split.DialectSpec.IdentifierRules;
import org.pragmatica.aether.pg.split.DialectSpec.StringRules;
import org.pragmatica.aether.pg.split.DialectSpec.BoundaryRules;
import org.pragmatica.aether.pg.split.DialectSpec.TerminatorRedefinition;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;

/// Catalog of populated [DialectSpec] descriptors. Ships PostgreSQL, MySQL/MariaDB, DB2,
/// SQL Server (T-SQL), and Oracle.
public sealed interface Dialects {
    record unused() implements Dialects {}

    /// Pattern matching the leading non-transactional command of a PostgreSQL statement,
    /// after leading whitespace and comments have been stripped. Case-insensitive.
    Pattern NON_TRANSACTIONAL = Pattern.compile(
        "^(?:"
        + "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+CONCURRENTLY\\b"   // CREATE INDEX CONCURRENTLY
        + "|CREATE\\s+(?:UNIQUE\\s+)?INDEX\\b.*\\bCONCURRENTLY\\b"  // CREATE … INDEX … CONCURRENTLY
        + "|DROP\\s+INDEX\\s+CONCURRENTLY\\b"
        + "|REINDEX\\b.*\\bCONCURRENTLY\\b"
        + "|VACUUM\\b"
        + "|ALTER\\s+TYPE\\b.*\\bADD\\s+VALUE\\b"
        + "|CREATE\\s+DATABASE\\b"
        + "|DROP\\s+DATABASE\\b"
        + ").*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /// Pattern recognizing the leading keywords of an Oracle PL/SQL block, after leading
    /// whitespace and comments have been stripped. A match means the statement's internal `;`
    /// are opaque and only a `/`-line terminates it. Recognizes anonymous blocks (`DECLARE`,
    /// `BEGIN`) and the stored-program DDL `CREATE [OR REPLACE] [EDITIONABLE|NONEDITIONABLE]
    /// (PROCEDURE|FUNCTION|TRIGGER|PACKAGE [BODY]|TYPE [BODY])`. A plain `CREATE TABLE`,
    /// `INSERT`, `ALTER`, etc. does NOT match. Case-insensitive.
    Pattern PLSQL_BLOCK_START = Pattern.compile(
        "^(?:"
        + "DECLARE\\b"
        + "|BEGIN\\b"
        + "|CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?"
        + "(?:PROCEDURE|FUNCTION|TRIGGER|PACKAGE(?:\\s+BODY)?|TYPE(?:\\s+BODY)?)\\b"
        + ").*",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /// Strips leading whitespace, `--` line comments, and `/*…*/` block comments so the
    /// transactional classifier sees the first real command keyword.
    Pattern LEADING_NOISE = Pattern.compile(
        "^(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)+",
        Pattern.DOTALL);

    /// The fully-populated PostgreSQL dialect descriptor.
    DialectSpec POSTGRESQL = new DialectSpec(
        new StringRules(true, false, true, false, false, false),
        new IdentifierRules(true, false, false),
        new CommentRules(true, false, true, true, false),
        new DollarQuoteRules(true),
        new BoundaryRules(none(), none(), none(), true),
        new CopyDataRules(true, "\\."),
        Dialects::isPostgresTransactional,
        Dialects::neverStartsBlock);

    /// The fully-populated MySQL/MariaDB dialect descriptor (one spec; MariaDB reuses it).
    ///
    /// Strings use SQL-standard `''` doubling, honor backslash escapes, and additionally treat
    /// `"…"` as a string literal (MySQL's default, without `ANSI_QUOTES`). Identifiers are
    /// quoted with backticks. Comments add `#` line comments and require a space after `--`;
    /// block comments do NOT nest. Statements are terminated by a redefinable terminator
    /// introduced with the `DELIMITER` client directive, defaulting to `;`. There is no
    /// dollar quoting, no batch separator, and no `COPY` inline-data mode.
    DialectSpec MYSQL = new DialectSpec(
        new StringRules(true, true, false, false, false, true),
        new IdentifierRules(false, true, false),
        new CommentRules(true, true, true, false, true),
        new DollarQuoteRules(false),
        new BoundaryRules(option(new TerminatorRedefinition("DELIMITER", ";")), none(), none(), true),
        new CopyDataRules(false, ""),
        Dialects::isMysqlTransactional,
        Dialects::neverStartsBlock);

    /// The fully-populated DB2 dialect descriptor.
    ///
    /// Strings use SQL-standard `''` doubling with NO backslash escapes. Identifiers are
    /// double-quoted (`"…"`). Comments are `--` line comments and `/*…*/` block comments that
    /// do NOT nest. There is no dollar quoting. Statements are terminated by a redefinable
    /// terminator introduced with the `--#SET TERMINATOR` client directive (defaulting to `;`),
    /// which — because the directive check runs before line-comment handling — is consumed as a
    /// directive rather than a `--` comment. There is no batch separator and no `COPY` mode, and
    /// `;` terminates as usual (`semicolonTerminates=true`). DB2 DDL is transactional.
    DialectSpec DB2 = new DialectSpec(
        new StringRules(true, false, false, false, false, false),
        new IdentifierRules(true, false, false),
        new CommentRules(true, false, true, false, false),
        new DollarQuoteRules(false),
        new BoundaryRules(option(new TerminatorRedefinition("--#SET TERMINATOR", ";")), none(), none(), true),
        new CopyDataRules(false, ""),
        Dialects::isDb2Transactional,
        Dialects::neverStartsBlock);

    /// The fully-populated SQL Server (T-SQL) dialect descriptor.
    ///
    /// Strings use SQL-standard `''` doubling, no backslash escapes, and additionally honor the
    /// `N'…'` national-character-string prefix. Identifiers are quoted with double quotes (`"…"`)
    /// AND brackets (`[…]`, where `]]` embeds a literal `]`). Comments are `--` line comments and
    /// `/*…*/` block comments that DO nest (T-SQL nests). There is no dollar quoting. Statements
    /// are bounded only by the `GO` batch separator — `semicolonTerminates=false` — so an internal
    /// `;` inside a `CREATE PROCEDURE … BEGIN …; …; END` body is preserved verbatim and each
    /// `GO`-batch is emitted as one statement. SQL Server DDL is transactional.
    DialectSpec SQLSERVER = new DialectSpec(
        new StringRules(true, false, false, true, false, false),
        new IdentifierRules(true, false, true),
        new CommentRules(true, false, true, true, false),
        new DollarQuoteRules(false),
        new BoundaryRules(none(), option("GO"), none(), false),
        new CopyDataRules(false, ""),
        Dialects::isSqlServerTransactional,
        Dialects::neverStartsBlock);

    /// The fully-populated Oracle dialect descriptor.
    ///
    /// Strings use SQL-standard `''` doubling with NO backslash escapes, and additionally honor
    /// Oracle alternative quoting `q'X…X'` / `Q'X…X'` (no `E'…'`, no `N'…'`, no `"…"` string).
    /// Identifiers are double-quoted (`"…"`) only — no backtick, no bracket. Comments are `--`
    /// line comments and `/*…*/` block comments that do NOT nest (Oracle does not nest). There is
    /// no dollar quoting. Statements are bounded by the `/` block-line terminator AND a top-level
    /// `;` (`semicolonTerminates=true`): a PL/SQL block (recognized by [#PLSQL_BLOCK_START]) keeps
    /// its internal `;` and ends only on a `/`-line, while a plain `CREATE TABLE`/`INSERT`/`ALTER`
    /// terminates on `;` as usual. There is no redefinable terminator, no batch separator, and no
    /// `COPY` inline-data mode. Oracle DDL auto-commits, so the executor runs the whole file in
    /// AUTOCOMMIT; the per-statement transactional classifier is therefore unused (always `true`).
    DialectSpec ORACLE = new DialectSpec(
        new StringRules(true, false, false, false, true, false),
        new IdentifierRules(true, false, false),
        new CommentRules(true, false, true, false, false),
        new DollarQuoteRules(false),
        new BoundaryRules(none(), none(), option("/"), true),
        new CopyDataRules(false, ""),
        Dialects::isOracleTransactional,
        Dialects::isOraclePlsqlBlock);

    /// Classifies a PostgreSQL statement as transactional or not.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return `false` for the non-transactional command set, `true` otherwise
    static boolean isPostgresTransactional(String statementText) {
        return !NON_TRANSACTIONAL.matcher(stripLeadingNoise(statementText)).matches();
    }

    /// Classifies a MySQL/MariaDB statement as transactional.
    ///
    /// MySQL is DDL-autocommit: per-statement classification is unused by the 4b executor,
    /// so every statement is reported transactional.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return always `true`
    static boolean isMysqlTransactional(String statementText) {
        return true;
    }

    /// Classifies a DB2 statement as transactional.
    ///
    /// DB2 DDL is transactional, so per-statement classification reports every statement
    /// transactional; the executor then wraps the whole file in a transaction.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return always `true`
    static boolean isDb2Transactional(String statementText) {
        return true;
    }

    /// Classifies a SQL Server (T-SQL) statement as transactional.
    ///
    /// SQL Server DDL is transactional, so per-statement classification reports every statement
    /// transactional; the executor then wraps the whole file in a transaction.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return always `true`
    static boolean isSqlServerTransactional(String statementText) {
        return true;
    }

    /// Classifies an Oracle statement as transactional.
    ///
    /// Oracle DDL auto-commits, so per-statement classification is unused by the executor (which
    /// runs the whole file in autocommit); every statement is reported transactional.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return always `true`
    static boolean isOracleTransactional(String statementText) {
        return true;
    }

    /// Whether the leading keywords of an Oracle statement prefix begin a PL/SQL block, so its
    /// internal `;` must be kept and only a `/`-line terminates it.
    ///
    /// @param statementPrefix the statement text accumulated so far
    ///
    /// @return `true` for an anonymous block or stored-program DDL, `false` for plain SQL
    static boolean isOraclePlsqlBlock(String statementPrefix) {
        return PLSQL_BLOCK_START.matcher(stripLeadingNoise(statementPrefix)).matches();
    }

    /// The block starter for dialects without a PL/SQL block mode — they never enter block mode.
    ///
    /// @param statementPrefix the statement text accumulated so far (ignored)
    ///
    /// @return always `false`
    static boolean neverStartsBlock(String statementPrefix) {
        return false;
    }

    /// Removes leading whitespace and comments before the first command keyword.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return text starting at the first significant command keyword
    static String stripLeadingNoise(String statementText) {
        return LEADING_NOISE.matcher(statementText).replaceFirst("");
    }
}
