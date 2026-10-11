// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.parser.ast.common;

import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;


public record Identifier(SourceSpan span, String value, QuoteStyle style) {
    public enum QuoteStyle {
        UNQUOTED,
        DOUBLE_QUOTED,
        UNICODE_QUOTED
    }

    public String normalized() {
        return style == QuoteStyle.UNQUOTED
               ? value.toLowerCase()
               : value;
    }

    public static Identifier unquoted(SourceSpan span, String value) {
        return new Identifier(span, value, QuoteStyle.UNQUOTED);
    }

    public static Identifier quoted(SourceSpan span, String value) {
        return new Identifier(span, value, QuoteStyle.DOUBLE_QUOTED);
    }

    @Override
    public String toString() {
        return style == QuoteStyle.UNQUOTED
               ? normalized()
               : "\"" + value + "\"";
    }
}
