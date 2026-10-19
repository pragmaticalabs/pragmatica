// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/// #1535 item 7: the refusal and availability scenarios of [CapacityRefusalRecoveryTest] rerun on an injected
/// clock that starts at [PlacementStampAudit#INJECTED_BASE]; the refusal stamp and the retry decision must read it.
class CapacityRefusalRecoveryInjectedClockTest extends CapacityRefusalRecoveryTest {
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
