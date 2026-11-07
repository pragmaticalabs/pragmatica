// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.upload;

import java.util.Locale;

import org.pragmatica.lang.Option;


/// Reads a header value of the form `type; name=value; name="quoted value"` (Content-Type, Content-Disposition): its
/// leading token, and one parameter by name. A quoted value may contain `;` and backslash-escaped characters (RFC 9110
/// §5.6.4). The extended `name*=` form (RFC 8187) is not read: a client that sends it sends the plain form too.
sealed interface HeaderParameters {
    /// The value's leading token, lower-cased and stripped: `multipart/form-data`, `form-data`.
    static String mediaType(String value) {
        var semicolon = value.indexOf(';');

        return (semicolon < 0
                ? value
                : value.substring(0, semicolon)).strip()
                                                .toLowerCase(Locale.ROOT);
    }

    /// The parameter `name` (matched case-insensitively), unquoted, or empty when the value does not carry it.
    static Option<String> parameter(String value, String name) {
        var position = value.indexOf(';');

        while (position >= 0 && position < value.length()) {
            var parsed = Parameter.at(value, position + 1);

            if (parsed.name().equalsIgnoreCase(name)) {
                return Option.some(parsed.value());
            }

            position = parsed.end();
        }

        return Option.none();
    }

    /// One `name=value` parameter read from `start`, and the index of the `;` after it (or the value's length).
    record Parameter(String name, String value, int end) {
        static Parameter at(String text, int start) {
            var equals = text.indexOf('=', start);
            var semicolon = text.indexOf(';', start);

            if (equals < 0 || (semicolon >= 0 && semicolon < equals)) {
                return new Parameter("",
                                     "",
                                     semicolon < 0
                                     ? text.length()
                                     : semicolon);
            }

            var name = text.substring(start, equals).strip();
            var valueStart = skipSpaces(text, equals + 1);

            return valueStart < text.length() && text.charAt(valueStart) == '"'
                   ? quoted(text, name, valueStart + 1)
                   : token(text, name, valueStart);
        }

        private static int skipSpaces(String text, int from) {
            var index = from;

            while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == '\t')) {
                index++;
            }

            return index;
        }

        private static Parameter token(String text, String name, int from) {
            var semicolon = text.indexOf(';', from);
            var end = semicolon < 0
                      ? text.length()
                      : semicolon;

            return new Parameter(name,
                                 text.substring(from, end).strip(),
                                 end);
        }

        private static Parameter quoted(String text, String name, int from) {
            var value = new StringBuilder();
            var index = from;

            while (index < text.length() && text.charAt(index) != '"') {
                if (text.charAt(index) == '\\' && index + 1 < text.length()) {
                    index++;
                }

                value.append(text.charAt(index++));
            }

            var semicolon = text.indexOf(';', index);

            return new Parameter(name,
                                 value.toString(),
                                 semicolon < 0
                                 ? text.length()
                                 : semicolon);
        }
    }

    record unused() implements HeaderParameters {}
}
