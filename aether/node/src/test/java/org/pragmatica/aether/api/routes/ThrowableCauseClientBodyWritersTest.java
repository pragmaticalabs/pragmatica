// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.util.List;

import org.pragmatica.aether.api.ManagementApiResponses.BlueprintValidationResponse;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3723: the writers that put a `Cause` into a client body other than through `SliceRouter`/`ResponseWriter` -- the
/// four [ManagementServerError] variants that echo a cause, the per-item outcome of a batch publish, and the blueprint
/// validation response -- render a caught throwable (whose `message()` is its stack trace) as the fixed text. Each case
/// has a control: the cause's own `message()` DOES carry the sentinel and a trace line, so a pass is not an empty one.
class ThrowableCauseClientBodyWritersTest {
    private static final String SENTINEL = "SENTINEL-3723-writers";

    private static Cause thrown() {
        return Causes.fromThrowable(new IllegalStateException(SENTINEL));
    }

    private static void assertClean(String text) {
        assertThat(text).contains(HttpError.INTERNAL_ERROR_TEXT)
                        .doesNotContain(SENTINEL)
                        .doesNotContain("IllegalStateException")
                        .doesNotContain("at java")
                        .doesNotContain("\tat ");
    }

    @Test
    void control_theCauseMessageIsTheStackTrace() {
        assertThat(thrown().message()).contains(SENTINEL).contains("\tat ");
    }

    @Test
    void streamRefused_message_hidesTheTrace() {
        assertClean(new ManagementServerError.StreamRefused(HttpStatus.CONFLICT, thrown()).message());
    }

    @Test
    void keylessPublishOutcomeUnknown_message_hidesTheTrace() {
        assertClean(new ManagementServerError.KeylessPublishOutcomeUnknown(thrown()).message());
    }

    @Test
    void publishRetryable_message_hidesTheTrace() {
        assertClean(new ManagementServerError.PublishRetryable("s", thrown()).message());
    }

    @Test
    void streamInfoUnavailable_message_hidesTheTrace() {
        assertClean(new ManagementServerError.StreamInfoUnavailable("s", 0, thrown()).message());
    }

    @Test
    void streamRefused_overACompositeWithAThrowable_hidesTheTrace() {
        var composite = Causes.composite(Causes.cause("plain-member").result(), thrown().result());
        var message = new ManagementServerError.StreamRefused(HttpStatus.CONFLICT, composite).message();

        assertClean(message);
        assertThat(message).contains("plain-member");
    }

    @Test
    void publishItemOutcome_outcomeUnknown_hidesTheTrace() throws Exception {
        assertClean(itemOutcome(new PublishOutcome.OutcomeUnknown(thrown())).cause().or(""));
    }

    @Test
    void publishItemOutcome_notAttempted_hidesTheTrace() throws Exception {
        assertClean(itemOutcome(new PublishOutcome.NotAttempted(thrown())).cause().or(""));
    }

    @Test
    void blueprintValidation_failedResponse_hidesTheTrace() throws Exception {
        var method = SliceRoutes.class.getDeclaredMethod("failedValidationResponse", Cause.class, List.class);

        method.setAccessible(true);

        var response = (BlueprintValidationResponse) method.invoke(null, thrown(), List.of());

        assertThat(response.valid()).isFalse();
        assertClean(String.join("\n", response.errors()));
    }

    private static StreamApiRoutes.PublishItemOutcome itemOutcome(PublishOutcome outcome) throws Exception {
        Method method = StreamApiRoutes.class.getDeclaredMethod("itemOutcome", int.class, PublishOutcome.class);

        method.setAccessible(true);

        return (StreamApiRoutes.PublishItemOutcome) method.invoke(null, 0, outcome);
    }
}
