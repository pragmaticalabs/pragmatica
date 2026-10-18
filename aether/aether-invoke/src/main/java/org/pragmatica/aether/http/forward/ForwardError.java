// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.forward;

import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Forward failures that decide the client's status themselves, instead of the generic 504 every other forward
/// failure gets. Both are terminal: no retry, on another node or the same one, can change them.
///
/// The messages are client-facing (each overrides [HttpError#clientMessage()] with its own text): they carry the
/// method, sizes and a fixed description, never the origin's text, which may name nodes or transport internals.
public sealed interface ForwardError extends HttpError, Cause.Terminal {
    @Override
    default String clientMessage() {
        return message();
    }

    /// A non-idempotent request (RFC 9110 §9.2.2) whose forwarded attempt may already have reached the target and
    /// been processed: it timed out, the target answered with a failure, or the target departed mid-wait. Re-sending
    /// it to another node could execute it twice, so it is not re-sent. The origin is the hop failure, kept for the
    /// server log only.
    @SuppressWarnings("JBCT-CAUSE-04")
    record NotRetried(String method, Cause origin, String message) implements ForwardError, Cause.Wrapped {
        static final Fn2<NotRetried, String, Cause> FACTORY = Causes.forTwoValues("%s request was NOT retried on another node: the forwarded attempt did not complete and may already have been processed",
                                                                                  NotRetried::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
    }

    /// The encoded request does not fit one cluster frame, so no node could receive it. Nothing was sent.
    record TooLargeToForward(int requestBytes, String message) implements ForwardError {
        static final Fn1<TooLargeToForward, Integer> FACTORY = Causes.forOneValue("Request too large to forward to the node serving this route (%d bytes encoded)",
                                                                                 TooLargeToForward::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.REQUEST_ENTITY_TOO_LARGE;
        }
    }
}
