// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
