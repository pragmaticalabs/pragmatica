package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Result.success;

/// Generates random integers within a specified range.
///
/// Pattern: `${range:MIN-MAX`} where MIN and MAX are integers.
///
/// Example: `${range:1-100`} generates a random number between 1 and 100 (inclusive)
public record RangeGenerator(int min, int max) implements PatternGenerator {
    public static final String TYPE = "range";

    public static Result<RangeGenerator> rangeGenerator(int min, int max) {
        return success(new RangeGenerator(min, max));
    }

    private static final Pattern RANGE_PATTERN = Pattern.compile("^(-?\\d+)-(-?\\d+)$");

    private static final Fn1<Cause, String> INVALID_RANGE = Causes.forOneValue("Invalid range format: %s. Expected MIN-MAX");

    private static final Cause MIN_GREATER_THAN_MAX = Causes.cause("Range min cannot be greater than max");

    /// Parses a range specification like "1-100" or "-50-50".
    public static Result<PatternGenerator> rangeGenerator(String rangeSpec) {
        var matcher = RANGE_PATTERN.matcher(rangeSpec.trim());
        if (!matcher.matches()) {
            return INVALID_RANGE.apply(rangeSpec)
                                .result();
        }
        var parsedValues = all(Number.parseInt(matcher.group(1)),
                               Number.parseInt(matcher.group(2)));
        return parsedValues.flatMap(RangeGenerator::ensureMinNotGreaterThanMax);
    }

    private static Result<PatternGenerator> ensureMinNotGreaterThanMax(int min, int max) {
        if (min > max) {
            return MIN_GREATER_THAN_MAX.result();
        }
        return rangeGenerator(min, max).map(gen -> gen);
    }

    @Override
    public String generate() {
        return String.valueOf(ThreadLocalRandom.current()
                                               .nextInt(min, max + 1));
    }

    @Override
    public String pattern() {
        return "${range:" + min + "-" + max + "}";
    }
}
