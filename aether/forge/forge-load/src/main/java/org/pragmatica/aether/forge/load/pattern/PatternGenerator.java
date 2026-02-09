package org.pragmatica.aether.forge.load.pattern;
/// Sealed interface for pattern generators used in load testing configuration.
///
/// Pattern syntax: `${type:args`} where type determines the generator
/// and args are type-specific parameters.
///
/// Supported patterns:
///
///   - `${uuid`} - Random UUID
///   - `${random:PATTERN`} - Pattern with #=digit, ?=letter, *=alphanumeric
///   - `${range:MIN-MAX`} - Random integer in range
///   - `${choice:A,B,C`} - Random pick from list
///   - `${seq:START`} - Sequential counter starting at START
///
public sealed interface PatternGenerator
 permits UuidGenerator, RandomGenerator, RangeGenerator, ChoiceGenerator, SequenceGenerator {
    /// Generates the next value according to this generator's pattern.
    ///
    /// @return generated value as a string
    String generate();

    /// Returns the original pattern string for this generator.
    String pattern();
}
