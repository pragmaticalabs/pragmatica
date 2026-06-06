// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceAddress;


/// Policy guard for stream lifecycle events.
///
/// Spec §13: lifecycle events whose subject lives under the `system` namespace must NOT be
/// produced into `system:cluster-events` (or any other system-namespace stream). Without this
/// filter, registering `system:cluster-events:1.0.0` would produce a `STREAM_REGISTERED` event
/// describing the registration of `system:cluster-events:1.0.0` and publish it into the same
/// stream — a self-referential loop.
///
/// Application-namespace stream lifecycle events are produced normally.
///
/// Callers that emit `STREAM_REGISTERED`/`STREAM_DELETED` (and analogous lifecycle events) must
/// gate their emit with [#shouldEmit(ResourceAddress)]. The lifecycle event types themselves are
/// added in the emission reconciliation PR; this guard is in place ahead of them so the
/// circular-dep prevention rule has a single, discoverable home.
public final class StreamLifecycleEventPolicy {
    private StreamLifecycleEventPolicy() {}

    /// Returns true when a lifecycle event for `address` should be emitted into the cluster
    /// event stream. False when `address` is in the system namespace.
    public static boolean shouldEmit(ResourceAddress address) {
        return !address.isSystem();
    }
}
