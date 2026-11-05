// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.control;

import org.pragmatica.aether.stream.segment.RetentionEnforcer.SegmentRetentionFloor;


/// The retention floor of a control log (#2590 P1, §5.2 item 3): everything below the oldest live key's latest record
/// may be reclaimed, and nothing else. Read from the committed index only. A partition whose index is not valid, or that
/// this node never built one for, answers `-1`: nothing is reclaimed on a stale or missing index. When the index holds
/// no live key, everything it has applied may go. Not wired into retention in P1; P2 folds it into the stream's floor.
@FunctionalInterface
public interface ControlRetentionFloor extends SegmentRetentionFloor {
    static ControlRetentionFloor controlRetentionFloor(ControlLog controlLog) {
        return controlLog::deletableThroughOffset;
    }
}
