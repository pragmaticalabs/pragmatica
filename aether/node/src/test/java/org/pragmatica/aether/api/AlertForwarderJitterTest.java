// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies jittered exponential backoff in [`AlertForwarder.jitteredBackoff`]:
///   - sequence is non-trivially increasing (median grows ~2x per attempt until cap)
///   - each value falls in [base*0.5, base*1.5] window
///   - jitter is non-zero (consecutive identical attempts produce varying values)
class AlertForwarderJitterTest {

    private static final long BASE_MS = 200L;
    private static final long CAP_MS = 30_000L;

    @Test
    void jitteredBackoff_attempt0_within50PercentOfBase() {
        for (var i = 0; i <100; i++) {
            var delay = AlertForwarder.jitteredBackoff(0);
            assertTrue(delay >= BASE_MS / 2 && delay <= (long) (BASE_MS * 1.5),
                       "attempt 0: delay " + delay + " outside [" + (BASE_MS / 2) + ", "
                       + (long) (BASE_MS * 1.5) + "]");
        }
    }

    @Test
    void jitteredBackoff_increasesAcrossAttempts_thenCaps() {
        // attempt N base = min(200 * 2^N, 30000)
        // attempt 0 → 200, attempt 1 → 400, attempt 2 → 800, ..., attempt 8 → 30000 (capped)
        var medianAttempt0 = sampleMedian(0, 51);
        var medianAttempt2 = sampleMedian(2, 51);
        var medianAttempt4 = sampleMedian(4, 51);
        assertTrue(medianAttempt2 > medianAttempt0,
                   "attempt 2 median (" + medianAttempt2 + ") must exceed attempt 0 median ("
                   + medianAttempt0 + ")");
        assertTrue(medianAttempt4 > medianAttempt2,
                   "attempt 4 median (" + medianAttempt4 + ") must exceed attempt 2 median ("
                   + medianAttempt2 + ")");
    }

    @Test
    void jitteredBackoff_capsAtMaximum() {
        // attempt 10 base = 200 * 1024 = 204800, capped to 30000
        // jittered max is 30000 * 1.5 = 45000
        for (var i = 0; i <50; i++) {
            var delay = AlertForwarder.jitteredBackoff(10);
            assertTrue(delay <= (long) (CAP_MS * 1.5),
                       "attempt 10: delay " + delay + " exceeded jittered cap "
                       + (long) (CAP_MS * 1.5));
            assertTrue(delay >= CAP_MS / 2,
                       "attempt 10: delay " + delay + " below jittered floor " + (CAP_MS / 2));
        }
    }

    @Test
    void jitteredBackoff_introducesActualJitter() {
        // Sample 30 values for the same attempt — they should not all be equal
        var first = AlertForwarder.jitteredBackoff(3);
        var anyDifferent = false;
        for (var i = 0; i <30; i++) {
            if (AlertForwarder.jitteredBackoff(3) != first) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "jitter must produce varying delays across 30 calls");
    }

    private static long sampleMedian(int attempt, int samples) {
        var values = new long[samples];
        for (var i = 0; i <samples; i++) {
            values[i] = AlertForwarder.jitteredBackoff(attempt);
        }
        java.util.Arrays.sort(values);
        return values[samples / 2];
    }
}
