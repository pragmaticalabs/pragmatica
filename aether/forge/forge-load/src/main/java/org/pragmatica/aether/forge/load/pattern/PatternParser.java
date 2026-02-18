package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Parses pattern specifications like `${type:args`} into PatternGenerator instances.
public sealed interface PatternParser {
    Pattern PATTERN_REGEX = Pattern.compile("\\$\\{([a-z]+)(?::(.*))?}");

    Fn1<Cause, String> UNKNOWN_TYPE = Causes.forOneValue("Unknown pattern type: %s");

    Fn1<Cause, String> INVALID_PATTERN = Causes.forOneValue("Invalid pattern syntax: %s");

    /// Parses a pattern string like "${uuid}" or "${random:SKU-#####}" into a generator.
    ///
    /// @param pattern the pattern string
    /// @return Result containing the generator or an error
    static Result<PatternGenerator> parse(String pattern) {
        var matcher = PATTERN_REGEX.matcher(pattern.trim());
        if (!matcher.matches()) {
            return INVALID_PATTERN.apply(pattern)
                                  .result();
        }
        var type = matcher.group(1);
        var args = matcher.group(2);
        return dispatchByType(type, args, pattern);
    }

    private static Result<PatternGenerator> dispatchByType(String type, String args, String pattern) {
        return switch (type) {
            case UuidGenerator.TYPE -> toUuidGenerator();
            case RandomGenerator.TYPE -> toRandomGenerator(args, pattern);
            case RangeGenerator.TYPE -> toRangeGenerator(args, pattern);
            case ChoiceGenerator.TYPE -> toChoiceGenerator(args, pattern);
            case SequenceGenerator.TYPE -> toSequenceGenerator(args);
            default -> unknownType(type);
        };
    }

    private static Result<PatternGenerator> toUuidGenerator() {
        return UuidGenerator.uuidGenerator()
                            .map(gen -> gen);
    }

    private static Result<PatternGenerator> toSequenceGenerator(String args) {
        return SequenceGenerator.sequenceGenerator(args);
    }

    private static Result<PatternGenerator> unknownType(String type) {
        return UNKNOWN_TYPE.apply(type)
                           .result();
    }

    private static Result<PatternGenerator> toRandomGenerator(String args, String pattern) {
        return option(args).filter(s -> !s.isEmpty())
                     .toResult(INVALID_PATTERN.apply(pattern + " (random requires template)"))
                     .flatMap(PatternParser::widenRandomGenerator);
    }

    private static Result<PatternGenerator> widenRandomGenerator(String template) {
        return RandomGenerator.randomGenerator(template)
                              .map(gen -> gen);
    }

    private static Result<PatternGenerator> toRangeGenerator(String args, String pattern) {
        return option(args).filter(s -> !s.isEmpty())
                     .toResult(INVALID_PATTERN.apply(pattern + " (range requires MIN-MAX)"))
                     .flatMap(RangeGenerator::rangeGenerator);
    }

    private static Result<PatternGenerator> toChoiceGenerator(String args, String pattern) {
        return option(args).filter(s -> !s.isEmpty())
                     .toResult(INVALID_PATTERN.apply(pattern + " (choice requires values)"))
                     .flatMap(ChoiceGenerator::choiceGenerator);
    }

    /// Checks if a string contains any pattern placeholders.
    static boolean containsPatterns(String text) {
        return option(text).filter(t -> t.contains("${"))
                     .isPresent();
    }

    /// Extracts the pattern type from a pattern string.
    static Option<String> extractType(String pattern) {
        var matcher = PATTERN_REGEX.matcher(pattern.trim());
        return matcher.matches()
               ? some(matcher.group(1))
               : none();
    }

    record unused() implements PatternParser {}
}
