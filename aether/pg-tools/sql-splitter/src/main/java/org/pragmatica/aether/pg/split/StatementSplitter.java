// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;

/// Pure, dialect-aware SQL statement splitter.
///
/// [#split(String, DialectSpec)] scans the input character-by-character with a state
/// machine that tracks which lexical span the cursor is inside (normal text, string and
/// escape-string literals, dollar-quoted bodies with a captured tag, line and nested block
/// comments, quoted identifiers, and `COPY … FROM STDIN` inline data). A statement boundary
/// is recognized only on a top-level `;` seen in normal state; terminators inside any span
/// are opaque. The scan is a thread-confined sequential Leaf — its mutable cursor never
/// escapes the call — and reports malformed input (an unterminated span at end of input) as
/// a typed [SplitError] value rather than an exception.
///
/// Only the primitives PostgreSQL enables in its [DialectSpec] are honored here. The
/// boundary primitives (redefinable terminator, batch separator, block line terminator) are
/// carried by the descriptor but PostgreSQL leaves them off, so the engine does not yet act
/// on them; a later dialect that enables them slots in as a localized extension keyed off
/// those descriptor fields.
public sealed interface StatementSplitter {
    record unused() implements StatementSplitter {}

    char BOM = '﻿';

    /// Splits a SQL script into its constituent statements.
    ///
    /// Empty statements (whitespace- or comment-only) are skipped. Each emitted [Statement]
    /// carries verbatim text (so checksums stay stable) and the 1-based line on which it
    /// begins. A leading UTF-8 BOM is stripped. An unterminated span at end of input yields a
    /// failure carrying a [SplitError].
    ///
    /// @param sql     the SQL script
    /// @param dialect the dialect descriptor whose primitives govern the scan
    ///
    /// @return the ordered list of statements, or a [SplitError] for malformed input
    static Result<List<Statement>> split(String sql, DialectSpec dialect) {
        return new Scan(stripBom(sql), dialect).run();
    }

    /// Removes a leading UTF-8 BOM if present.
    ///
    /// @param sql the raw SQL script
    ///
    /// @return the script without a leading BOM
    private static String stripBom(String sql) {
        return !sql.isEmpty() && sql.charAt(0) == BOM
               ? sql.substring(1)
               : sql;
    }

    /// Thread-confined sequential scanner. All mutable cursor state lives here and never
    /// escapes the enclosing [#split(String, DialectSpec)] call.
    final class Scan {
        private final String sql;
        private final DialectSpec dialect;
        private final int length;
        private final List<Statement> statements = new ArrayList<>();

        private int pos;
        private int line = 1;
        private int statementStart;
        private int statementStartLine;

        private Scan(String sql, DialectSpec dialect) {
            this.sql = sql;
            this.dialect = dialect;
            this.length = sql.length();
            this.statementStart = 0;
            this.statementStartLine = 0; // 0 = pending: set on the first significant char
        }

        /// Drives the scan to completion, short-circuiting on the first malformed span.
        private Result<List<Statement>> run() {
            var outcome = unitResult();

            while (pos < length && outcome.isSuccess()) {
                outcome = scanNormalChar();
            }
            return outcome.flatMap(this::finish);
        }

        private Result<List<Statement>> finish(Unit ignored) {
            emitStatement(length);
            return success(List.copyOf(statements));
        }

        /// Dispatches a single character in normal state, possibly descending into a span.
        private Result<Unit> scanNormalChar() {
            var c = sql.charAt(pos);

            if (c == '\n') {
                line++;
                pos++;
                return unitResult();
            }
            if (Character.isWhitespace(c)) {
                pos++;
                return unitResult();
            }
            markStatementLine();

            if (c == ';') {
                return splitOrEnterCopyData();
            }
            return descend(c);
        }

        /// Records the current line as the statement's begin-line on its first significant
        /// (non-whitespace) character, so leading blank lines do not skew diagnostics.
        private void markStatementLine() {
            if (statementStartLine == 0) {
                statementStartLine = line;
            }
        }

        /// A top-level `;` either opens a `COPY … FROM STDIN` data block or ends a statement.
        private Result<Unit> splitOrEnterCopyData() {
            return startsCopyData()
                   ? consumeCopyData()
                   : splitHere();
        }

        private Result<Unit> splitHere() {
            emitStatement(pos);
            pos++;
            statementStart = pos;
            statementStartLine = 0; // pending: re-marked on the next statement's first char
            return unitResult();
        }

