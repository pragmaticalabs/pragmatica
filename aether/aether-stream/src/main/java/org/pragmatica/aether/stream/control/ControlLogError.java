// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// The failures of the control log itself (#2590 P1). An admission predicate's own refusals are its kind's business and
/// reach the writer unchanged inside [ControlOutcome.Refused].
public sealed interface ControlLogError extends Cause {
    enum General implements ControlLogError {
        CARRY_NOT_WRITABLE("A CARRY record is written by the partition owner only"),
        CARRY_SUPERSEDED("The carried record is no longer the latest record of its key"),
        INDEX_UNAVAILABLE("The control index of this partition is not valid yet; retry"),
        FORWARD_UNSUPPORTED("This node's forward client cannot forward a control append"),
        FORWARD_TIMEOUT("The forwarded control append was not answered in time; its outcome is unknown");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// A record of a named kind no [Admission] is registered for: nobody can judge it, so it is refused.
    record UnknownKind(String kind, String message) implements ControlLogError {
        static final Fn1<UnknownKind, String> FACTORY = Causes.forOneValue("No admission is registered for control kind %s",
                                                                           UnknownKind::new);
    }

    /// A payload carrying the control tag that does not decode: corruption, or a framing version this node does not know.
    record MalformedRecord(String detail, String message) implements ControlLogError {
        static final Fn1<MalformedRecord, String> FACTORY = Causes.forOneValue("Malformed control record: %s",
                                                                               MalformedRecord::new);
    }

    /// A read of the retained log that did not continue at the offset it asked for: a hole, such as a replica missing
    /// sealed segments below its ring. The index is not built over it.
    record IndexHole(Long expected, Long found, String message) implements ControlLogError {
        static final Fn2<IndexHole, Long, Long> FACTORY = Causes.forTwoValues("Control log read expected offset %s and found %s: the retained log has a hole",
                                                                              IndexHole::new);
    }

    /// The owner refused a forwarded record. Only the owner's text and its retryable classification cross the wire.
    record RemoteRefused(Boolean retryable, String reason, String message) implements ControlLogError {
        public static final Fn2<RemoteRefused, Boolean, String> FACTORY = Causes.forTwoValues("The owner refused the control record (retryable: %s): %s",
                                                                                              RemoteRefused::new);
    }
}
