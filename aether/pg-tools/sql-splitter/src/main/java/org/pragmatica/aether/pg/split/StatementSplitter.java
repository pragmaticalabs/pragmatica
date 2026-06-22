// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.pragmatica.aether.pg.split.DialectSpec.TerminatorRedefinition;
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
/// machine that tracks which lexical span the cursor is inside (normal text; string,
/// escape-string, national-string (`N'…'`), and double-quote-string literals; dollar-quoted
/// bodies with a captured tag; line and block comments (nesting or flat per dialect);
/// double-quote, backtick, and bracket (`[…]`) quoted identifiers; and `COPY … FROM STDIN`
/// inline data). A statement boundary is recognized either on a top-level active terminator
/// seen in normal state or, for batch dialects, on a line-anchored batch separator (e.g.
/// T-SQL `GO`); terminators inside any span are opaque. The scan is a thread-confined
/// sequential Leaf — its mutable cursor never escapes the call — and reports malformed input
/// (an unterminated span at end of input) as a typed [SplitError] value rather than an
/// exception.
///
/// Every lexical primitive is driven by the [DialectSpec] descriptor, so each dialect
/// enables exactly the spans it needs. PostgreSQL keeps the historical behavior: a
/// single-char `;` terminator, nesting block comments, double-quote identifiers, and `COPY`
/// inline data. A dialect that carries a redefinable terminator (e.g. MySQL's `DELIMITER`,
/// DB2's `--#SET TERMINATOR`) maintains an active terminator string — initialized to the
/// descriptor default, reset by a line-anchored directive that is consumed without emitting —
/// and splits on that (possibly multi-character) string instead. A batch dialect (SQL Server)
/// instead sets `semicolonTerminates=false` so a top-level `;` never ends a statement, and
/// bounds statements only on the batch separator — each `GO`-batch (procedure body internal
/// `;` and all) is emitted verbatim.
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
        private String activeTerminator;

        private Scan(String sql, DialectSpec dialect) {
            this.sql = sql;
            this.dialect = dialect;
            this.length = sql.length();
            this.statementStart = 0;
            this.statementStartLine = 0; // 0 = pending: set on the first significant char
            this.activeTerminator = initialTerminator(dialect);
        }

        /// The terminator in force at the start of the scan: the descriptor's redefinable
        /// default when configured, otherwise the single-char `;` the engine has always used.
        private static String initialTerminator(DialectSpec dialect) {
            return dialect.boundaries()
                          .redefinableTerminator()
                          .map(TerminatorRedefinition::defaultValue)
                          .or(";");
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
            if (consumesTerminatorDirective()) {
                return unitResult();
            }
            if (consumesBatchSeparator()) {
                return unitResult();
            }
            markStatementLine();

            if (atActiveTerminator()) {
                return splitOrEnterCopyData();
            }
            return descend(c);
        }

        /// Whether the cursor sits on the active terminator. PostgreSQL keeps the historical
        /// single-char `;` test; a redefinable-terminator dialect matches the full (possibly
        /// multi-char) terminator string. Batch dialects (`semicolonTerminates=false`, e.g. SQL
        /// Server) never terminate on the `;` here — only the batch separator bounds statements.
        private boolean atActiveTerminator() {
            if (!dialect.boundaries().semicolonTerminates()) {
                return false;
            }
            return activeTerminator.length() == 1
                   ? sql.charAt(pos) == activeTerminator.charAt(0)
                   : sql.regionMatches(pos, activeTerminator, 0, activeTerminator.length());
        }

        /// Records the current line as the statement's begin-line on its first significant
        /// (non-whitespace) character, so leading blank lines do not skew diagnostics.
        private void markStatementLine() {
            if (statementStartLine == 0) {
                statementStartLine = line;
            }
        }

        // ----- redefinable terminator directive --------------------------------------

        /// Recognizes a client terminator-redefinition directive (e.g. `DELIMITER $$`) at a
        /// statement start. The directive is line-anchored: the cursor must sit on the first
        /// significant character of a line and no statement text may have accumulated since
        /// the last terminator. When matched the WHOLE directive line is consumed without
        /// emitting (it is a client directive, not server SQL) and the active terminator is
        /// reset to the named token. Dialects without a redefinable terminator (PostgreSQL)
        /// never enter this path.
        ///
        /// @return `true` when a directive line was consumed, `false` otherwise
        private boolean consumesTerminatorDirective() {
            return dialect.boundaries()
                          .redefinableTerminator()
                          .map(this::consumeTerminatorDirective)
                          .or(false);
        }

        private boolean consumeTerminatorDirective(TerminatorRedefinition rule) {
            return atStatementStartOfLine() && tryConsumeDirectiveLine(rule.keyword());
        }

        /// Whether the cursor sits at the very start of a statement (no text accumulated yet)
        /// and at the beginning of a line (only whitespace precedes it on this line).
        private boolean atStatementStartOfLine() {
            return statementStartLine == 0 && onlyWhitespaceSinceLineStart();
        }

        private boolean onlyWhitespaceSinceLineStart() {
            var cursor = pos - 1;

            while (cursor >= statementStart && sql.charAt(cursor) != '\n') {
                if (!Character.isWhitespace(sql.charAt(cursor))) {
                    return false;
                }
                cursor--;
            }
            return true;
        }

        /// Matches `KEYWORD <whitespace> <token> <optional trailing whitespace>` to end of line
        /// (case-insensitive keyword). On a match, consumes the directive line including its
        /// terminating newline, sets the active terminator to the token, and returns `true`.
        private boolean tryConsumeDirectiveLine(String keyword) {
            if (!keywordMatchesAt(keyword)) {
                return false;
            }
            var afterKeyword = pos + keyword.length();
            var tokenStart = skipInlineWhitespace(afterKeyword);

            if (tokenStart == afterKeyword || tokenStart >= length || isLineEnd(sql.charAt(tokenStart))) {
                return false;
            }
            var tokenEnd = lineEndFrom(tokenStart);

            applyDirective(sql.substring(tokenStart, tokenEnd).stripTrailing(), tokenEnd);
            return true;
        }

        private boolean keywordMatchesAt(String keyword) {
            return pos + keyword.length() <= length
                   && sql.regionMatches(true, pos, keyword, 0, keyword.length())
                   && isWhitespaceAt(pos + keyword.length());
        }

        /// Adopts the new terminator and advances past the directive line (consuming its
        /// trailing newline so the next statement starts cleanly), without emitting.
        private void applyDirective(String token, int tokenEnd) {
            activeTerminator = token;
            pos = tokenEnd;
            if (pos < length) {
                line++;
                pos++; // consume the directive line's newline
            }
            statementStart = pos;
            statementStartLine = 0; // pending: re-marked on the next statement's first char
        }

        private int skipInlineWhitespace(int from) {
            var cursor = from;

            while (cursor < length && isWhitespaceAt(cursor) && !isLineEnd(sql.charAt(cursor))) {
                cursor++;
            }
            return cursor;
        }

        private int lineEndFrom(int from) {
            var cursor = from;

            while (cursor < length && sql.charAt(cursor) != '\n') {
                cursor++;
            }
            return cursor;
        }

        private boolean isWhitespaceAt(int index) {
            return index < length && Character.isWhitespace(sql.charAt(index));
        }

        private static boolean isLineEnd(char c) {
            return c == '\n' || c == '\r';
        }

        // ----- batch separator (T-SQL GO) --------------------------------------------

        /// Recognizes a batch separator (e.g. T-SQL `GO`, optionally with a trailing repeat
        /// count `GO 3`) at the start of a line. The keyword must be the sole content of the
        /// line (case-insensitive, only whitespace before it on the line). When matched the
        /// accumulated statement is emitted and the WHOLE separator line is consumed WITHOUT
        /// emitting the keyword. Dialects without a batch separator (PostgreSQL, MySQL, DB2)
        /// never enter this path.
        ///
        /// @return `true` when a batch-separator line was consumed, `false` otherwise
        private boolean consumesBatchSeparator() {
            return dialect.boundaries()
                          .batchSeparator()
                          .map(this::consumeBatchSeparator)
                          .or(false);
        }

        private boolean consumeBatchSeparator(String keyword) {
            return onlyWhitespaceSinceLineStart() && tryConsumeBatchLine(keyword);
        }

        /// Matches `KEYWORD [<whitespace> <integer count>] <optional trailing whitespace>` to
        /// end of line (case-insensitive keyword). On a match, emits the accumulated statement,
        /// consumes the separator line including its terminating newline, resets the statement
        /// origin, and returns `true`.
        private boolean tryConsumeBatchLine(String keyword) {
            if (!keywordAloneOnLine(keyword)) {
                return false;
            }
            emitStatement(pos);
            advancePastBatchLine();
            return true;
        }

        /// Whether the line at the cursor consists solely of the batch keyword, optionally
        /// followed by whitespace and a single integer repeat count.
        private boolean keywordAloneOnLine(String keyword) {
            if (!keywordMatchesEndOrSpaceAt(keyword)) {
                return false;
            }
            var rest = sql.substring(pos + keyword.length(), lineEndFrom(pos)).strip();
            return rest.isEmpty() || isPositiveInteger(rest);
        }

        private boolean keywordMatchesEndOrSpaceAt(String keyword) {
            var after = pos + keyword.length();
            return after <= length
                   && sql.regionMatches(true, pos, keyword, 0, keyword.length())
                   && (after == length || isLineEnd(sql.charAt(after)) || Character.isWhitespace(sql.charAt(after)));
        }

        private static boolean isPositiveInteger(String text) {
            return text.chars().allMatch(Character::isDigit);
        }

        /// Consumes the batch-separator line including its terminating newline and resets the
        /// statement origin so the next batch is recognized separately.
        private void advancePastBatchLine() {
            pos = lineEndFrom(pos);
            if (pos < length) {
                line++;
                pos++; // consume the separator line's newline
            }
            statementStart = pos;
            statementStartLine = 0; // pending: re-marked on the next statement's first char
        }

        /// A top-level `;` either opens a `COPY … FROM STDIN` data block or ends a statement.
        private Result<Unit> splitOrEnterCopyData() {
            return startsCopyData()
                   ? consumeCopyData()
                   : splitHere();
        }

        private Result<Unit> splitHere() {
            emitStatement(pos);
            pos += activeTerminator.length();
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
            if (startsNationalString(c)) {
                return consumeNationalString();
            }
            if (c == '\'') {
                return consumeString();
            }
            if (startsDoubleQuoteString(c)) {
                return consumeDoubleQuoteString();
            }
            if (startsBacktickIdentifier(c)) {
                return consumeBacktickIdentifier();
            }
            if (startsBracketIdentifier(c)) {
                return consumeBracketIdentifier();
            }
            if (startsQuotedIdentifier(c)) {
                return consumeQuotedIdentifier();
            }
            pos++;
            return unitResult();
        }

        // ----- span predicates -------------------------------------------------------

        private boolean startsLineComment(char c) {
            return (dialect.comments().dashLineComment() && c == '-' && peek(1) == '-' && dashCommentSpaceOk())
                   || (dialect.comments().hashLineComment() && c == '#');
        }

        /// Whether the `--` here may open a line comment under the dialect's dash rule. When
        /// `dashRequiresSpace` (MySQL) the `--` must be followed by whitespace or end of input,
        /// so `--x` is not a comment; otherwise (PostgreSQL) any `--` opens a comment.
        private boolean dashCommentSpaceOk() {
            if (!dialect.comments().dashRequiresSpace()) {
                return true;
            }
            var after = pos + 2;
            return after >= length || Character.isWhitespace(sql.charAt(after));
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

        private boolean startsNationalString(char c) {
            return dialect.strings().nationalPrefix() && (c == 'N' || c == 'n') && peek(1) == '\'';
        }

        private boolean startsQuotedIdentifier(char c) {
            return dialect.identifiers().doubleQuote() && c == '"';
        }

        private boolean startsDoubleQuoteString(char c) {
            return dialect.strings().doubleQuoteString() && c == '"';
        }

        private boolean startsBacktickIdentifier(char c) {
            return dialect.identifiers().backtick() && c == '`';
        }

        private boolean startsBracketIdentifier(char c) {
            return dialect.identifiers().bracket() && c == '[';
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
            return dialect.comments().nestedBlock()
                   ? consumeNestedBlockComment()
                   : consumeFlatBlockComment();
        }

        /// PostgreSQL-style depth-counted block comment: nested `/*` raise the depth, each `*/`
        /// lowers it, and the comment ends when depth returns to zero.
        private Result<Unit> consumeNestedBlockComment() {
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

        /// Flat (non-nesting) block comment: the opening `/*` is consumed and the comment ends
        /// at the FIRST `*/`, with intervening `/*` treated as ordinary content (MySQL).
        private Result<Unit> consumeFlatBlockComment() {
            var openedLine = line;

            pos += 2; // consume the opening /*

            while (pos < length) {
                if (atBlockClose()) {
                    pos += 2;
                    return unitResult();
                }
                advanceTrackingLine();
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

        /// Consumes an `N'…'` national-character string literal (SQL Server). The `N` prefix is
        /// consumed and the body follows the same `''`-doubling rules as an ordinary `'…'`
        /// literal (SQL Server does not honor backslash escapes here).
        private Result<Unit> consumeNationalString() {
            pos++; // consume the N prefix; the cursor now sits on the opening '
            return consumeString();
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

        /// Consumes a `"…"` STRING literal (MySQL without `ANSI_QUOTES`), honoring `""`
        /// doubling and, when the dialect enables it, backslash escapes.
        private Result<Unit> consumeDoubleQuoteString() {
            var openedLine = line;

            pos++; // consume opening "

            while (pos < length) {
                var c = sql.charAt(pos);

                if (dialect.strings().backslashEscapes() && c == '\\' && pos + 1 < length) {
                    advanceEscapePair();
                } else if (c == '"' && peek(1) == '"') {
                    pos += 2;
                } else if (c == '"') {
                    pos++;
                    return unitResult();
                } else {
                    advanceTrackingLine();
                }
            }
            return new SplitError.UnterminatedString(openedLine).result();
        }

        /// Consumes a `` `…` `` quoted identifier (MySQL), with doubled `` `` `` an embedded
        /// backtick.
        private Result<Unit> consumeBacktickIdentifier() {
            var openedLine = line;

            pos++; // consume opening `

            while (pos < length) {
                var c = sql.charAt(pos);

                if (c == '`' && peek(1) == '`') {
                    pos += 2;
                } else if (c == '`') {
                    pos++;
                    return unitResult();
                } else {
                    advanceTrackingLine();
                }
            }
            return new SplitError.UnterminatedQuotedIdentifier(openedLine).result();
        }

        /// Consumes a `[…]` bracket-quoted identifier (SQL Server), with doubled `]]` an embedded
        /// closing bracket.
        private Result<Unit> consumeBracketIdentifier() {
            var openedLine = line;

            pos++; // consume opening [

            while (pos < length) {
                var c = sql.charAt(pos);

                if (c == ']' && peek(1) == ']') {
                    pos += 2;
                } else if (c == ']') {
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
