package org.pragmatica.aether.forge.load.pattern;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates random strings based on a pattern.
 * <p>
 * Pattern: {@code ${random:TEMPLATE}} where TEMPLATE contains:
 * <ul>
 *   <li>{@code #} - Random digit (0-9)</li>
 *   <li>{@code ?} - Random lowercase letter (a-z)</li>
 *   <li>{@code *} - Random alphanumeric (a-z, 0-9)</li>
 *   <li>Any other character - Literal</li>
 * </ul>
 * <p>
 * Example: {@code ${random:SKU-#####}} generates "SKU-48291"
 */
public record RandomGenerator(String template) implements PatternGenerator {
    public static final String TYPE = "random";

    public static RandomGenerator randomGenerator(String template) {
        return new RandomGenerator(template);
    }

    private static final String DIGITS = "0123456789";
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz";
    private static final String ALPHANUMERIC = LETTERS + DIGITS;

    @Override
    public String generate() {
        var random = ThreadLocalRandom.current();
        var result = new StringBuilder(template.length());
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            result.append(switch (c) {
                case '#' -> DIGITS.charAt(random.nextInt(DIGITS.length()));
                case '?' -> LETTERS.charAt(random.nextInt(LETTERS.length()));
                case '*' -> ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length()));
                default -> c;
            });
        }
        return result.toString();
    }

    @Override
    public String pattern() {
        return "${random:" + template + "}";
    }
}
