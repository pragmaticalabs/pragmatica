// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

/// The ring window of an `entity:` partition: how many entries and bytes its ring holds, how old they may get, and the
/// largest record a write may carry. It lives in this module so the stream manager can size an entity ring without
/// reading retention.
///
/// The ring bounds a takeover fold. A recovering owner replays from its checkpoint forward through its ring and the
/// segments it sealed (#1240), so on that node the window need not cover the checkpoint lag. A node taking the
/// partition over reads only its own ring, so there the window must stay ahead of the checkpoint interval, or the fold
/// refuses rather than serving state missing writes. After the ring is sized from the hot window, this is the window
/// passed to it as the entity hot window, never through retention.
public final class EntityRingWindow {
    /// Ring capacity per entity partition: index slots are allocated UP FRONT at `INDEX_ENTRY_SIZE` (24) bytes each,
    /// so this is a direct per-partition memory cost, multiplied by a keyspace's partition count (64 by default). At 10k
    /// that is ~240 KB per partition and ~15 MB for a default keyspace; the `RetentionPolicy` default of 100k would be
    /// ~154 MB for the same keyspace, which is why this deliberately does not inherit it.
    public static final long CAPACITY = 10_000L;
    /// Data-region CAP, not an up-front allocation: the ring grows one segment at a time toward it.
    public static final long MAX_BYTES = 64L * 1024 * 1024;
    /// Age bound, deliberately far out and deliberately FINITE: entity state has no reason to expire on a clock, but
    /// `evictByAge` computes `now - maxAgeMs`, so `Long.MAX_VALUE` would underflow.
    public static final long MAX_AGE_MS = 365L * 24 * 60 * 60 * 1000;
    /// Largest entity record a write accepts (O19 ruled (c)): 1 MiB. Larger records are refused with `EventTooLarge`.
    public static final int MAX_EVENT_SIZE_BYTES = 1024 * 1024;

    private EntityRingWindow() {}
}
