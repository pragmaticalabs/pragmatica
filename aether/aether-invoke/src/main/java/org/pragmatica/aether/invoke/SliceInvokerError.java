// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public sealed interface SliceInvokerError extends Cause {
    record AllInstancesFailedError(Artifact artifact, MethodName method, String details) implements SliceInvokerError {
        public static AllInstancesFailedError allInstancesFailedError(Artifact artifact,
                                                                      MethodName method,
                                                                      String details) {
            return new AllInstancesFailedError(artifact, method, details);
        }

        @Override
        public String message() {
            return "All instances failed for " + artifact + ":" + method + " - " + details;
        }
    }

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
    /// with the callee's failure otherwise. When the invoker gives the call up (see `SliceInvokerImpl.LATE_COMPLETION_CAPACITY`,
    /// the TTL, a departed target, a stop) it settles as an [OutcomeAbandoned] failure: the outcome stays unknown and
    /// will never be learned. It stays unsettled only while the call is retained and unanswered.
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

    /// #1723: a completion-awaited call that timed out is no longer retained by the invoker (capacity, TTL, its target
    /// node departed, the invoker stopped), so no response can ever resolve it: its outcome will never be learned. It
    /// settles `CompletionUnknown.lateOutcome` as a failure of THIS type, which is not a failure of the call: callers
    /// must not count it as one.
    record OutcomeAbandoned(String reason) implements SliceInvokerError {
        public static OutcomeAbandoned outcomeAbandoned(String reason) {
            return new OutcomeAbandoned(reason);
        }

        @Override
        public String message() {
            return "Outcome never learned: " + reason;
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
