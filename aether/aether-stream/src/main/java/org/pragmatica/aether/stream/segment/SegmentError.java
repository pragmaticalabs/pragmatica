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

    /// A segment that is corrupt from the record at `position` on, so the read fails rather than return
    /// what came before it. Either the record declares a payload `length` that is negative or exceeds the
    /// bytes left ([#FACTORY]), or the segment ends inside a record header, and `length` is how many of
    /// its header bytes are present ([#TRUNCATED_HEADER]).
    record CorruptRecord(String segment, int position, int length, String message) implements SegmentError {
        static final Fn3<CorruptRecord, String, Integer, Integer> FACTORY = Causes.forThreeValues("Segment %s has a corrupt record at byte position %d: declared payload length %d"
                                                                                                 + " is negative or exceeds the bytes remaining",
                                                                                                  CorruptRecord::new);

        static final Fn3<CorruptRecord, String, Integer, Integer> TRUNCATED_HEADER = Causes.forThreeValues("Segment %s ends in a truncated record header at byte position %d: only %d header bytes"
                                                                                                          + " are present",
                                                                                                           CorruptRecord::new);
    }
}
