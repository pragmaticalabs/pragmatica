// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// #1880 — tells the operator once per node per drain-safety grace window that a young core is held back from retirement.
///
/// The voter-retirement tick runs every second and a young core stays held for the whole grace (minutes), so warning on every
/// tick is noise. The window is the grace itself: a node is young for at most one window, so a node is announced once, and a
/// node that is held again later (a new window) is announced again.
final class HeldCoreWarnings {
    private final Map<NodeId, Long> announcedAtMs = new HashMap<>();
    private final LongSupplier clockMs;
    private final Consumer<String> sink;

    private HeldCoreWarnings(LongSupplier clockMs, Consumer<String> sink) {
        this.clockMs = clockMs;
        this.sink = sink;
    }

    static HeldCoreWarnings heldCoreWarnings(LongSupplier clockMs, Consumer<String> sink) {
        return new HeldCoreWarnings(clockMs, sink);
    }

    /// Passes `message` to the sink unless `node` was already announced within `windowMs`. Synchronized: the check and the stamp are one step.
    synchronized Unit announce(NodeId node, long windowMs, String message) {
        var now = clockMs.getAsLong();
        var withinWindow = Option.option(announcedAtMs.get(node)).filter(last -> now - last < windowMs).isPresent();

        if (!withinWindow) {
            announcedAtMs.put(node, now);
            sink.accept(message);
        }

        return unit();
    }
}