        private Result<Unit> descend(char c) {
            if (startsLineComment(c)) {
                consumeLineComment();
                return unitResult();
            }
            if (startsBlockComment(c)) {
                return consumeBlockComment();
            }
            if (startsDollarQuote(c)) {
                return consumeDollarQuote();
            }
            if (startsEscapeString(c)) {
                return consumeEscapeString();
            }
            if (c == '\'') {
                return consumeString();
            }
            if (startsQuotedIdentifier(c)) {
                return consumeQuotedIdentifier();
            }
            pos++;
            return unitResult();
        }

        // ----- span predicates -------------------------------------------------------

        private boolean startsLineComment(char c) {
            return (dialect.comments().dashLineComment() && c == '-' && peek(1) == '-')
                   || (dialect.comments().hashLineComment() && c == '#');
        }

        private boolean startsBlockComment(char c) {
            return dialect.comments().blockComment() && c == '/' && peek(1) == '*';
        }

        private boolean startsDollarQuote(char c) {
            return dialect.dollarQuote().enabled() && c == '$' && dollarTagAt(pos).isPresent();
        }

        private boolean startsEscapeString(char c) {
            return dialect.strings().escapeStringPrefix() && (c == 'E' || c == 'e') && peek(1) == '\'';
        }

        private boolean startsQuotedIdentifier(char c) {
            return dialect.identifiers().doubleQuote() && c == '"';
        }

        private boolean startsCopyData() {
            return dialect.copyData().enabled() && copyFromStdinEndsHere();
        }

        // ----- span consumers --------------------------------------------------------

        private void consumeLineComment() {
            while (pos < length && sql.charAt(pos) != '\n') {
                pos++;
            }
        }

        private Result<Unit> consumeBlockComment() {
            var openedLine = line;
            var depth = 0;

            while (pos < length) {
                if (atBlockOpen()) {
                    depth++;
                    pos += 2;
                } else if (atBlockClose()) {
                    depth--;
                    pos += 2;
                    if (depth == 0) {
                        return unitResult();
                    }
                } else {
                    advanceTrackingLine();
                }
            }
            return new SplitError.UnterminatedBlockComment(openedLine).result();
        }

        private Result<Unit> consumeDollarQuote() {
            var openedLine = line;
            var tag = dollarTagAt(pos).or("");

            pos += tag.length();

            while (pos < length) {
                if (sql.charAt(pos) == '$' && matchesTagAt(pos, tag)) {
                    pos += tag.length();
                    return unitResult();
                }
                advanceTrackingLine();
            }
            return new SplitError.UnterminatedDollarQuote(innerTag(tag), openedLine).result();
        }

        private Result<Unit> consumeEscapeString() {
            var openedLine = line;

            pos += 2; // consume E'

            while (pos < length) {
                var c = sql.charAt(pos);

                if (c == '\\' && pos + 1 < length) {
                    advanceEscapePair();
                } else if (c == '\'' && peek(1) == '\'') {
                    pos += 2;
                } else if (c == '\'') {
                    pos++;
                    return unitResult();
                } else {
                    advanceTrackingLine();
                }
            }
            return new SplitError.UnterminatedString(openedLine).result();
        }

        private Result<Unit> consumeString() {
            var openedLine = line;

            pos++; // consume opening '

            while (pos < length) {
                var c = sql.charAt(pos);

                if (dialect.strings().backslashEscapes() && c == '\\' && pos + 1 < length) {
                    advanceEscapePair();
                } else if (c == '\'' && peek(1) == '\'') {
                    pos += 2;
                } else if (c == '\'') {
                    pos++;
                    return unitResult();
                } else {
                    advanceTrackingLine();
                }
            }
            return new SplitError.UnterminatedString(openedLine).result();
        }

        private Result<Unit> consumeQuotedIdentifier() {
            var openedLine = line;

            pos++; // consume opening "

            while (pos < length) {
                var c = sql.charAt(pos);

                if (c == '"' && peek(1) == '"') {
                    pos += 2;
                } else if (c == '"') {
                    pos++;
                    return unitResult();
                } else {
                    advanceTrackingLine();
                }
            }
            return new SplitError.UnterminatedQuotedIdentifier(openedLine).result();
        }

