// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
}
