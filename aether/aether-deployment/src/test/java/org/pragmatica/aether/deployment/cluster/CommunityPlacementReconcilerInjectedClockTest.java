// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/// #1535 item 7: every scenario of [CommunityPlacementReconcilerTest] rerun on an injected clock that starts at
/// [PlacementStampAudit#INJECTED_BASE] and ticks one millisecond per read. A deadline or stamp that still read
/// the wall clock would either fail the scenario or be flagged by the audit after it.
class CommunityPlacementReconcilerInjectedClockTest extends CommunityPlacementReconcilerTest {
    private final AtomicLong ticks = new AtomicLong(PlacementStampAudit.INJECTED_BASE);

    @Override
    boolean injectedClock() {
        return true;
    }

    @Override
    LongSupplier clock() {
        return () -> ticks.incrementAndGet();
    }
}
