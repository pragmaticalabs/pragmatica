package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomly selects from a list of choices.
 * <p>
 * Pattern: {@code ${choice:A,B,C}} where A, B, C are comma-separated options.
 * <p>
 * Example: {@code ${choice:NYC,LAX,CHI}} randomly picks one of the three values
 */
public record ChoiceGenerator(List<String> choices) implements PatternGenerator {
    public static final String TYPE = "choice";

    public static ChoiceGenerator choiceGenerator(List<String> choices) {
        return new ChoiceGenerator(choices);
    }

    private static final Fn1<Cause, String> INVALID_CHOICE = Causes.forOneValue("Invalid choice format: %s. Expected comma-separated values");

    /**
     * Parses a choice specification like "A,B,C".
     */
    public static Result<PatternGenerator> choiceGenerator(String choiceSpec) {
        return Option.option(choiceSpec)
                     .filter(s -> !s.isBlank())
                     .toResult(INVALID_CHOICE.apply("empty"))
                     .flatMap(ChoiceGenerator::parseChoices);
    }

    private static Result<PatternGenerator> parseChoices(String choiceSpec) {
        var choices = Arrays.stream(choiceSpec.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
        if (choices.isEmpty()) {
            return INVALID_CHOICE.apply(choiceSpec)
                                 .result();
        }
        return Result.success(choiceGenerator(choices));
    }

    @Override
    public String generate() {
        return choices.get(ThreadLocalRandom.current()
                                            .nextInt(choices.size()));
    }

    @Override
    public String pattern() {
        return "${choice:" + String.join(",", choices) + "}";
    }
}
