package org.pragmatica.aether.forge.load.pattern;
/**
 * Sealed interface for pattern generators used in load testing configuration.
 * <p>
 * Pattern syntax: {@code ${type:args}} where type determines the generator
 * and args are type-specific parameters.
 * <p>
 * Supported patterns:
 * <ul>
 *   <li>{@code ${uuid}} - Random UUID</li>
 *   <li>{@code ${random:PATTERN}} - Pattern with #=digit, ?=letter, *=alphanumeric</li>
 *   <li>{@code ${range:MIN-MAX}} - Random integer in range</li>
 *   <li>{@code ${choice:A,B,C}} - Random pick from list</li>
 *   <li>{@code ${seq:START}} - Sequential counter starting at START</li>
 * </ul>
 */
public sealed interface PatternGenerator
 permits UuidGenerator, RandomGenerator, RangeGenerator, ChoiceGenerator, SequenceGenerator {
    /**
     * Generates the next value according to this generator's pattern.
     *
     * @return generated value as a string
     */
    String generate();

    /**
     * Returns the original pattern string for this generator.
     */
    String pattern();
}
