// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.aether.stream.control.ControlLogError;
import org.pragmatica.aether.stream.control.ControlOutcome;
import org.pragmatica.serialization.Codec;


/// One record's [ControlOutcome] on the wire of the forwarded batch-append (#2590 P1). A cause crosses as its message
/// and, for a refusal, whether the owner classed it retryable ([ControlOutcome#isLocalOnly] on the owner); the sender
/// rebuilds a refusal as [ControlLogError.RemoteRefused] and an unknown outcome as [StreamForwardError.RemotePublishFailed].
@Codec
public record ControlOutcomeDto(Kind kind, long offset, String message, boolean retryable) {
    /// The ordinal is the encoding: append only, never reorder. `UNKNOWN` is what a newer sender's kind decodes to.
    @Codec
    public enum Kind {
        ADMITTED,
        REFUSED,
        OUTCOME_UNKNOWN,
        INDEX_UNAVAILABLE,
        UNKNOWN
    }

    public static ControlOutcomeDto fromOutcome(ControlOutcome outcome) {
        return switch (outcome) {
            case ControlOutcome.Admitted(var offset) -> new ControlOutcomeDto(Kind.ADMITTED, offset, "", false);
            case ControlOutcome.Refused(var cause) -> new ControlOutcomeDto(Kind.REFUSED,
                                                                            - 1L,
                                                                            cause.message(),
                                                                            outcome.isLocalOnly());
            case ControlOutcome.OutcomeUnknown(var cause) -> new ControlOutcomeDto(Kind.OUTCOME_UNKNOWN,
                                                                                   - 1L,
                                                                                   cause.message(),
                                                                                   true);
            case ControlOutcome.IndexUnavailable _ -> new ControlOutcomeDto(Kind.INDEX_UNAVAILABLE, -1L, "", true);
        };
    }

    public ControlOutcome toOutcome() {
        return switch (kind) {
            case ADMITTED -> new ControlOutcome.Admitted(offset);
            case REFUSED -> new ControlOutcome.Refused(ControlLogError.RemoteRefused.FACTORY.apply(retryable, message));
            case OUTCOME_UNKNOWN -> new ControlOutcome.OutcomeUnknown(new StreamForwardError.RemotePublishFailed(message));
            case INDEX_UNAVAILABLE -> ControlOutcome.IndexUnavailable.INDEX_UNAVAILABLE;
            // An outcome this node cannot read is never an acknowledgement: the record may have been appended.
            case UNKNOWN -> new ControlOutcome.OutcomeUnknown(new StreamForwardError.RemotePublishFailed("an outcome kind this node does not know: " + message));
        };
    }
}
