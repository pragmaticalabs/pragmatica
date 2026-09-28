// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.AppendLog;

import static org.pragmatica.lang.Unit.unit;


/// Stores a sealed segment. Contract (#1234): the returned promise succeeds only once the segment is
/// durably stored AND readable — [StorageSegmentSink] updates the index before it resolves — because
/// [SegmentSealer] drops its retained copy in a continuation of that promise.
///
/// `log` is the partition's append log when it has one. A sink that stores into a storage instance seals
/// through [org.pragmatica.storage.StorageInstance#seal] with it, which is the ONLY way the log's records
/// for the segment's range become truncatable (#1567); a sink that ignores it leaves the log holding them.
@FunctionalInterface
public interface SegmentSink {
    Promise<Unit> seal(SealedSegment segment, Option<AppendLog> log);

    /// Store `segment` for a partition without a log: nothing becomes truncatable.
    default Promise<Unit> seal(SealedSegment segment) {
        return seal(segment, Option.none());
    }

    SegmentSink DISCARD = (_, _) -> Promise.success(unit());
}
