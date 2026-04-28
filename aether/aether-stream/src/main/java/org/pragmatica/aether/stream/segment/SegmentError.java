// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Cause;


public sealed interface SegmentError extends Cause {
    enum General implements SegmentError {
        SEGMENT_REF_NOT_FOUND("Segment named reference not found in storage"),
        SEGMENT_DATA_NOT_FOUND("Segment data block not found in storage");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }
}
