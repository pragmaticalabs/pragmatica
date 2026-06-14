// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/// Shared interactive-confirmation guard for destructive CLI commands (#301): `drain`, `scale`,
/// `migrate`, and `restore`. Mirrors the `cluster destroy` model — a `--yes`/`--force` flag
/// bypasses confirmation for scripts, an interactive TTY prompts for explicit consent, and a
/// non-interactive shell WITHOUT the bypass refuses (so a piped/CI invocation cannot silently
/// execute a destructive action). `cluster destroy` keeps its stronger name-re-confirmation guard.
public final class DestructiveAction {
    private final BooleanSupplier interactive;
    private final Supplier<Prompt> promptFactory;

    private DestructiveAction(BooleanSupplier interactive, Supplier<Prompt> promptFactory) {
        this.interactive = interactive;
        this.promptFactory = promptFactory;
    }

    /// Production guard: TTY detection via `System.console()`, prompt over stdin/stdout.
    public static DestructiveAction destructiveAction() {
        return new DestructiveAction(() -> System.console() != null, Prompt::new);
    }

    /// Test/inject seam: explicit interactivity and prompt source.
    public static DestructiveAction destructiveAction(BooleanSupplier interactive, Supplier<Prompt> promptFactory) {
        return new DestructiveAction(interactive, promptFactory);
    }

    /// Returns true when the destructive action may proceed. `skipConfirmation` (the command's
    /// `--yes`/`--force` flag) bypasses unconditionally; otherwise a non-interactive shell refuses
    /// and an interactive shell asks the operator (default No).
    public boolean confirm(boolean skipConfirmation, String warning) {
        if (skipConfirmation) {
            return true;
        }

        if (!interactive.getAsBoolean()) {
            System.err.println(warning);
            System.err.println("Refusing to proceed without confirmation. Re-run with --yes to override.");

            return false;
        }

        System.out.println(warning);

        return promptFactory.get()
                            .confirm("Proceed?", false);
    }
}
