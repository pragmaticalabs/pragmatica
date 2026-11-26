// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #2101: [HttpError#message()] walks the origin chain (server-side log text); [HttpError#clientMessage()] is the
/// client-facing text and stops at the top cause.
class HttpErrorClientMessageTest {
    private static final String SENTINEL = "SENTINEL-origin-detail";

    record Wrapped(String message, Cause origin) implements Cause {
        @Override
        public Option<Cause> source() {
            return Option.some(origin);
        }
    }

    private static HttpError chained() {
        return HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR,
                                   new Wrapped("top", Causes.cause(SENTINEL)));
    }

    @Test
    void message_walksChain_control() {
        assertThat(chained().message()).contains("top").contains(SENTINEL);
    }

    @Test
    void clientMessage_keepsTopCauseOnly() {
        assertThat(chained().clientMessage()).contains("top").doesNotContain(SENTINEL);
    }

    @Test
    void clientMessage_ofNestedHttpError_dropsInnerChain() {
        var nested = HttpError.httpError(HttpStatus.BAD_GATEWAY, chained());

        assertThat(nested.clientMessage()).contains("top").doesNotContain(SENTINEL);
    }

    @Test
    void clientMessage_ofPlainCause_isItsMessage() {
        assertThat(HttpError.clientMessage(Causes.cause("plain"))).isEqualTo("plain");
    }

    /// An implementer that walks a chain in `message()` and does not opt in to a wider client text.
    record Walker(HttpStatus status, Cause origin) implements HttpError {
        @Override
        public String message() {
            return status().message() + ": " + origin().message() + " <- " + origin().source().map(Cause::message).or("");
        }
    }

    @Test
    void clientMessage_defaultIsStatusTextOnly() {
        var walker = new Walker(HttpStatus.BAD_GATEWAY, new Wrapped("top", Causes.cause(SENTINEL)));

        assertThat(walker.message()).contains(SENTINEL);
        assertThat(walker.clientMessage()).isEqualTo("Bad Gateway");
    }

    @Test
    void problemDetail_ofNestedHttpErrorOrigin_omitsInnerChain() {
        var nested = HttpError.httpError(HttpStatus.BAD_GATEWAY, chained());
        var detail = ProblemDetail.fromHttpError(nested, "/x", "req").detail().or("");

        assertThat(nested.message()).contains(SENTINEL);
        assertThat(detail).contains("top").doesNotContain(SENTINEL);
    }

    @Test
    void problemDetail_fromCause_ofChainedHttpError_omitsInnerChain() {
        var detail = ProblemDetail.fromCause(chained(), "/x", "req").detail().or("");

        assertThat(detail).contains("top").doesNotContain(SENTINEL);
    }

    private static final String THROWN = "SENTINEL-3723-throwable-text";

    private static Cause thrown() {
        return Causes.fromThrowable(new IllegalStateException(THROWN));
    }

    /// #3723 control: the throwable cause's `message()` is unchanged, a stack trace with the sentinel, for logs and callers.
    @Test
    void throwableCause_messageStaysTheStackTrace_control() {
        assertThat(thrown()).isInstanceOf(Causes.ThrowableCause.class);
        assertThat(thrown().message()).contains(THROWN).contains("IllegalStateException").contains("\tat ");
    }

    @Test
    void clientMessage_ofThrowableCause_isTheFixedText() {
        assertThat(HttpError.clientMessage(thrown())).isEqualTo("Internal error");
    }

    @Test
    void clientMessage_ofHttpErrorOverThrowable_isStatusAndFixedText() {
        var wrapped = HttpError.httpError(HttpStatus.INTERNAL_SERVER_ERROR, thrown());

        assertThat(wrapped.message()).contains(THROWN);
        assertThat(wrapped.clientMessage()).isEqualTo("Internal Server Error: Internal error");
        assertThat(HttpError.clientMessage(wrapped)).isEqualTo("Internal Server Error: Internal error");
    }

    @Test
    void clientMessage_ofCompositeWithThrowableMember_hidesOnlyTheThrowable() {
        var composite = Causes.composite(Causes.cause("plain-member").result(), thrown().result());

        assertThat(composite.message()).contains(THROWN);
        assertThat(HttpError.clientMessage(composite)).isEqualTo("Composite:\n  plain-member\n  Internal error");
    }

    @Test
    void clientMessage_ofHttpErrorOverCompositeWithThrowable_hidesTheThrowable() {
        var wrapped = HttpError.httpError(HttpStatus.SERVICE_UNAVAILABLE,
                                          Causes.composite(thrown().result(), Causes.cause("plain-member").result()));

        assertThat(HttpError.clientMessage(wrapped)).doesNotContain(THROWN).doesNotContain("\tat ").contains("plain-member");
    }

    @Test
    void problemDetail_ofThrowableCause_carriesTheFixedTextOnly() {
        var detail = ProblemDetail.fromCause(thrown(), "/x", "req").detail().or("");

        assertThat(detail).isEqualTo("Internal error");
    }

    @Test
    void throwableCause_rendersAndClassifiesLikeABareCause() {
        var one = Causes.ThrowableCause.throwableCause(new IllegalStateException(THROWN));

        assertThat(one.toString()).startsWith("Cause: \n  ").contains(THROWN);
        assertThat(one.source().isPresent()).isFalse();
        assertThat(one.isTerminal()).isFalse();
        assertThat(one.isTransient()).isFalse();
    }
}
