// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record ChoiceGenerator(List<String> choices) implements PatternGenerator {
    public static final String TYPE = "choice";

    public static Result<ChoiceGenerator> choiceGenerator(List<String> choices) {
        return success(new ChoiceGenerator(choices));
    }

    private static final Fn1<Cause, String> INVALID_CHOICE = Causes.forOneValue("Invalid choice format: %s. Expected comma-separated values");

    public static Result<PatternGenerator> choiceGenerator(String choiceSpec) {
        return option(choiceSpec).filter(s -> !s.isBlank())
                     .toResult(INVALID_CHOICE.apply("empty"))
                     .flatMap(ChoiceGenerator::toChoices);
    }

    private static Result<PatternGenerator> toChoices(String choiceSpec) {
        var choices = Arrays.stream(choiceSpec.split(",")).map(String::trim)
                                   .filter(s -> !s.isEmpty())
                                   .toList();
        return ensureNonEmpty(choices, choiceSpec);
    }

    private static Result<PatternGenerator> ensureNonEmpty(List<String> choices, String choiceSpec) {
        return choices.isEmpty()
              ? INVALID_CHOICE.apply(choiceSpec).result()
              : choiceGenerator(choices).map(gen -> gen);
    }

    @Override public String generate() {
        return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    @Override public String pattern() {
        return "${choice:" + String.join(",", choices) + "}";
    }
}
