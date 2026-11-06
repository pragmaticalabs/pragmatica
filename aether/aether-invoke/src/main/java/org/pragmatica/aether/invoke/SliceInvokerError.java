// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public sealed interface SliceInvokerError extends Cause {
    record InvocationError(Artifact artifact, MethodName method, Cause cause) implements SliceInvokerError {
        public static InvocationError invocationError(Artifact artifact, MethodName method, Cause cause) {
            return new InvocationError(artifact, method, cause);
        }

        @Override
        public String message() {
            return "Invocation failed for " + artifact + ":" + method + ": " + cause.message();
        }
    }

    /// #1723: a REMOTE call that asked the callee for its completion got no response within the invocation timeout. The
    /// outcome is UNKNOWN, not a failure: the callee may not have received the request, may be running it still, or
    /// may have completed it with the response lost. Callers that record outcomes (the scheduler) must not count it as
    /// either an execution or a failure.
    ///
    /// `lateOutcome` settles if the callee's response arrives LATE: with success when the callee completed the call,
    /// with the callee's failure otherwise. It never settles when no response arrives, or when the invoker no longer
    /// retains the call (see `SliceInvokerImpl.LATE_COMPLETION_CAPACITY`): the outcome then stays unknown.
    record CompletionUnknown(Artifact artifact, MethodName method, Cause timeout, Promise<Unit> lateOutcome) implements SliceInvokerError {
        public static CompletionUnknown completionUnknown(Artifact artifact,
                                                          MethodName method,
                                                          Cause timeout,
                                                          Promise<Unit> lateOutcome) {
            return new CompletionUnknown(artifact, method, timeout, lateOutcome);
        }

        /// An unknown outcome that no late response will resolve.
        public static CompletionUnknown completionUnknown(Artifact artifact, MethodName method, Cause timeout) {
            return new CompletionUnknown(artifact, method, timeout, Promise.promise());
        }

        @Override
        public String message() {
            return "Outcome unknown for " + artifact
                 + ":" + method
                 + ": no response within the invocation timeout (" + timeout.message()
                 + ")";
        }
    }

    /// #2807: the target node departed while the call waited for its response. The request was handed to the transport,
    /// so it may have run there: the outcome is UNKNOWN, and the invoker never re-sends it to another instance, because a
    /// non-idempotent call must not run twice.
    record TargetDeparted(NodeId targetNode) implements SliceInvokerError {
        public static TargetDeparted targetDeparted(NodeId targetNode) {
            return new TargetDeparted(targetNode);
        }

        @Override
        public String message() {
            return "Target node " + targetNode + " departed after the request was sent: outcome unknown, not re-sent";
        }
    }

    /// #2807: every instance the typed call could be routed to refused the request at the transport, so this call
    /// reached no callee. It is a failure of THIS slice-to-slice hop only: it is deliberately not an HttpError and keeps
    /// the refusal out of `source()`, so a route that returns it makes no claim about the caller's own effects (the
    /// outer handler may have applied some before it made this call) and never tells a client to retry.
    record NoInstanceAccepted(Artifact artifact, MethodName method, Cause refusal) implements SliceInvokerError {
        public static NoInstanceAccepted noInstanceAccepted(Artifact artifact, MethodName method, Cause refusal) {
            return new NoInstanceAccepted(artifact, method, refusal);
        }

        @Override
        public String message() {
            return "No instance of " + artifact + ":" + method + " accepted the request: " + refusal.message();
        }
    }

    record NoEndpointsError(Artifact artifact, MethodName method) implements SliceInvokerError, Cause.Transient {
        public static NoEndpointsError noEndpointsError(Artifact artifact, MethodName method) {
            return new NoEndpointsError(artifact, method);
        }

        @Override
        public String message() {
            return "No endpoints available for " + artifact + ":" + method;
        }
    }

    record MethodHandleError(String artifact, String method, String reason) implements SliceInvokerError {
        public static MethodHandleError methodHandleError(String artifact, String method, String reason) {
            return new MethodHandleError(artifact, method, reason);
        }

        @Override
        public String message() {
            return "Failed to create method handle for " + artifact + ":" + method + " - " + reason;
        }
    }

    record SerializationError(String details) implements SliceInvokerError {
        public static SerializationError serializationError(String details) {
            return new SerializationError(details);
        }

        @Override
        public String message() {
            return "Serialization error: " + details;
        }
    }

    record TimeoutError(Artifact artifact, MethodName method, long timeoutMs) implements SliceInvokerError, Cause.Transient {
        public static TimeoutError timeoutError(Artifact artifact, MethodName method, long timeoutMs) {
            return new TimeoutError(artifact, method, timeoutMs);
        }

        @Override
        public String message() {
            return "Timeout after " + timeoutMs + "ms waiting for " + artifact + ":" + method;
        }
    }

    record RemoteInvocationError(String errorMessage) implements SliceInvokerError {
        public static RemoteInvocationError remoteInvocationError(String errorMessage) {
            return new RemoteInvocationError(errorMessage);
        }

        @Override
        public String message() {
            return "Remote invocation failed: " + errorMessage;
        }
    }
}
