// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTest {

    private static Prompt promptOf(String input, ByteArrayOutputStream output) {
        var in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        return new Prompt(in, new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private static Prompt promptOf(String input) {
        return promptOf(input, new ByteArrayOutputStream());
    }

    @Nested
    class PromptDefault {

        @Test
        void prompt_emptyInputWithDefault_returnsDefault() {
            var prompt = promptOf("\n");
            assertThat(prompt.prompt("Q", "default")).isEqualTo("default");
        }

        @Test
        void prompt_nonEmptyInputWithDefault_returnsTrimmedInput() {
            var prompt = promptOf("  hello  \n");
            assertThat(prompt.prompt("Q", "default")).isEqualTo("hello");
        }

        @Test
        void prompt_emptyInputNoDefault_returnsEmptyString() {
            var prompt = promptOf("\n");
            assertThat(prompt.prompt("Q", "")).isEmpty();
        }
    }

    @Nested
    class Confirm {

        @Test
        void confirm_emptyInputDefaultYes_returnsTrue() {
            var prompt = promptOf("\n");
            assertThat(prompt.confirm("Q", true)).isTrue();
        }

        @Test
        void confirm_emptyInputDefaultNo_returnsFalse() {
            var prompt = promptOf("\n");
            assertThat(prompt.confirm("Q", false)).isFalse();
        }

        @Test
        void confirm_yResponseDefaultNo_returnsTrue() {
            var prompt = promptOf("y\n");
            assertThat(prompt.confirm("Q", false)).isTrue();
        }

        @Test
        void confirm_nResponseDefaultYes_returnsFalse() {
            var prompt = promptOf("n\n");
            assertThat(prompt.confirm("Q", true)).isFalse();
        }
    }

    @Nested
    class Choice {

        @Test
        void choice_numericSelection_returnsChosenOption() {
            var prompt = promptOf("1\n");
            assertThat(prompt.choice("Q", List.of("A", "B", "C"), "B")).isEqualTo("A");
        }

        @Test
        void choice_emptyInput_returnsDefault() {
            var prompt = promptOf("\n");
            assertThat(prompt.choice("Q", List.of("A", "B", "C"), "B")).isEqualTo("B");
        }
    }

    @Nested
    class PromptValidated {

        @Test
        void promptValidated_validInput_returnsParsedValue() {
            var prompt = promptOf("42\n");
            int value = prompt.promptValidated("Q", null, str -> Result.success(Integer.parseInt(str.trim())));
            assertThat(value).isEqualTo(42);
        }
    }
}
