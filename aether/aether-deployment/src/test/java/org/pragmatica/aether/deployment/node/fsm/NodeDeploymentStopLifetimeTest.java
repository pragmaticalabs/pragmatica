// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentEvents.NodeArtifactPutReceived;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumDisappeared;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.Shutdown;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.OwnerLifetime;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3754, the node deployment FSM's four timer sites: the routing remediation (1), the activation remediation (2), the bounded park escalation (3)
/// and the FAILED-write retry (4). One lifetime per `NodeDeploymentContext`; entry into `Stopped` ends it, and every consensus write of the FSM goes
/// through a door that refuses once it has ended.
///
/// Per site, the pair the brief fixes: (a) a task armed before the stop never runs its body after it; (b) a body already past the wrapper's read of
/// the lifetime when the stop ran produces no effect. (b) is driven by calling the body on the `Active` state captured BEFORE the stop, which is exactly
/// a body in flight: the lifetime's wrapper cannot help it. Each (a)/(b) test has a LIVE-lifetime control in which the same body does produce its effect,
/// so a silent body cannot pass for a refused one. The timers are armed through the production path and fired by hand through a recording seam.
///
/// Mutants (each solo goes red): the stop never dispatches `Shutdown` (the manager and node tests); `Stopped.onEntry` does not end the lifetime (every (a)
/// test and the stop-path tests); the door lets writes through (the (b) tests of sites 1, 2 and 4); the park escalation's own check removed (the (b)
/// test of site 3).
@SuppressWarnings("JBCT-RET-03")
class NodeDeploymentStopLifetimeTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final SliceNodeKey KEY = SliceNodeKey.sliceNodeKey(ARTIFACT, SELF);

    private final Set<Artifact> serving = ConcurrentHashMap.newKeySet();
    private final AtomicLong clock = new AtomicLong(10_000_000L);
    private final HandSeam seam = new HandSeam();
    private RecordingClusterNode cluster;
    private KVStore<AetherKey, AetherValue> kvStore;
    private OwnerLifetime lifetime;
    private NodeDeploymentContext context;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        cluster = new RecordingClusterNode(SELF);
        lifetime = OwnerLifetime.ownerLifetime(seam);
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory = fsm -> buildContext(fsm, ctxHolder, router);

        harness = FsmTestHarness.harness("stop-lifetime-" + SELF.id(), factory);
        context = ctxHolder.get();
        harness.dispatch(new QuorumEstablished());
        cluster.batches.clear();
    }

    @Nested
    class Site1RoutingRemediation {
        @Test
        void remediateStuckRouting_liveLifetime_redrivesTheWrite() {
            var active = activeWithSlice(SliceState.ROUTING);

            active.remediateStuckRouting(KEY);

            assertThat(cluster.batches).as("control: the live body writes").hasSize(1);
        }

        @Test
        void scheduledBeforeStop_neverRunsItsBodyAfterShutdown() {
            var active = activeWithSlice(SliceState.ROUTING);

            active.scheduleRoutingRemediation(KEY);
            assertThat(seam.wrappers).as("control: the timer was armed through the lifetime").hasSize(1);
            stop();
            seam.wrappers.getFirst().run();

            assertThat(cluster.batches).as("the wrapper refused to run the body").isEmpty();
            assertThat(seam.futures.getFirst().isCancelled()).as("and the stop cancelled the queue entry").isTrue();
        }

        @Test
        void bodyInFlightAtStop_producesNoWrite() {
            var active = activeWithSlice(SliceState.ROUTING);

            stop();
            active.remediateStuckRouting(KEY);

            assertThat(cluster.batches).as("the door refused the re-driven write").isEmpty();
        }
    }

    @Nested
    class Site2ActivationRemediation {
        @Test
        void remediateStuckActivating_liveLifetime_redrivesTheWrite() {
            var active = activeWithSlice(SliceState.ACTIVATING);

            active.remediateStuckActivating(KEY);

            assertThat(cluster.batches).as("control: the live body writes").hasSize(1);
        }

        @Test
        void scheduledBeforeStop_neverRunsItsBodyAfterShutdown() {
            var active = activeWithSlice(SliceState.ACTIVATING);

            active.processStateTransition(KEY, SliceState.ACTIVATING);
            assertThat(seam.wrappers).as("control: the timer was armed through the lifetime").hasSize(1);
            stop();
            seam.wrappers.getFirst().run();

            assertThat(cluster.batches).as("the wrapper refused to run the body").isEmpty();
            assertThat(seam.futures.getFirst().isCancelled()).as("and the stop cancelled the queue entry").isTrue();
        }

        @Test
        void bodyInFlightAtStop_producesNoWrite() {
            var active = activeWithSlice(SliceState.ACTIVATING);

            stop();
            active.remediateStuckActivating(KEY);

            assertThat(cluster.batches).as("the door refused the re-driven write").isEmpty();
        }
    }

    @Nested
    class Site3ParkEscalation {
        @Test
        void escalateParkedStart_liveLifetime_marksTheWarning() {
            var active = park();

            active.escalateParkedStart(KEY);

            assertThat(warned()).as("control: the live body raises").isTrue();
        }

        @Test
        void scheduledBeforeStop_neverRunsItsBodyAfterShutdown() {
            park();
            assertThat(seam.wrappers).as("control: the escalation was armed through the lifetime").hasSize(1);
            stop();
            seam.wrappers.getFirst().run();

            assertThat(warned()).as("the wrapper refused to run the body").isFalse();
            assertThat(seam.futures.getFirst().isCancelled()).as("and the stop cancelled the queue entry").isTrue();
        }

        @Test
        void bodyInFlightAtStop_raisesNothing() {
            var active = park();

            stop();
            active.escalateParkedStart(KEY);

            assertThat(warned()).as("the escalation's own second check refused").isFalse();
        }

        @Test
        void quorumFlap_doesNotEndTheLifetime_theBoundedParkSurvivesIt() {
            park();

            harness.dispatch(new QuorumDisappeared());
            harness.dispatch(new QuorumEstablished());

            assertThat(lifetime.isEnded()).as("a quorum cycle is not a stop").isFalse();
            assertThat(seam.futures.getFirst().isCancelled()).as("the armed escalation survives the flap").isFalse();
        }
    }

    @Nested
    class Site4FailedWriteRetry {
        @Test
        void transitionToFailedWithRetry_liveLifetime_writesFailed() {
            var active = activeWithSlice(SliceState.LOADING);

            assertThat(active.transitionToFailedWithRetry(KEY, Causes.cause("boom"), Unrecognised.RETRY, 0).await().isSuccess()).isTrue();
            assertThat(cluster.batches).as("control: the live body writes").hasSize(1);
        }

        @Test
        void scheduledBeforeStop_neverRunsItsBodyAfterShutdown() {
            var active = activeWithSlice(SliceState.LOADING);

            active.handleFailedTransitionRetry(KEY, Causes.cause("boom"), Unrecognised.RETRY, Causes.cause("write failed"), 0);
            assertThat(seam.wrappers).as("control: the retry was armed through the lifetime").hasSize(1);
            stop();
            seam.wrappers.getFirst().run();

            assertThat(cluster.batches).as("the wrapper refused to run the body").isEmpty();
            assertThat(seam.futures.getFirst().isCancelled()).as("and the stop cancelled the queue entry").isTrue();
        }

        @Test
        void bodyInFlightAtStop_producesNoWrite_andRearmsNothing() {
            var active = activeWithSlice(SliceState.LOADING);

            stop();
            var written = active.transitionToFailedWithRetry(KEY, Causes.cause("boom"), Unrecognised.RETRY, 0).await();

            assertThat(written.isFailure()).as("the door refused the write").isTrue();
            assertThat(cluster.batches).as("nothing reached consensus").isEmpty();
            assertThat(seam.wrappers).as("the refused write's retry is not armed on an ended lifetime").isEmpty();
        }
    }

    @Nested
    class StopPath {
        @Test
        void shutdownFromDormant_endsTheLifetime() {
            harness.dispatch(new QuorumDisappeared());
            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Dormant.class);

            harness.dispatch(new Shutdown());

            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Stopped.class);
            assertThat(lifetime.isEnded()).as("a node stop that leaves the FSM in Dormant still ends it").isTrue();
        }

        @Test
        void shutdownFromActive_endsTheLifetime() {
            harness.dispatch(new Shutdown());

            assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Stopped.class);
            assertThat(lifetime.isEnded()).isTrue();
        }

        @Test
        void quorumCycle_withoutShutdown_leavesTheLifetimeLive() {
            harness.dispatch(new QuorumDisappeared());
            harness.dispatch(new QuorumEstablished());

            assertThat(lifetime.isEnded()).isFalse();
        }

        @Test
        void managerStop_afterQuorumWasLeft_endsTheLifetimeAndCancelsTheArmedTimer() {
            var managerSeam = new HandSeam();
            var managerLifetime = OwnerLifetime.ownerLifetime(managerSeam);
            var manager = NodeDeploymentManager.nodeDeploymentManager(SELF,
                                                                      new NodeAddress("localhost", 9000),
                                                                      MessageRouter.mutable(),
                                                                      new EmptySliceStore(),
                                                                      cluster,
                                                                      kvStore,
                                                                      stubInvocationHandler(),
                                                                      SliceActionConfig.sliceActionConfig(),
                                                                      SliceCodec.sliceCodec(List.of()),
                                                                      Option.none(),
                                                                      Option.none(),
                                                                      timeSpan(120_000).millis(),
                                                                      timeSpan(2_000).millis(),
                                                                      Option::none,
                                                                      managerLifetime);

            manager.onQuorumStateChange(ClusterStateNotification.active());
            manager.onNodeArtifactPut(nodeArtifactPut(SliceState.LOAD));
            assertThat(managerSeam.wrappers).as("control: the park escalation was armed").hasSize(1);
            manager.onQuorumStateChange(ClusterStateNotification.passive());
            assertThat(manager.isActive()).as("control: quorum was left, the FSM is Dormant (today's stop path)").isFalse();
            assertThat(managerLifetime.isEnded()).as("control: leaving quorum is not a stop").isFalse();

            manager.stop();

            assertThat(managerLifetime.isEnded()).isTrue();
            assertThat(managerSeam.futures.getFirst().isCancelled()).isTrue();
        }
    }

    private void stop() {
        harness.dispatch(new Shutdown());
        assertThat(lifetime.isEnded()).as("control: the stop path ended the lifetime").isTrue();
    }

    private NodeDeploymentState.Active activeWithSlice(SliceState state) {
        commit(state);
        applyToKv(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(ARTIFACT.base()),
                                      SliceTargetValue.sliceTargetValue(ARTIFACT.version(), 1, 1, Option.none())));
        serving.add(ARTIFACT);
        var active = activeState();

        active.deployments().put(KEY, NodeDeploymentManager.SliceDeployment.sliceDeployment(KEY, state, 0L));

        return active;
    }

    /// A start refused for want of a committed target, parked at LOAD, with the bound already elapsed on the node's clock.
    private NodeDeploymentState.Active park() {
        commit(SliceState.LOAD);
        harness.dispatch(new NodeArtifactPutReceived(nodeArtifactPut(SliceState.LOAD)));
        assertThat(context.parkedStart(KEY).isPresent()).as("control: the start is parked").isTrue();
        clock.addAndGet(context.parkedStartBound().millis());

        return activeState();
    }

    private boolean warned() {
        return context.parkedStart(KEY).map(NodeDeploymentContext.ParkedStart::warned).or(false);
    }

    private ValuePut<AetherKey.NodeArtifactKey, NodeArtifactValue> nodeArtifactPut(SliceState state) {
        var key = NodeArtifactKey.nodeArtifactKey(SELF, ARTIFACT);

        return new ValuePut<>(new KVCommand.Put<>(key, NodeArtifactValue.nodeArtifactValue(state)), Option.none());
    }

    private void commit(SliceState state) {
        applyToKv(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(SELF, ARTIFACT), NodeArtifactValue.nodeArtifactValue(state)));
    }

    private void applyToKv(KVCommand<AetherKey> command) {
        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router) {
        var built = new NodeDeploymentContext(fsm,
                                              SELF,
                                              new NodeAddress("localhost", 9000),
                                              new EmptySliceStore(),
                                              SliceActionConfig.sliceActionConfig(),
                                              SliceCodec.sliceCodec(List.of()),
                                              cluster,
                                              kvStore,
                                              stubInvocationHandler(),
                                              router,
                                              Option.none(),
                                              Option.none(),
                                              timeSpan(120_000).millis(),
                                              timeSpan(2_000).millis(),
                                              clock::get,
                                              Option::none,
                                              lifetime);

        ctxHolder.set(built);

        return built.dormant();
    }

    /// Records what the lifetime enqueues and runs nothing by itself: a test fires a wrapper by hand, which is the moment a real scheduler would.
    private static final class HandSeam implements OwnerLifetime.Seam {
        private final List<Runnable> wrappers = new CopyOnWriteArrayList<>();
        private final List<HandFuture> futures = new CopyOnWriteArrayList<>();

        @Override
        public ScheduledFuture<?> enqueue(Runnable wrapper, TimeSpan initialDelay, Option<TimeSpan> period) {
            var future = new HandFuture();

            wrappers.add(wrapper);
            futures.add(future);

            return future;
        }
    }

    private static final class HandFuture implements ScheduledFuture<Object> {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override public long getDelay(TimeUnit unit) {return 0;}

        @Override public int compareTo(java.util.concurrent.Delayed other) {return 0;}

        @Override public boolean cancel(boolean mayInterruptIfRunning) {return cancelled.compareAndSet(false, true);}

        @Override public boolean isCancelled() {return cancelled.get();}

        @Override public boolean isDone() {return cancelled.get();}

        @Override public Object get() throws ExecutionException {throw new ExecutionException(new IllegalStateException("hand future"));}

        @Override public Object get(long timeout, TimeUnit unit) throws ExecutionException {return get();}
    }

    private static final class EmptySliceStore implements SliceStore {
        @Override public List<LoadedSlice> loaded() {
            return List.of();
        }

        @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
            return Promise.success(loadedSlice(artifact));
        }

        @Override public Promise<Unit> unloadSlice(Artifact artifact) {
            return Promise.unitPromise();
        }

        @Override public Option<ConfigurationProvider> sliceComposite(Artifact artifact) {
            return Option.none();
        }

        private static LoadedSlice loadedSlice(Artifact artifact) {
            var slice = new Slice() {
                @Override public List<org.pragmatica.aether.slice.SliceMethod<?, ?>> methods() {
                    return List.of();
                }
            };

            return new LoadedSlice() {
                @Override public Artifact artifact() {
                    return artifact;
                }

                @Override public Slice slice() {
                    return slice;
                }
            };
        }
    }

    /// Records every submitted batch whole, in submission order.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<List<KVCommand<AetherKey>>> batches = new CopyOnWriteArrayList<>();

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            batches.add(List.copyOf(batch));

            return Promise.success(List.of());
        }
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 1;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }

    private InvocationHandler stubInvocationHandler() {
        return new InvocationHandler() {
            @Override public void onInvokeRequest(InvokeRequest request) {}

            @Override public void registerSlice(Artifact artifact, SliceBridge bridge) {}

            @Override public void unregisterSlice(Artifact artifact) {}

            @Override public Option<SliceBridge> localSlice(Artifact artifact) {
                return serving.contains(artifact)
                       ? Option.some(stubBridge())
                       : Option.none();
            }

            @Override public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override public Option<InvocationMetricsCollector> metricsCollector() {
                return Option.none();
            }
        };
    }

    private static SliceBridge stubBridge() {
        return new SliceBridge() {
            @Override public Promise<byte[]> invoke(String methodName, byte[] input) {
                return Promise.success(new byte[0]);
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public ClassLoader classLoader() {
                return NodeDeploymentStopLifetimeTest.class.getClassLoader();
            }

            @Override public List<String> methodNames() {
                return List.of();
            }
        };
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
