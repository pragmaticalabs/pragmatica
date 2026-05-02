// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.lang.Result;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;
import java.util.function.Function;

/// Shared interactive-prompt helper for CLI commands. Consolidates yes/no
/// confirmation, single-line read with default, validated read with re-prompt,
/// and numbered choice from a list.
///
/// Default constructor wires `System.in` / `System.out`. Tests construct with
/// arbitrary streams.
@SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01"})
public class Prompt {
    private final BufferedReader reader;
    private final PrintStream out;

    public Prompt() {
        this(System.in, System.out);
    }

    public Prompt(InputStream in, PrintStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in));
        this.out = out;
    }

    /// Read one trimmed line. Returns empty string on EOF or IO error.
    public String readLine() {
        try {
            var line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException _) {
            return "";
        }
    }

    /// Print a question and read a single line. If blank, returns the default.
    /// `defaultValue` may be null or empty — no `[default]` hint is shown then.
    public String prompt(String question, String defaultValue) {
        var hint = defaultValue == null || defaultValue.isEmpty()
                  ? ": "
                  : " [" + defaultValue + "]: ";
        out.print(question + hint);
        out.flush();
        var input = readLine();
        return input.isEmpty() && defaultValue != null ? defaultValue : input;
    }

    /// Yes/no confirmation. Default capitalisation reflects the default. Empty
    /// input takes the default; `y` / `yes` (case-insensitive) → true; anything else → false.
    public boolean confirm(String question, boolean defaultYes) {
        var hint = defaultYes ? "[Y/n]" : "[y/N]";
        out.print(question + " " + hint + " ");
        out.flush();
        var input = readLine().toLowerCase();
        if (input.isEmpty()) {
            return defaultYes;
        }
        return "y".equals(input) || "yes".equals(input);
    }

    /// Print a numbered list and read a 1-based selection. Re-prompts on
    /// invalid input. Returns the chosen option.
    public <T> T choice(String question, List<T> options, T defaultOption) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("choice() requires at least one option");
        }
        var defaultIdx = options.indexOf(defaultOption);
        while (true) {
            out.println(question);
            for (int i = 0; i < options.size(); i++) {
                var marker = i == defaultIdx ? "*" : " ";
                out.println("  " + marker + " " + (i + 1) + ") " + options.get(i));
            }
            var hint = defaultIdx >= 0 ? " [" + (defaultIdx + 1) + "]: " : ": ";
            out.print("Choice" + hint);
            out.flush();
            var input = readLine();
            if (input.isEmpty() && defaultIdx >= 0) {
                return options.get(defaultIdx);
            }
            try {
                var idx = Integer.parseInt(input) - 1;
                if (idx >= 0 && idx < options.size()) {
                    return options.get(idx);
                }
            } catch (NumberFormatException ignored) {
                // fall through to re-prompt
            }
            out.println("  Please pick a number 1-" + options.size() + ".");
        }
    }

    /// Read a value, validate it, re-prompt on failure with the validator's error message.
    /// Empty input takes the default if non-null.
    public <T> T promptValidated(String question, String defaultValue, Function<String, Result<T>> validator) {
        while (true) {
            var raw = prompt(question, defaultValue);
            var result = validator.apply(raw);
            if (result.isSuccess()) {
                return result.fold(_ -> { throw new AssertionError("isSuccess but fold-failure ran"); },
                                    value -> value);
            }
            result.onFailure(cause -> out.println("  ✗ " + cause.message()));
        }
    }
}
