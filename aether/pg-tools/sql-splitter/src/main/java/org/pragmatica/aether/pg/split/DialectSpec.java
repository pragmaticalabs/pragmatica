// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;

/// Data-only descriptor of an SQL dialect's lexical and boundary primitives.
///
/// The descriptor carries the FULL cross-dialect primitive set as data so that adding a
/// later dialect is mostly a matter of populating fields rather than changing the engine.
/// [StatementSplitter] honors each primitive a dialect enables: PostgreSQL leaves the
/// boundary primitives off and uses a fixed `;` terminator, while MySQL enables the
/// redefinable terminator (`DELIMITER`). The batch separator and block line terminator are
/// carried as data but no shipped dialect enables them yet; when one does, the engine gains
/// a localized extension keyed off these same fields — not a redesign.
///
/// @param strings     string-literal lexical rules
/// @param identifiers identifier-quoting rules
/// @param comments    comment lexical rules
/// @param dollarQuote dollar-quoting rules
/// @param boundaries  statement-boundary primitives (terminator/batch/block)
/// @param copyData    `COPY … FROM STDIN` inline-data handling
/// @param classifier  dialect-specific transactional classifier for a single statement
public record DialectSpec(StringRules strings,
                          IdentifierRules identifiers,
                          CommentRules comments,
                          DollarQuoteRules dollarQuote,
                          BoundaryRules boundaries,
                          CopyDataRules copyData,
                          Fn1<Boolean, String> classifier) {

    /// Whether the given statement text runs inside a transaction in this dialect.
    ///
    /// Non-transactional statements (e.g. PostgreSQL `CREATE INDEX … CONCURRENTLY`,
    /// `VACUUM`) cannot be wrapped in a `BEGIN`/`COMMIT` block by a migration runner.
    /// The classification is dialect-specific and case-insensitive, tolerant of leading
    /// comments and whitespace.
    ///
    /// @param statementText verbatim statement text
    ///
    /// @return `true` if the statement is transactional, `false` otherwise
    public boolean isTransactional(String statementText) {
        return classifier.apply(statementText);
    }

    /// String-literal lexical rules.
    ///
    /// @param doubledQuoteEscape  `''` inside `'…'` represents a literal quote (SQL standard)
    /// @param backslashEscapes    backslash escapes are honored inside ordinary `'…'`
    /// @param escapeStringPrefix  `E'…'` escape-string syntax (always backslash-aware)
    /// @param nationalPrefix      `N'…'` national-character-string prefix
    /// @param altQuoting          `q'X…X'` Oracle-style alternative quoting
    /// @param doubleQuoteString   `"…"` is a STRING literal, not a quoted identifier
    ///                            (MySQL without `ANSI_QUOTES`); doubling `""` embeds a quote
    public record StringRules(boolean doubledQuoteEscape,
                              boolean backslashEscapes,
                              boolean escapeStringPrefix,
                              boolean nationalPrefix,
                              boolean altQuoting,
                              boolean doubleQuoteString) {}

    /// Identifier-quoting rules — which delimiter pairs introduce a quoted identifier.
    ///
    /// @param doubleQuote `"…"` (SQL standard)
    /// @param backtick    `` `…` `` (MySQL)
    /// @param bracket     `[…]` (SQL Server)
    public record IdentifierRules(boolean doubleQuote, boolean backtick, boolean bracket) {}

    /// Comment lexical rules.
    ///
    /// @param dashLineComment   `--` line comment
    /// @param hashLineComment   `#` line comment (MySQL)
    /// @param blockComment      `/*…*/` block comment
    /// @param nestedBlock       block comments nest (PostgreSQL); when `false` the first `*/`
    ///                          closes the comment regardless of intervening `/*`
    /// @param dashRequiresSpace `--` opens a line comment only when immediately followed by
    ///                          whitespace or end-of-line (MySQL); `--x` is not a comment
    public record CommentRules(boolean dashLineComment,
                               boolean hashLineComment,
                               boolean blockComment,
                               boolean nestedBlock,
                               boolean dashRequiresSpace) {}

    /// Dollar-quoting rules.
    ///
    /// @param enabled untagged `$$…$$` and tagged `$tag$…$tag$` dollar quoting is honored
    public record DollarQuoteRules(boolean enabled) {}

    /// Statement-boundary primitives carried as data. PostgreSQL disables all three.
    ///
    /// @param redefinableTerminator trigger that redefines the statement terminator
    ///                              (e.g. `DELIMITER`, `--#SET TERMINATOR`); empty when none
    /// @param batchSeparator        standalone batch separator keyword (e.g. `GO`); empty when none
    /// @param blockLineTerminator   single-character line that terminates a block (e.g. `/`);
    ///                              empty when none
    public record BoundaryRules(Option<TerminatorRedefinition> redefinableTerminator,
                                Option<String> batchSeparator,
                                Option<String> blockLineTerminator) {}

    /// How a dialect lets a script redefine its statement terminator mid-stream.
    ///
    /// @param keyword       the directive keyword (e.g. `DELIMITER`, `--#SET TERMINATOR`)
    /// @param defaultValue  the terminator in force before any redefinition (e.g. `;`)
    public record TerminatorRedefinition(String keyword, String defaultValue) {}

    /// `COPY … FROM STDIN` inline-data handling.
    ///
    /// @param enabled        a `COPY … FROM STDIN;` switches to opaque inline-data mode
    /// @param endMarkerLine  the line whose sole content ends the data block (e.g. `\\.`)
    public record CopyDataRules(boolean enabled, String endMarkerLine) {}
}
