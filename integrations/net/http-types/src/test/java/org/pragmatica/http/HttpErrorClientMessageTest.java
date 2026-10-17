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
}
