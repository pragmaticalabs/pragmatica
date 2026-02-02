package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Functions.Fn1;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/**
 * Parses pattern specifications like {@code ${type:args}} into PatternGenerator instances.
 */
public sealed interface PatternParser {
    Pattern PATTERN_REGEX = Pattern.compile("\\$\\{([a-z]+)(?::(.*))?}");

    Fn1<Cause, String> UNKNOWN_TYPE = Causes.forOneValue("Unknown pattern type: %s");

    Fn1<Cause, String> INVALID_PATTERN = Causes.forOneValue("Invalid pattern syntax: %s");

    /**
     * Parses a pattern string like "${uuid}" or "${random:SKU-#####}" into a generator.
     *
     * @param pattern the pattern string
     * @return Result containing the generator or an error
     */
    static Result<PatternGenerator> parse(String pattern) {
        var matcher = PATTERN_REGEX.matcher(pattern.trim());
        if (!matcher.matches()) {
            return INVALID_PATTERN.apply(pattern)
                                  .result();
        }
        var type = matcher.group(1);
        var args = matcher.group(2);
        // may be null
        return switch (type) {
            case UuidGenerator.TYPE -> parseUuid();
            case RandomGenerator.TYPE -> parseRandom(args, pattern);
            case RangeGenerator.TYPE -> parseRange(args, pattern);
            case ChoiceGenerator.TYPE -> parseChoice(args, pattern);
            case SequenceGenerator.TYPE -> SequenceGenerator.sequenceGenerator(args);
            default -> UNKNOWN_TYPE.apply(type)
                                   .result();
        };
    }

    private static Result<PatternGenerator> parseUuid() {
        return Result.success(UuidGenerator.uuidGenerator());
    }

    private static Result<PatternGenerator> parseRandom(String args, String pattern) {
        return Option.option(args)
                     .filter(s -> !s.isEmpty())
                     .toResult(INVALID_PATTERN.apply(pattern + " (random requires template)"))
                     .map(RandomGenerator::randomGenerator);
    }

    private static Result<PatternGenerator> parseRange(String args, String pattern) {
        return Option.option(args)
                     .filter(s -> !s.isEmpty())
                     .toResult(INVALID_PATTERN.apply(pattern + " (range requires MIN-MAX)"))
                     .flatMap(RangeGenerator::rangeGenerator);
    }

    private static Result<PatternGenerator> parseChoice(String args, String pattern) {
        return Option.option(args)
                     .filter(s -> !s.isEmpty())
                     .toResult(INVALID_PATTERN.apply(pattern + " (choice requires values)"))
                     .flatMap(ChoiceGenerator::choiceGenerator);
    }

    /**
     * Checks if a string contains any pattern placeholders.
     */
    static boolean containsPatterns(String text) {
        return text != null && text.contains("${");
    }

    /**
     * Extracts the pattern type from a pattern string.
     */
    static Option<String> extractType(String pattern) {
        var matcher = PATTERN_REGEX.matcher(pattern.trim());
        return matcher.matches()
               ? some(matcher.group(1))
               : none();
    }

    record Unused() implements PatternParser {}
}
