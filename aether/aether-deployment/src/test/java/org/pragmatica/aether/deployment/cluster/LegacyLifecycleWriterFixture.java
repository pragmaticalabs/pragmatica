// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/// Test-only fixture that synthesises a [`LifecycleWriter`] from a raw command-applier and a
/// lifecycle-reader. Production code MUST NOT use this — `MembershipFsm` is the sole
/// production writer of `NodeLifecycleKey` (post-R8 single-writer rule).
///
/// This fixture exists so that legacy [`ClusterTopologyManager`] unit tests can drive the CTM
/// without spinning up a full MembershipFsm. Each request is translated into a direct
/// `KVCommand.Put<NodeLifecycleKey, NodeLifecycleValue>`, mirroring the behaviour of the
/// in-production MembershipFsm closely enough for these tests to exercise the CTM's reaction
/// to KV transitions.
final class LegacyLifecycleWriterFixture {
    private LegacyLifecycleWriterFixture() {
    }

    static LifecycleWriter create(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                  Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                  LongSupplier clock) {
        return new LifecycleWriter() {
            @Override public Promise<Unit> requestDrain(NodeId target) {
                return write(target, NodeLifecycleState.DRAINING, commandApplier, lifecycleReader, clock);
            }

            @Override public Promise<Unit> requestDecommission(NodeId target) {
                return write(target, NodeLifecycleState.DECOMMISSIONED, commandApplier, lifecycleReader, clock);
            }

            @Override public Promise<Unit> requestActivate(NodeId target) {
                return write(target, NodeLifecycleState.ON_DUTY, commandApplier, lifecycleReader, clock);
            }

            @Override public Promise<Unit> requestFailedDrain(NodeId target) {
                return write(target, NodeLifecycleState.FAILED_DRAIN, commandApplier, lifecycleReader, clock);
            }
        };
    }

    private static NodeLifecycleValue valueFor(NodeLifecycleState state,
                                                Option<NodeLifecycleValue> prior,
                                                long nowMs) {
        if (prior.isEmpty()) {
            return state == NodeLifecycleState.DRAINING
                   ? NodeLifecycleValue.nodeLifecycleValue(state, "", 0, Epoch.ZERO)
                   : NodeLifecycleValue.nodeLifecycleValue(state, "", 0, ProvisioningSource.CTM);
        }
        var p = prior.unwrap();
        return NodeLifecycleValue.nodeLifecycleValue(state,
                                                      nowMs,
                                                      p.host(),
                                                      p.port(),
                                                      p.observedCoreEpoch(),
                                                      p.transitionedAt(),
                                                      p.provisioningSource());
    }

    @SuppressWarnings("unchecked")
    private static Promise<Unit> write(NodeId nodeId,
                                       NodeLifecycleState state,
                                       Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                       Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                       LongSupplier clock) {
        var value = valueFor(state, lifecycleReader.apply(nodeId), clock.getAsLong());
        var command = (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(
                NodeLifecycleKey.nodeLifecycleKey(nodeId), value);
        return commandApplier.apply(List.of(command)).mapToUnit();
    }
}
