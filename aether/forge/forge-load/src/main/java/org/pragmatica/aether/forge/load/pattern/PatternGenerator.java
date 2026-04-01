package org.pragmatica.aether.forge.load.pattern;
public sealed interface PatternGenerator permits UuidGenerator, RandomGenerator, RangeGenerator, ChoiceGenerator, SequenceGenerator {
    /// Generates the next value according to this generator's pattern.
    ///
    /// @return generated value as a string
    String generate();

    /// Returns the original pattern string for this generator.
    String pattern();
}
