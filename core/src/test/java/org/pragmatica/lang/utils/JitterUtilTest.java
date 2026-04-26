/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.lang.utils;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JitterUtilTest {

    @Test
    void applyJitter_lowerBound_returnedWhenRandomIsZero() {
        DoubleSupplier zero = () -> 0.0d;
        var result = JitterUtil.applyJitter(1000L, 0.5d, 1.5d, zero);
        assertEquals(500L, result, "factor=0.5 yields baseMs * 0.5");
    }

    @Test
    void applyJitter_upperBound_approachedWhenRandomNearOne() {
        DoubleSupplier nearOne = () -> 0.9999d;
        var result = JitterUtil.applyJitter(1000L, 0.5d, 1.5d, nearOne);
        assertTrue(result >= 1499L && result <= 1500L,
                   "factor near 1.5 yields ~1500ms, got " + result);
    }

    @Test
    void applyJitter_midpoint_returnsBaseValue() {
        DoubleSupplier half = () -> 0.5d;
        var result = JitterUtil.applyJitter(1000L, 0.5d, 1.5d, half);
        assertEquals(1000L, result, "factor=1.0 (midpoint) yields baseMs");
    }

    @Test
    void applyJitter_seededRandom_producesDeterministicSequenceWithinBounds() {
        var random = new Random(42L);
        var supplier = JitterUtil.supplierFrom(random);
        for (var i = 0; i <100; i++) {
            var result = JitterUtil.applyJitter(1000L, 0.5d, 1.5d, supplier);
            assertTrue(result >= 500L && result <= 1500L,
                       "iteration " + i + ": jittered value " + result + " outside [500, 1500]");
        }
    }

    @Test
    void applyJitter_lightJitterBounds_kept_within_0_8_to_1_2() {
        var random = new Random(123L);
        var supplier = JitterUtil.supplierFrom(random);
        for (var i = 0; i <100; i++) {
            var result = JitterUtil.applyJitter(10_000L,
                                                 JitterUtil.LIGHT_MIN_FACTOR,
                                                 JitterUtil.LIGHT_MAX_FACTOR,
                                                 supplier);
            assertTrue(result >= 8000L && result <= 12_000L,
                       "iteration " + i + ": light-jittered value " + result + " outside [8000, 12000]");
        }
    }

    @Test
    void applyJitter_zeroBase_returnsZero() {
        var result = JitterUtil.applyJitter(0L, 0.5d, 1.5d);
        assertEquals(0L, result);
    }

    @Test
    void applyJitter_negativeBase_passesThroughUnchanged() {
        var result = JitterUtil.applyJitter(-5L, 0.5d, 1.5d);
        assertEquals(-5L, result, "negative base bypasses jitter math");
    }

    @Test
    void applyJitter_smallBaseWithLowFactor_neverGoesBelowOne() {
        DoubleSupplier zero = () -> 0.0d;
        // base=1, factor=0.5 → (long)(1 * 0.5) = 0, but Math.max(1, 0) = 1
        var result = JitterUtil.applyJitter(1L, 0.5d, 1.5d, zero);
        assertEquals(1L, result, "result clamped to >= 1 when baseMs > 0");
    }

    @Test
    void applyJitter_defaultOverload_usesHalfToOnePointFiveRange() {
        // sample many times — must always fall in [baseMs/2, baseMs*1.5]
        for (var i = 0; i <200; i++) {
            var result = JitterUtil.applyJitter(2000L);
            assertTrue(result >= 1000L && result <= 3000L,
                       "default-jittered value " + result + " outside [1000, 3000]");
        }
    }

    @Test
    void applyJitter_distinctSeedsProduceDistinctSequences() {
        // Two seeded suppliers should diverge — confirms randomness flows from supplier
        var a = JitterUtil.supplierFrom(new Random(1L));
        var b = JitterUtil.supplierFrom(new Random(2L));
        var anyDifferent = false;
        for (var i = 0; i <20; i++) {
            if (JitterUtil.applyJitter(1000L, 0.5d, 1.5d, a)
                != JitterUtil.applyJitter(1000L, 0.5d, 1.5d, b)) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "different seeds should yield different jittered values");
    }
}
