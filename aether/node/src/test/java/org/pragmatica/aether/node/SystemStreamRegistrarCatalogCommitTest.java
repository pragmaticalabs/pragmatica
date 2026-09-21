// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.KvBackedStreamRegistry;
import org.pragmatica.aether.slice.stream.SystemStreamBootstrap;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Result.unitResult;


/// #968, through the REAL bootstrap leg: `SystemStreamRegistrar` must not latch its bootstrap leg
/// DONE when the catalog `Put` for `system:*` is refused by consensus. `SystemStreamRegistrarTest`
/// drives the registrar with stub legs and so pins the retry loop, not what the real leg reports;
/// before the fix the real leg — `SystemStreamBootstrap` over `KvBackedStreamRegistry` — reported
/// success for a put it had fired and dropped, so the registrar latched DONE and never retried, and
/// `system:cluster-events` stayed out of the catalog on a stable cluster.
///
/// The seam is the registry's `ClusterNode`: a stub whose `apply` either lands the put in a real
/// `KVStore` or fails the promise. `StreamNamespacesService.inMemory()` (used by #1229's tests)
/// registers synchronously and cannot exercise this path.
class SystemStreamRegistrarCatalogCommitTest {
    @Test
    void bootstrapLeg_catalogPutRefused_doesNotLatchDone_andRetriesUntilItCommits() {
        var accepting = new AtomicBoolean(false);
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var registry = new KvBackedStreamRegistry(switchableClusterNode(store, accepting), store);
        var bootstrap = new SystemStreamBootstrap(registry);
        var scheduler = new CapturingScheduler();
        var registrar = SystemStreamRegistrar.systemStreamRegistrar(() -> unitResult(), bootstrap::bootstrap, scheduler);

        registrar.onLeaderChange(gained());

        assertThat(registrar.isComplete()).as("the bootstrap leg must not latch DONE on a refused catalog put").isFalse();
        assertThat(scheduler.hasPending()).as("a retry must be scheduled for the refused leg").isTrue();
        assertThat(registry.lookup(SystemStreams.CLUSTER_EVENTS).isEmpty()).isTrue();

        accepting.set(true);
        scheduler.fireNext();

        assertThat(registrar.isComplete()).as("once consensus accepts, the retry commits and the leg latches DONE").isTrue();
        assertThat(registry.lookup(SystemStreams.CLUSTER_EVENTS).isPresent()).isTrue();
        assertThat(scheduler.hasPending()).isFalse();
    }

    private static LeaderNotification.LeaderChange gained() {
        return LeaderNotification.leaderChange(Option.option(NodeId.nodeId("leader").unwrap()), true);
    }

    private static ClusterNode<KVCommand<AetherKey>> switchableClusterNode(KVStore<AetherKey, AetherValue> store,
                                                                           AtomicBoolean accepting) {
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
                if (!accepting.get()) {
                    return Promise.failure(Causes.cause("Node is inactive"));
                }

                store.process(store.createBatch(commands));

                return Promise.success(List.of());
            }
        };
    }

    private static final class CapturingScheduler implements SystemStreamRegistrar.RetryScheduler {
        private final List<Runnable> pending = new ArrayList<>();

        @Override public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            pending.add(runnable);
            return new NoopFuture();
        }

        boolean hasPending() {
            return !pending.isEmpty();
        }

        void fireNext() {
            if (pending.isEmpty()) {
                return;
            }
            pending.removeFirst().run();
        }
    }

    private static final class NoopFuture implements ScheduledFuture<Object> {
        @Override public long getDelay(TimeUnit unit) {return 0;}
        @Override public int compareTo(Delayed o) {return 0;}
        @Override public boolean cancel(boolean mayInterruptIfRunning) {return true;}
        @Override public boolean isCancelled() {return false;}
        @Override public boolean isDone() {return false;}
        @Override public Object get() {return null;}
        @Override public Object get(long timeout, TimeUnit unit) {return null;}
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
