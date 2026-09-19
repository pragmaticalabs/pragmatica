// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


public sealed interface SegmentError extends Cause {
    enum General implements SegmentError {
        SEGMENT_REF_NOT_FOUND("Segment named reference not found in storage"),
        SEGMENT_DATA_NOT_FOUND("Segment data block not found in storage");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// A record whose declared payload length is negative or exceeds the bytes left in its segment. The
    /// segment is corrupt from that record on, so the read fails rather than return what came before it.
    record CorruptRecord(String segment, int position, int length, String message) implements SegmentError {
        static final Fn3<CorruptRecord, String, Integer, Integer> FACTORY = Causes.forThreeValues("Segment %s has a corrupt record at byte position %d: declared payload length %d"
                                                                                                 + " is negative or exceeds the bytes remaining",
                                                                                                  CorruptRecord::new);
    }
}
