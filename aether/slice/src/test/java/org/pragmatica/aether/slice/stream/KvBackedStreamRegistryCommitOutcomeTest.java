// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;


/// #968: [KvBackedStreamRegistry#register] must report the outcome of the consensus `Put` that
/// carries the catalog entry, not the outcome of *issuing* it. Before the fix it fired
/// `cluster.apply`, logged the failure and returned `Result.success(entry)` unconditionally — the
/// log-and-drop shape that turns a refused write into a green response one caller up
/// (`POST /streams` answered `"created"` for an entry that never committed). The sibling
/// `KvBackedStreamRegistryTest` drives an always-succeeding cluster stub and so cannot see this.
class KvBackedStreamRegistryCommitOutcomeTest {
    private static final ResourceAddress APP_ORDERS = resourceAddress("com.example.app:orders:1.0.0").unwrap();
    private static final Cause CONSENSUS_REFUSED = Causes.cause("Node is inactive");

    @Test
    void register_consensusApplyFails_returnsTheFailureNotSuccess() {
        var registry = new KvBackedStreamRegistry(refusingClusterNode(), emptyStore());

        var result = registry.register(operatorEntry());

        result.onSuccess(entry -> fail("register must not report success for a catalog put consensus refused: " + entry));
        result.onFailure(cause -> assertThat(cause.message()).contains(CONSENSUS_REFUSED.message()));
    }

    /// The registrar's bootstrap leg latches DONE on a successful `bootstrap()` and never retries a
    /// latched leg — so a swallowed apply failure here is a permanently missing `system:*` catalog
    /// entry. The leg must see the failure to retry it.
    @Test
    void bootstrap_consensusApplyFails_isNotReportedRegistered() {
        var registry = new KvBackedStreamRegistry(refusingClusterNode(), emptyStore());

        var result = new SystemStreamBootstrap(registry).bootstrap();

        result.onSuccess(entries -> fail("bootstrap must not report the system streams registered when the put was refused: " + entries));
        assertThat(result.isFailure()).isTrue();
    }

    /// Control for the two above: the same code path with an accepting cluster still registers, so
    /// the failures are attributable to the refusal and not to the fixture.
    @Test
    void register_consensusApplyAccepts_stillSucceeds() {
        var accepting = new AtomicBoolean(true);
        var registry = new KvBackedStreamRegistry(switchableClusterNode(accepting), emptyStore());

        registry.register(operatorEntry())
                .onFailure(cause -> fail("register against an accepting cluster must succeed: " + cause.message()));
    }

    private static StreamRegistryEntry operatorEntry() {
        return StreamRegistryEntry.operator(APP_ORDERS, RetentionPolicy.retentionPolicy(), Instant.EPOCH);
    }

    private static ClusterNode<KVCommand<AetherKey>> refusingClusterNode() {
        return switchableClusterNode(new AtomicBoolean(false));
    }

    private static ClusterNode<KVCommand<AetherKey>> switchableClusterNode(AtomicBoolean accepting) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return NodeId.nodeId("test-node").unwrap();
            }

            @Override public TopologyManager topologyManager() {
                return null;
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return accepting.get() ? Promise.success(List.of()) : Promise.failure(CONSENSUS_REFUSED);
            }
        };
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
