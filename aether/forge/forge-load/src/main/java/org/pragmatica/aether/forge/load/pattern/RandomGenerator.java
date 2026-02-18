package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Result.success;

/// Generates random strings based on a pattern.
///
/// Pattern: `${random:TEMPLATE`} where TEMPLATE contains:
///
///   - `#` - Random digit (0-9)
///   - `?` - Random lowercase letter (a-z)
///   - `*` - Random alphanumeric (a-z, 0-9)
///   - Any other character - Literal
///
///
/// Example: `${random:SKU-#####`} generates "SKU-48291"
public record RandomGenerator(String template) implements PatternGenerator {
    public static final String TYPE = "random";

    private static final Cause EMPTY_TEMPLATE = Causes.cause("Random generator template cannot be empty");

    public static Result<RandomGenerator> randomGenerator(String template) {
        return Verify.ensure(template, Verify.Is::notNull, EMPTY_TEMPLATE)
                     .filter(EMPTY_TEMPLATE,
                             s -> !s.isEmpty())
                     .map(RandomGenerator::new);
    }

    private static final String DIGITS = "0123456789";
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String ALPHANUMERIC = LETTERS + DIGITS;

    @Override
    public String generate() {
        var random = ThreadLocalRandom.current();
        var chars = IntStream.range(0,
                                    template.length())
                             .mapToObj(i -> generateChar(template.charAt(i),
                                                         random))
                             .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append);
        return chars.toString();
    }

    private static char generateChar(char c, ThreadLocalRandom random) {
        return switch (c) {
            case '#' -> randomFrom(DIGITS, random);
            case '?' -> randomFrom(LETTERS, random);
            case '*' -> randomFrom(ALPHANUMERIC, random);
            default -> c;
        };
    }

    private static char randomFrom(String source, ThreadLocalRandom random) {
        return source.charAt(random.nextInt(source.length()));
    }

    @Override
    public String pattern() {
        return "${random:" + template + "}";
    }
}
