// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// D1 exit (k), the in-JVM host arm (KVStore dispatcher design §3.2, "Why there is no shutdown hang", in-JVM hosts): a
/// STARTED node whose KV notification handler throws an `Error` is stopped by its host through the in-JVM entry's stop
/// hook, and that real stop (`performStop` through the reset of the engine, over a store the failure path has already
/// closed) COMPLETES, ends the node's dispatcher thread, and runs on a thread other than the dispatcher's.
///
/// The sink-level arm (`AetherNodeKvDispatcherWiringBootTest#inJvmSink_stopsTheNodeOffTheCallingThread`) pins the hop in
/// isolation; this class pins the whole path with the real node behind it. The failure is injected at the router
/// boundary: the test interposes a router that throws on a `ValuePut` notification once armed, which is exactly where
/// a handler's `Error` reaches the dispatcher's catch. Mutations (each RED through this class):
/// - the stop runs inline on the dispatcher thread (`stopInJvm` without the virtual-thread hop): the thread assertion;
/// - a closed store's `awaitDelivered` parks instead of failing: the stop never completes, the bounded await fails.
class AetherNodeKvDispatcherFailureStopBootTest {
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:dispatcher-stop:1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:dispatcher-stop-slice:1.0.0").unwrap();

    @TempDir
    Path tempDir;

    private final AtomicReference<AetherNode> nodeRef = new AtomicReference<>();
    private final AtomicReference<Promise<Unit>> stopPromise = new AtomicReference<>();
    private final AtomicReference<String> stoppedOn = new AtomicReference<>();
    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private final AtomicBoolean armed = new AtomicBoolean();

    @AfterEach
    void tearDown() {
        Option.option(nodeRef.getAndSet(null))
              .onPresent(node -> node.stop()
                                     .await(timeSpan(10).seconds()));
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void inJvmNode_stopCompletesAfterADispatcherFailure() throws Exception {
        var delegate = MessageRouter.DelegateRouter.delegate();
        var self = NodeId.nodeId("kv-dispatcher-stop-" + UUID.randomUUID()).unwrap();
        var node = AetherNode.aetherNode(minimalConfig(self, tempDir),
                                         delegate,
                                         NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                         this::stopThisNode)
                             .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                             .unwrap();

        nodeRef.set(node);
        interposeFailingRouter(delegate);
        node.start()
            .await(timeSpan(30).seconds())
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        var store = node.kvStore();
        var dispatcher = store.dispatcherThreadName();

        assertThat(liveThreadNamed(dispatcher)).as("control: the started node has a live dispatcher thread").isTrue();
        armed.set(true);
        node.kvStore().process(store.createBatch(List.of(blueprintPut())));

        assertThat(stopRequested.await(15, TimeUnit.SECONDS)).as("the failure reaches the in-JVM stop hook").isTrue();
        assertThat(stoppedOn.get()).as("the stop runs off the failing dispatcher thread").isNotEqualTo(dispatcher);

        var stopped = stopPromise.get().await(timeSpan(30).seconds());

        assertThat(stopped.isSuccess()).as("the node stop completes over the closed store: %s", stopped).isTrue();
        assertThat(store.deliveryMetrics().closed()).as("the failure path closed the store").isTrue();
        assertThat(liveThreadNamed(dispatcher)).as("the dispatcher thread has ended").isFalse();
        nodeRef.set(null);
    }

    private void stopThisNode() {
        stoppedOn.set(Thread.currentThread().getName());
        stopPromise.set(nodeRef.get().stop());
        stopRequested.countDown();
    }

    /// Replaces the delegate the booted node installed with a router that forwards everything, except that once armed a
    /// `ValuePut` notification raises an `AssertionError` before any handler sees it.
    private void interposeFailingRouter(MessageRouter.DelegateRouter delegate) throws Exception {
        Field field = delegate.getClass().getDeclaredField("delegate");

        field.setAccessible(true);
        var real = (MessageRouter) field.get(delegate);

        delegate.replaceDelegate(new FailingRouter(real, armed));
    }

    private static AetherKey blueprintKey() {
        return AppBlueprintKey.appBlueprintKey(OWNER);
    }

    private static KVCommand<AetherKey> blueprintPut() {
        var slice = ResolvedSlice.resolvedSlice(SLICE, 1, 1, false, Set.of())
                                     .unwrap();
        var blueprint = AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER, List.of(slice), Option.none()));

        return new KVCommand.Put<AetherKey, AetherValue>(blueprintKey(), blueprint);
    }

    private static boolean liveThreadNamed(String name) {
        return Thread.getAllStackTraces()
                     .keySet()
                     .stream()
                     .anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }

    private static AetherNodeConfig minimalConfig(NodeId self, Path storageRoot) {
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }

    private record FailingRouter(MessageRouter real, AtomicBoolean armed) implements MessageRouter.ImmutableRouter<Message> {
        @Override
        public Map<Class<Message>, List<Consumer<Message>>> routingTable() {
            return Map.of();
        }

        @Override
        public <R extends Message> void route(R message) {
            if (armed.get() && message instanceof KVStoreNotification.ValuePut) {
                throw new AssertionError("injected handler error");
            }

            real.route(message);
        }
    }
}
