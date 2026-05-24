// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandApplied;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandReceived;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;


/// Regression for the dead-reconciler bug (session 2026-05-24): the deployment-module audit
/// events are registered with the SYSTEM-LEVEL node serializer (`NodeCodecs`), not via the
/// slice-DI `resources.toml` path — the deployment module is not a slice. The
/// `audit.lifecycle.commands` `StreamPublisher` used by `LifecycleWriter` / the
/// `LifecycleReconciler` serializes these events on every reconciler tick. A missing
/// registration throws `IllegalArgumentException: No codec registered` — a by-design fatal
/// guard in `Serializer` (dev/test safety) that aborts the tick and silently stops automatic
/// cleanup. This test fails fast if the registration is ever dropped.
class NodeCodecsAuditLifecycleEventTest {
    private final SliceCodec codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    @Test
    void nodeCodecs_commandReceived_roundTripsThroughSystemSerializer() {
        var event = new CommandReceived("ForceDecommission",
                                        "node-2",
                                        "FORCED",
                                        "operator decommission",
                                        CommandLifecycleEvent.SOURCE_RECONCILER,
                                        1234L);
        CommandLifecycleEvent decoded = codec.decode(codec.encode(event));
        assertEquals(event, decoded);
    }

    @Test
    void nodeCodecs_commandApplied_roundTripsThroughSystemSerializer() {
        var event = new CommandApplied("ForceDecommission",
                                       "node-2",
                                       "FORCED",
                                       "operator decommission",
                                       CommandLifecycleEvent.SOURCE_RECONCILER,
                                       5678L,
                                       true);
        CommandLifecycleEvent decoded = codec.decode(codec.encode(event));
        assertEquals(event, decoded);
    }
}
