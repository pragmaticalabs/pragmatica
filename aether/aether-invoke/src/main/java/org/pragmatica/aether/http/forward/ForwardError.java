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


/// Forward failures that decide the client's status and text themselves.
///
/// The statuses and wording follow the 2026-10-07 ruling (know 14a3606152, owner ruling bcfb04232): a forward that
/// may have reached a node answers 503 "outcome unknown, may have been applied; retry only an idempotent operation";
/// a forward that never left this node answers 503 "nothing was applied; safe to retry". A request no cluster frame
/// can carry answers 413.
///
/// The messages are client-facing (each overrides [HttpError#clientMessage()] with its own text): they carry the
/// method, a size or fixed wording, never the origin's text, which may name nodes or transport internals and stays in
/// the server log. [NotRetried] and [TooLargeToForward] are terminal: no further attempt, on any node, may follow.
/// The two exhaustion results are not, so an outer loop that may legitimately re-resolve its target (the task-group
/// path) keeps that freedom for an idempotent request.
public sealed interface ForwardError extends HttpError {
    String OUTCOME_UNKNOWN_TEXT = "outcome unknown, may have been applied; retry only an idempotent operation";
    String NOT_SENT_TEXT = "nothing was applied; safe to retry";

    @Override
    default String clientMessage() {
        return message();
    }

    /// A non-idempotent request (RFC 9110 §9.2.2) whose forwarded attempt may already have reached the target: it
    /// timed out, the target answered with a failure, or the target departed mid-wait. Re-sending it to another node
    /// could execute it twice, so it is not re-sent.
    @SuppressWarnings("JBCT-CAUSE-04")
    record NotRetried(String method, Cause origin, String message) implements ForwardError, Cause.Wrapped, Cause.Terminal {
        static final Fn2<NotRetried, String, Cause> FACTORY = Causes.forTwoValues("%s request was NOT retried on another node: " + OUTCOME_UNKNOWN_TEXT,
                                                                                  NotRetried::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /// Every attempt failed and at least one may have reached a node.
    @SuppressWarnings("JBCT-CAUSE-04")
    record OutcomeUnknown(Cause origin, String message) implements ForwardError, Cause.Wrapped {
        static final Fn1<OutcomeUnknown, Cause> FACTORY = Causes.forOneValue("Forwarded request " + OUTCOME_UNKNOWN_TEXT,
                                                                             OutcomeUnknown::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /// No attempt left this node: no node to send to, the transport refused every write, or the request could not be
    /// encoded or admitted.
    @SuppressWarnings("JBCT-CAUSE-04")
    record NotSent(Cause origin, String message) implements ForwardError, Cause.Wrapped {
        static final Fn1<NotSent, Cause> FACTORY = Causes.forOneValue("Request was not forwarded: " + NOT_SENT_TEXT,
                                                                      NotSent::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
    }

    /// The encoded request does not fit one cluster frame, so no node could receive it. Nothing was sent.
    record TooLargeToForward(int requestBytes, String message) implements ForwardError, Cause.Terminal {
        static final Fn1<TooLargeToForward, Integer> FACTORY = Causes.forOneValue("Request too large to forward to the node serving this route (%d bytes encoded)",
                                                                                  TooLargeToForward::new);

        @Override
        public HttpStatus status() {
            return HttpStatus.REQUEST_ENTITY_TOO_LARGE;
        }
    }
}
