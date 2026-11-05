/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.postgres.r2dbc;

/// The `??` escape: a literal `?` in a statement, outside quoted text and comments. jOOQ keeps `??` in the SQL it hands to
/// the executing layer, and relies on that layer to turn it into `?` (pgjdbc does, on the JDBC route); the jsonb
/// operators `?`, `?|` and `?&` are written this way. The server never sees `??`.
///
/// Quoted text (`'...'`, `"..."`, `$tag$...$tag$`) and comments (`-- ...`, nested `/* ... */`) are copied as they are, so a
/// `??` inside a string literal stays two characters. A lone `?` is left alone.
final class QuestionMarkEscape {
    private QuestionMarkEscape() {}

    static String unescape(String sql) {
        if (!sql.contains("??")) {
            return sql;
        }

        var out = new StringBuilder(sql.length());
        var index = 0;

        while (index < sql.length()) {
            index = copyToken(sql, index, out);
        }

        return out.toString();
    }

    /// Appends the token starting at `index` (unescaped when it is the escape) and returns the index after it.
    private static int copyToken(String sql, int index, StringBuilder out) {
        if (sql.startsWith("??", index)) {
            out.append('?');

            return index + 2;
        }

        var end = tokenEnd(sql, index);

        out.append(sql, index, end);

        return end;
    }

    private static int tokenEnd(String sql, int index) {
        return switch (sql.charAt(index)) {
            case '\'' -> quotedEnd(sql, index, '\'', opensEscapeString(sql, index));
            case '"' -> quotedEnd(sql, index, '"', false);
            case '$' -> opensDollarQuote(sql, index)
                        ? dollarQuotedEnd(sql, index)
                        : index + 1;
            case '-' -> sql.startsWith("--", index)
                        ? lineCommentEnd(sql, index)
                        : index + 1;
            case '/' -> sql.startsWith("/*", index)
                        ? blockCommentEnd(sql, index)
                        : index + 1;
            default -> index + 1;
        };
    }

    /// A doubled quote inside the text is part of it; in an escape string (`E'...'`) a backslash also escapes the next
    /// character, a quote included. An unterminated one runs to the end.
    private static int quotedEnd(String sql, int start, char quote, boolean backslashEscapes) {
        var index = start + 1;

        while (index < sql.length()) {
            if (backslashEscapes && sql.charAt(index) == '\\') {
                index += 2;
            } else if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                } else {
                    return index + 1;
                }
            } else {
                index++;
            }
        }

        return sql.length();
    }

    /// `E'...'` (the `E` not the tail of a longer word) is an escape string: a backslash escapes the next character.
    private static boolean opensEscapeString(String sql, int quote) {
        return quote > 0
               && (sql.charAt(quote - 1) == 'E' || sql.charAt(quote - 1) == 'e')
               && (quote == 1 || !isIdentifierChar(sql.charAt(quote - 2)));
    }

    /// A dollar-quote tag cannot follow an identifier character: in `a$b$c` the `$` signs belong to the identifier.
    private static boolean opensDollarQuote(String sql, int dollar) {
        return dollar == 0 || !isIdentifierChar(sql.charAt(dollar - 1));
    }

    private static boolean isIdentifierChar(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private static int lineCommentEnd(String sql, int start) {
        var newline = sql.indexOf('\n', start);

        return newline < 0
               ? sql.length()
               : newline + 1;
    }

    /// PostgreSQL block comments nest.
    private static int blockCommentEnd(String sql, int start) {
        var depth = 1;
        var index = start + 2;

        while (index < sql.length() && depth > 0) {
            if (sql.startsWith("/*", index)) {
                depth++;
                index += 2;
            } else if (sql.startsWith("*/", index)) {
                depth--;
                index += 2;
            } else {
                index++;
            }
        }

        return index;
    }

    /// `$tag$ ... $tag$` (the tag may be empty). `$1` is a parameter, not a quote, and so is a `$` that opens no tag.
    private static int dollarQuotedEnd(String sql, int start) {
        var tagEnd = tagEnd(sql, start);

        if (tagEnd < 0) {
            return start + 1;
        }

        var close = sql.indexOf(sql.substring(start, tagEnd + 1), tagEnd + 1);

        return close < 0
               ? sql.length()
               : close + (tagEnd + 1 - start);
    }

    /// The index of the `$` closing the opening tag, or -1 when `start` opens no tag.
    private static int tagEnd(String sql, int start) {
        var index = start + 1;

        while (index < sql.length() && isTagChar(sql.charAt(index), index == start + 1)) {
            index++;
        }

        return index < sql.length() && sql.charAt(index) == '$'
               ? index
               : -1;
    }

    private static boolean isTagChar(char c, boolean first) {
        return c == '_' || Character.isLetter(c) || (!first && Character.isDigit(c));
    }
}
