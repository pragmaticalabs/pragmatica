// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;
import java.util.function.Function;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


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

    public String readLine() {
        try {
            var line = reader.readLine();

            return line == null
                   ? ""
                   : line.trim();
        } catch (IOException _) {
            return "";
        }
    }

    public String prompt(String question, String defaultValue) {
        var fallback = Option.option(defaultValue);
        var hint = fallback.filter(value -> !value.isEmpty()).map(value -> " [" + value + "]: ").or(": ");

        out.print(question + hint);
        out.flush();
        var input = readLine();

        return input.isEmpty()
               ? fallback.or(input)
               : input;
    }

    public boolean confirm(String question, boolean defaultYes) {
        var hint = defaultYes
                   ? "[Y/n]"
                   : "[y/N]";

        out.print(question + " " + hint + " ");
        out.flush();
        var input = readLine().toLowerCase();

        if (input.isEmpty()) {
            return defaultYes;
        }

        return "y".equals(input) || "yes".equals(input);
    }

    public <T> T choice(String question, List<T> options, T defaultOption) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("choice() requires at least one option");
        }

        var defaultIdx = options.indexOf(defaultOption);

        while (true) {
            out.println(question);
            for (int i = 0; i < options.size(); i++) {
                var marker = i == defaultIdx
                             ? "*"
                             : " ";

                out.println("  " + marker + " " + (i + 1) + ") " + options.get(i));
            }

            var hint = defaultIdx >= 0
                       ? " [" + (defaultIdx + 1) + "]: "
                       : ": ";

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
            } catch (NumberFormatException ignored) {}

            out.println("  Please pick a number 1-" + options.size() + ".");
        }
    }

    public <T> T promptValidated(String question, String defaultValue, Function<String, Result<T>> validator) {
        return validator.apply(prompt(question, defaultValue))
                        .fold(cause -> retryPrompt(question, defaultValue, validator, cause),
                              value -> value);
    }

    private <T> T retryPrompt(String question,
                              String defaultValue,
                              Function<String, Result<T>> validator,
                              Cause cause) {
        out.println("  ✗ " + cause.message());

        return promptValidated(question, defaultValue, validator);
    }
}
