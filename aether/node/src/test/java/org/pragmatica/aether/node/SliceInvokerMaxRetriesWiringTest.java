// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.pragmatica.aether.config.TimeoutsConfig;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.node.assembly.SliceRuntimeAssembly;
import org.pragmatica.aether.slice.DeferredSliceInvokerFacade;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2807: the node's `timeouts.invocation.max_retries` reaches the `SliceInvoker` the assembly builds. The value is a
/// non-default one, so a wiring that passes a constant (0 disables re-routing, 3 is the default) cannot pass.
class SliceInvokerMaxRetriesWiringTest {
    private static final int CONFIGURED_MAX_RETRIES = 7;

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    @Test
    void sliceInvoker_assembly_passesTheConfiguredMaxRetries() throws Exception {
        var defaults = TimeoutsConfig.timeoutsConfig();
        var invocation = defaults.invocation();
        var timeouts = new TimeoutsConfig(new TimeoutsConfig.InvocationTimeouts(invocation.timeout(),
                                                                               invocation.invokerTimeout(),
                                                                               invocation.retryBaseDelay(),
                                                                               CONFIGURED_MAX_RETRIES),
                                          defaults.forwarding(),
                                          defaults.deployment(),
                                          defaults.rollingUpdate(),
                                          defaults.cluster(),
                                          defaults.observability(),
                                          defaults.dht(),
                                          defaults.worker(),
                                          defaults.security(),
                                          defaults.repository(),
                                          defaults.scaling(),
                                          defaults.storageMaintenance());
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(), Option.none(), tempDir)
                                                         .withTimeouts(timeouts);
        var setup = new AetherNode.ResourceProviderSetup(mock(ResourceProviderFacade.class),
                                                         Option.none(),
                                                         Option.none(),
                                                         Option.none(),
                                                         Option.none());
        var inputs = new SliceRuntimeAssembly.SliceInvokerInputs(config,
                                                                 mock(MessageRouter.DelegateRouter.class),
                                                                 mock(KVStore.class),
                                                                 DeferredSliceInvokerFacade.deferredSliceInvokerFacade(),
                                                                 deepStubbedClusterNode(),
                                                                 mock(Serializer.class),
                                                                 mock(Deserializer.class),
                                                                 mock(DistributedDHTClient.class),
                                                                 mock(DistributedDHTClient.class),
                                                                 mock(StorageInstance.class),
                                                                 setup,
                                                                 mock(SwitchableClusterNode.class),
                                                                 mock(InvocationMetricsCollector.class),
                                                                 mock(InvocationHandler.class),
                                                                 EndpointRegistry.endpointRegistry(),
                                                                 mock(TopicSubscriptionRegistry.class),
                                                                 mock(ScheduledTaskRegistry.class),
                                                                 mock(ScheduledTaskStateRegistry.class),
                                                                 new AtomicReference<>(),
                                                                 mock(DeploymentManager.class),
                                                                 mock(OperatorWarningSink.class));

        var invoker = SliceRuntimeAssembly.sliceInvoker(inputs).sliceInvoker();
        var field = invoker.getClass().getDeclaredField("maxRetries");

        field.setAccessible(true);

        try {
            assertThat(field.getInt(invoker)).as("timeouts.invocation.max_retries must reach the invoker")
                                             .isEqualTo(CONFIGURED_MAX_RETRIES);
        } finally {
            invoker.stop().await(timeSpan(5).seconds());
        }
    }

    @SuppressWarnings("unchecked")
    private static RabiaNode<KVCommand<AetherKey>> deepStubbedClusterNode() {
        return (RabiaNode<KVCommand<AetherKey>>) mock(RabiaNode.class, Answers.RETURNS_DEEP_STUBS);
    }
}
