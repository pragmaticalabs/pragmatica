// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.postgres.r2dbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2481 (v-2475 D-3): `??` is a literal `?` outside quoted text and comments; everything else is copied unchanged.
class QuestionMarkEscapeTest {
    @Test
    void unescape_doubledQuestionMark_becomesOne() {
        assertThat(QuestionMarkEscape.unescape("SELECT a ?? 'k'")).isEqualTo("SELECT a ? 'k'");
        assertThat(QuestionMarkEscape.unescape("SELECT a ??| ARRAY['k'] AND a ??& ARRAY['k']")).isEqualTo("SELECT a ?| ARRAY['k'] AND a ?& ARRAY['k']");
    }

    @Test
    void unescape_withoutTheEscape_isUnchanged() {
        assertThat(QuestionMarkEscape.unescape("SELECT a ? 'k', $1, $2")).isEqualTo("SELECT a ? 'k', $1, $2");
    }

    @Test
    void unescape_parametersNextToTheEscape_areKept() {
        assertThat(QuestionMarkEscape.unescape("SELECT a ?? $1 AND $2 ?? b")).isEqualTo("SELECT a ? $1 AND $2 ? b");
    }

    @Test
    void unescape_threeQuestionMarks_isEscapeThenLiteral() {
        assertThat(QuestionMarkEscape.unescape("a ??? b")).isEqualTo("a ?? b");
    }

    @Test
    void unescape_insideQuotedText_isUnchanged() {
        assertThat(QuestionMarkEscape.unescape("SELECT 'what??', \"col??\", 'it''s ??' ?? x")).isEqualTo("SELECT 'what??', \"col??\", 'it''s ??' ? x");
    }

    @Test
    void unescape_insideDollarQuotes_isUnchanged() {
        assertThat(QuestionMarkEscape.unescape("SELECT $$ ?? $$ ?? $tag$ ?? $tag$ ?? $1")).isEqualTo("SELECT $$ ?? $$ ? $tag$ ?? $tag$ ? $1");
    }

    @Test
    void unescape_dollarTagWithADigitAfterTheFirstCharacter_isAQuote() {
        assertThat(QuestionMarkEscape.unescape("SELECT $a1$ ?? $a1$ ?? x")).isEqualTo("SELECT $a1$ ?? $a1$ ? x");
    }

    @Test
    void unescape_insideComments_isUnchanged() {
        assertThat(QuestionMarkEscape.unescape("a ?? b -- note ??\nc ?? d /* x ?? /* nested ?? */ still ?? */ e ?? f"))
            .isEqualTo("a ? b -- note ??\nc ? d /* x ?? /* nested ?? */ still ?? */ e ? f");
    }

    @Test
    void unescape_unterminatedQuote_runsToTheEnd() {
        assertThat(QuestionMarkEscape.unescape("a ?? 'open ??")).isEqualTo("a ? 'open ??");
    }
}
