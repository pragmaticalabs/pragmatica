package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.parse.Number;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Generates random integers within a specified range.
 * <p>
 * Pattern: {@code ${range:MIN-MAX}} where MIN and MAX are integers.
 * <p>
 * Example: {@code ${range:1-100}} generates a random number between 1 and 100 (inclusive)
 */
public record RangeGenerator(int min, int max) implements PatternGenerator {
    public static final String TYPE = "range";

    public static RangeGenerator rangeGenerator(int min, int max) {
        return new RangeGenerator(min, max);
    }

    private static final Pattern RANGE_PATTERN = Pattern.compile("^(-?\\d+)-(-?\\d+)$");

    private static final Fn1<Cause, String> INVALID_RANGE = Causes.forOneValue("Invalid range format: %s. Expected MIN-MAX");

    private static final Cause MIN_GREATER_THAN_MAX = Causes.cause("Range min cannot be greater than max");

    /**
     * Parses a range specification like "1-100" or "-50-50".
     */
    public static Result<PatternGenerator> rangeGenerator(String rangeSpec) {
        var matcher = RANGE_PATTERN.matcher(rangeSpec.trim());
        if (!matcher.matches()) {
            return INVALID_RANGE.apply(rangeSpec)
                                .result();
        }
        return Result.all(Number.parseInt(matcher.group(1)),
                          Number.parseInt(matcher.group(2)))
                     .flatMap(RangeGenerator::validateAndCreate);
    }

    private static Result<PatternGenerator> validateAndCreate(int min, int max) {
        return min > max
               ? MIN_GREATER_THAN_MAX.result()
               : Result.success(rangeGenerator(min, max));
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
