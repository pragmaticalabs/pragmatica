// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

/// Per-incarnation discriminator carried on the metrics heartbeat pong
/// (membership-architecture-v2-spec §7.5.3). The leader keys its in-memory readiness
/// view by `(NodeId, incarnation)` so it can reject a stale prior-incarnation pong and
/// never misattribute a fast-restart `DRAINING → SYNCING` flip to one continuous life.
///
/// **Source decision.** SWIM owns a real per-incarnation counter, but it is neither
/// exposed as a node-local self-accessor (`SwimProtocol.announceJoin` takes incarnation
/// as a caller-supplied parameter) nor reachable from the `aether-metrics` module
/// (which does not depend on `swim`). Rather than introduce a cross-module dependency,
/// `aether-metrics` carries a `long` on the wire sourced from an injected `LongSupplier`
/// (`ClusterSyncCollector.setIncarnationSupplier`). [BootEpoch] is the default supplier:
/// a `long` captured ONCE at process start via `System.nanoTime()`, monotonic and
/// distinct across restarts of the same `NodeId`. `AetherNode` may re-wire the supplier
/// to the real SWIM incarnation when it wires the node; until then this default holds.
public sealed interface BootEpoch {
    long VALUE = System.nanoTime();

    /// The process-wide boot epoch — a `long` captured once at interface-init (process
    /// start). Stable for the lifetime of this JVM; changes on restart.
    static long bootEpoch() {
        return VALUE;
    }

    record unused() implements BootEpoch {}
}
