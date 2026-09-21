// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import org.pragmatica.aether.resource.projection.Projection;
import org.pragmatica.aether.resource.projection.ProjectionStore.ReplayStatus;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The node's type-erased view of one attached [Projection] (#1333): what the cursor hook reports
/// into, what the rebuild route drives, and what the groups route reads. Every call is lifted at the
/// caller — the projection is slice code and may throw.
public interface ProjectionHandle {
    String projectionName();
    Promise<Unit> onCursorCommitted(RewindToken token, int partition, long committedCursor);
    Promise<Unit> rebuild();
    Promise<ReplayStatus> replayStatus();

    static <S, T> ProjectionHandle projectionHandle(Projection<S, T> projection) {
        record projectionHandle <S, T>(Projection<S, T> projection) implements ProjectionHandle {
            @Override
            public String projectionName() {
                return projection.name();
            }

            @Override
            public Promise<Unit> onCursorCommitted(RewindToken token, int partition, long committedCursor) {
                return projection.onCursorCommitted(token, partition, committedCursor);
            }

            @Override
            public Promise<Unit> rebuild() {
                return projection.rebuild();
            }

            @Override
            public Promise<ReplayStatus> replayStatus() {
                return projection.store()
                                 .replayStatus();
            }
        }

        return new projectionHandle <>(projection);
    }
}
