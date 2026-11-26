// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.api.routes.NodeLifecycleRoutes.DrainPort;
import org.pragmatica.aether.deployment.cluster.DrainGate;
import org.pragmatica.aether.deployment.cluster.DrainSink;
import org.pragmatica.aether.deployment.cluster.FencePort;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;

/// Test-only seam (#3754 lane b2): the real [FencedDrainSink] (package-private) over a port the test scripts, wired into a [DrainPort]
/// exactly as `DrainAssembly#drainPort` wires it (`request` through [DrainGate#always()], `pending` and `inFlight` the sink's), so
/// a test in another package can drive the operator routes through the real sink. `DrainAssembly#drainPort` itself fixes the unfenced
/// port, so it cannot be scripted.
public final class ScriptedFencedDrain {
    private ScriptedFencedDrain() {}

    /// The sink, and the port the routes take.
    public record Assembled(DrainPort port, DrainSink sink) {
        public static Assembled assembled(DrainPort port, DrainSink sink) {
            return new Assembled(port, sink);
        }
    }

    public static Assembled assemble(FencePort fencePort,
                                     Consumer<NodeId> registryAdd,
                                     Supplier<Set<NodeId>> drainTargets,
                                     AtomicReference<MembershipFsm> membershipFsmRef) {
        var sink = FencedDrainSink.fencedDrainSink(fencePort, registryAdd, drainTargets, membershipFsmRef);
        var port = DrainPort.registry(target -> sink.request(target,
                                                             DrainGate.always()),
                                      sink::pending,
                                      sink::inFlight);

        return Assembled.assembled(port, sink);
    }
}