        private Result<Unit> consumeCopyData() {
            var openedLine = line;
            var marker = dialect.copyData().endMarkerLine();

            advanceToNextLine(); // move past the COPY header line

            while (pos < length) {
                if (lineContentEquals(marker)) {
                    return endCopyData();
                }
                advanceToNextLine();
            }
            return new SplitError.UnterminatedCopyData(openedLine).result();
        }

        /// Consumes the marker line, emits the complete COPY statement (header + data), and
        /// resets the statement origin so a following statement is recognized separately.
        private Result<Unit> endCopyData() {
            consumeLineComment(); // consume the marker line content
            emitStatement(pos);
            if (pos < length) {
                line++;
                pos++; // consume the marker line newline
            }
            statementStart = pos;
            statementStartLine = 0; // pending: re-marked on the next statement's first char
            return unitResult();
        }

        // ----- low-level cursor helpers ----------------------------------------------

        private char peek(int ahead) {
            var index = pos + ahead;
            return index < length
                   ? sql.charAt(index)
                   : '\0';
        }

        private void advanceTrackingLine() {
            if (sql.charAt(pos) == '\n') {
                line++;
            }
            pos++;
        }

        private void advanceEscapePair() {
            if (sql.charAt(pos + 1) == '\n') {
                line++;
            }
            pos += 2;
        }

        private boolean atBlockOpen() {
            return dialect.comments().nestedBlock() && sql.charAt(pos) == '/' && peek(1) == '*';
        }

        private boolean atBlockClose() {
            return sql.charAt(pos) == '*' && peek(1) == '/';
        }

        private void advanceToNextLine() {
            while (pos < length && sql.charAt(pos) != '\n') {
                pos++;
            }
            if (pos < length) {
                line++;
                pos++;
            }
        }

        /// Whether the current line (from `pos` to the next newline or EOF) consists solely
        /// of the marker, ignoring a trailing carriage return.
        private boolean lineContentEquals(String marker) {
            var end = pos;
            while (end < length && sql.charAt(end) != '\n') {
                end++;
            }
            return stripCr(sql.substring(pos, end)).equals(marker);
        }

        private static String stripCr(String content) {
            return !content.isEmpty() && content.charAt(content.length() - 1) == '\r'
                   ? content.substring(0, content.length() - 1)
                   : content;
        }

        // ----- COPY detection --------------------------------------------------------

        /// Whether the statement accumulated so far is a `COPY … FROM STDIN` and the cursor
        /// sits on the `;` that opens its data block. Detected from the already-scanned
        /// statement prefix so the scan stays in normal state until the terminator.
        private boolean copyFromStdinEndsHere() {
            return sql.charAt(pos) == ';' && currentStatementIsCopyFromStdin();
        }

        private boolean currentStatementIsCopyFromStdin() {
            var upper = sql.substring(statementStart, pos).stripLeading().toUpperCase(Locale.ROOT);
            return upper.startsWith("COPY ") && upper.contains("FROM STDIN");
        }

        // ----- dollar-tag matching ---------------------------------------------------

        /// Returns the full opening dollar tag (including both `$` delimiters) starting at
        /// `index`, if one is present. Empty tag `$$` and arbitrary `$name$` are recognized.
        private Option<String> dollarTagAt(int index) {
            if (index >= length || sql.charAt(index) != '$') {
                return none();
            }
            var cursor = index + 1;

            while (cursor < length && isTagChar(sql.charAt(cursor), cursor == index + 1)) {
                cursor++;
            }
            return option(cursor < length && sql.charAt(cursor) == '$'
                          ? sql.substring(index, cursor + 1)
                          : null);
        }

        private boolean matchesTagAt(int index, String tag) {
            return index + tag.length() <= length && sql.regionMatches(index, tag, 0, tag.length());
        }

        private static boolean isTagChar(char c, boolean first) {
            return first
                   ? Character.isLetter(c) || c == '_'
                   : Character.isLetterOrDigit(c) || c == '_';
        }

        private static String innerTag(String fullTag) {
            return fullTag.length() >= 2
                   ? fullTag.substring(1, fullTag.length() - 1)
                   : fullTag;
        }

        // ----- statement emission ----------------------------------------------------

        private void emitStatement(int end) {
            var text = sql.substring(statementStart, end);

            if (!isBlank(text)) {
                statements.add(new Statement(text, statementStartLine));
            }
        }

        /// Whether the text is empty once whitespace and leading comments are removed — such
        /// statements are skipped rather than emitted.
        private boolean isBlank(String text) {
            return Dialects.stripLeadingNoise(text).isBlank();
        }
    }
}
