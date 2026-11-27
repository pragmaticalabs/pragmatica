// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723: the forward errors are `Cause.Wrapped` and `HttpError` at once; their declared client text is their own fixed
/// message, never the text a wrapper derives from a hidden origin. The origin here is a caught throwable, whose client
/// text is "Internal error": a `Cause.Wrapped` default taking over would show that instead of the declared wording.
class ForwardErrorClientMessageTest {
    private static final String SECRET = "SENTINEL-forward-3723-secret";

    private static Cause thrown() {
        return Causes.fromThrowable(new IllegalStateException(SECRET));
    }

    @Test
    void notSent_overAThrowable_answersItsDeclaredText() {
        var error = ForwardError.NotSent.notSent(thrown());

        assertThat(HttpError.clientMessage(error)).isEqualTo(error.message())
                                                  .contains(ForwardError.NOT_SENT_TEXT)
                                                  .doesNotContain(SECRET);
    }

    @Test
    void outcomeUnknown_overAThrowable_answersItsDeclaredText() {
        var error = ForwardError.OutcomeUnknown.FACTORY.apply(thrown());

        assertThat(HttpError.clientMessage(error)).isEqualTo(error.message())
                                                  .contains(ForwardError.OUTCOME_UNKNOWN_TEXT)
                                                  .doesNotContain(SECRET);
    }

    @Test
    void notRetried_overAThrowable_answersItsDeclaredText() {
        var error = ForwardError.NotRetried.FACTORY.apply("POST", thrown());

        assertThat(HttpError.clientMessage(error)).isEqualTo(error.message())
                                                  .contains(ForwardError.OUTCOME_UNKNOWN_TEXT)
                                                  .doesNotContain(SECRET);
    }
}
