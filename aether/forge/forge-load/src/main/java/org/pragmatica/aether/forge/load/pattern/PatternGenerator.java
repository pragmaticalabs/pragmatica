package org.pragmatica.aether.forge.load.pattern;

public sealed interface PatternGenerator permits UuidGenerator, RandomGenerator, RangeGenerator, ChoiceGenerator, SequenceGenerator {
    String generate();
    String pattern();
}
