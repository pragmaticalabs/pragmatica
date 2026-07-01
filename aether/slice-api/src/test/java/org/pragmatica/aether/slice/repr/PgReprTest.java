// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.repr;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the pure [PgRepr] descriptor: `lower` unwraps totally, `lift` re-parses fallibly,
/// and `trusted` produces an always-succeeding decode.
class PgReprTest {
    private static final Cause OUT_OF_RANGE = Causes.cause("score out of range");

    record Score(int value) {
        static Result<Score> score(int raw) {
            return Result.success(raw)
                         .filter(OUT_OF_RANGE, candidate -> candidate >= 0 && candidate <= 100)
                         .map(Score::new);
        }

        static PgRepr<Score, Integer> pgRepr() {
            return PgRepr.of(Score::value, Score::score);
        }
    }

    @Test
    void lower_unwrapsValueObjectToColumnValue() {
        assertThat(Score.pgRepr().lower().apply(new Score(42))).isEqualTo(42);
    }

    @Test
    void lift_reconstructsValueObject_forValidColumnValue() {
        Score.pgRepr()
             .lift()
             .apply(42)
             .onFailure(cause -> Assertions.fail(cause.message()))
             .onSuccess(score -> assertThat(score.value()).isEqualTo(42));
    }

    @Test
    void lift_fails_forInvalidColumnValue() {
        Score.pgRepr()
             .lift()
             .apply(200)
             .onSuccess(score -> Assertions.fail("expected failure but decoded " + score));
    }

    @Test
    void of_roundTripsThroughLowerAndLift() {
        var original = new Score(7);
        var repr = Score.pgRepr();

        repr.lift()
            .apply(repr.lower().apply(original))
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(restored -> assertThat(restored).isEqualTo(original));
    }

    @Test
    void trusted_neverFails_evenForInvalidColumnValue() {
        var trusted = PgRepr.trusted(Score::value, Score::new);

        trusted.lift()
               .apply(200)
               .onFailure(cause -> Assertions.fail(cause.message()))
               .onSuccess(score -> assertThat(score.value()).isEqualTo(200));
    }
}
