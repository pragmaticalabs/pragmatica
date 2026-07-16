// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/// #301: destructive CLI commands (drain/scale/migrate/restore) must not execute without
/// confirmation. The shared guard bypasses on `--yes`, prompts in an interactive TTY, and refuses
/// in a non-interactive shell that omits the bypass (so scripts/CI cannot silently destroy state).
class DestructiveActionTest {
    private static final String WARNING = "This will do something destructive.";

    private static Prompt promptOf(String input) {
        var in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

        return new Prompt(in, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @Test
    void confirm_proceeds_whenYesFlagSet() {
        var guard = DestructiveAction.destructiveAction(() -> false, () -> promptOf(""));

        assertThat(guard.confirm(true, WARNING)).isTrue();
    }

    @Test
    void confirm_refuses_whenNonInteractiveAndNoYesFlag() {
        var guard = DestructiveAction.destructiveAction(() -> false, () -> promptOf("yes\n"));

        assertThat(guard.confirm(false, WARNING)).isFalse();
    }

    @Test
    void confirm_proceeds_whenInteractiveAndOperatorConfirms() {
        var guard = DestructiveAction.destructiveAction(() -> true, () -> promptOf("yes\n"));

        assertThat(guard.confirm(false, WARNING)).isTrue();
    }

    @Test
    void confirm_refuses_whenInteractiveAndOperatorDeclines() {
        var guard = DestructiveAction.destructiveAction(() -> true, () -> promptOf("no\n"));

        assertThat(guard.confirm(false, WARNING)).isFalse();
    }

    @Test
    void confirm_refuses_whenInteractiveAndOperatorEntersBlank() {
        var guard = DestructiveAction.destructiveAction(() -> true, () -> promptOf("\n"));

        assertThat(guard.confirm(false, WARNING)).isFalse();
    }
}
