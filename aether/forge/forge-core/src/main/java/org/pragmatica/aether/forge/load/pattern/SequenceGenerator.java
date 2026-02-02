package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates sequential values starting from a specified number.
 * <p>
 * Pattern: {@code ${seq:START}} where START is the initial value.
 * <p>
 * Example: {@code ${seq:1000}} generates 1000, 1001, 1002, ...
 * <p>
 * Note: This generator is thread-safe and maintains state across calls.
 */
public final class SequenceGenerator implements PatternGenerator {
    public static final String TYPE = "seq";

    private final long start;
    private final AtomicLong counter;

    public static SequenceGenerator sequenceGenerator(long start) {
        return new SequenceGenerator(start);
    }

    SequenceGenerator(long start) {
        this.start = start;
        this.counter = new AtomicLong(start);
    }

    /**
     * Parses a sequence specification like "1000".
     */
    public static Result<PatternGenerator> sequenceGenerator(String seqSpec) {
        return Option.option(seqSpec)
                     .map(String::trim)
                     .filter(s -> !s.isBlank())
                     .map(s -> Number.parseLong(s)
                                     .<PatternGenerator> map(SequenceGenerator::sequenceGenerator))
                     .or(Result.success(sequenceGenerator(1)));
    }

    @Override
    public String generate() {
        return String.valueOf(counter.getAndIncrement());
    }

    @Override
    public String pattern() {
        return "${seq:" + start + "}";
    }

    /**
     * Resets the counter to the initial start value.
     */
    public void reset() {
        counter.set(start);
    }

    /**
     * Returns the current counter value without incrementing.
     */
    public long current() {
        return counter.get();
    }
}
