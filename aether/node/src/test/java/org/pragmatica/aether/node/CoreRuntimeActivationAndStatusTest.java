// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ManagementApiResponses.StatusResponse;
import org.pragmatica.aether.api.routes.StatusRoutes;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class CoreRuntimeActivationAndStatusTest {
    @TempDir Path storageRoot;
    private AetherNode node;

    @AfterEach void close() {
        if (node != null) node.stop().await(timeSpan(10).seconds()).unwrap();
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    void assembledCoreRejectsWorkerDirectiveDeliveredThroughItsRealValuePutSubscription() throws ReflectiveOperationException {
        node = AetherNode.aetherNode(coreConfig(), () -> {}).unwrap();
        var switchable = (SwitchableClusterNode<?>) component(node, "switchableCluster");
        var coreDelegate = component(node, "clusterNode");
        var announcer = (AtomicReference<?>) component(node, "governorAnnouncerHolder");
        assertThat(switchable.current()).isSameAs(coreDelegate);
        assertThat(announcer.get()).isNull();

        var directive = new AetherValue.ActivationDirectiveValue(AetherValue.ActivationDirectiveValue.WORKER, "wrong-role-community", "");
        var notification = new KVStoreNotification.ValuePut<>(
            new KVCommand.Put<>(new AetherKey.ActivationDirectiveKey(node.self()), directive), Option.none());
        ((MessageRouter) component(node, "router")).route(notification);

        assertThat(switchable.current()).as("a core cannot acquire the worker forwarding delegate").isSameAs(coreDelegate);
        assertThat(announcer.get()).as("the wrong-role ValuePut cannot start worker/governor subsystems").isNull();
        assertThat(node.coreNodeIds()).contains(node.self());
    }

    @Test
    void statusRouteExposesTheAssembledConsensusVoterReconfigurationStatus() {
        node = AetherNode.aetherNode(coreConfig(), () -> {}).unwrap();
        var expected = node.voterReconfigurationStatus();
        assertThat(expected.stage()).isEqualTo("STABLE");
        assertThat(expected.installedVoters()).containsExactly(node.self().id());
        var route = StatusRoutes.statusRoutes(() -> node, node::appHttpServer).routes().findFirst().orElseThrow();
        var request = (RequestContext) Proxy.newProxyInstance(RequestContext.class.getClassLoader(),
            new Class[]{RequestContext.class}, (_, method, _) -> fail("The no-argument status route unexpectedly read " + method.getName()));
        var response = (StatusResponse) route.handler().handle(request).await(timeSpan(5).seconds()).unwrap();
        assertThat(response.voterReconfiguration()).isEqualTo(expected);
        assertThat(response.voterReconfiguration().failure()).isEmpty();
    }

    private AetherNodeConfig coreConfig() {
        var self = new NodeId("activation-status-core");
        return AetherNodeConfig.builder().self(self)
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6123).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"))))
            .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
            .sliceConfig(org.pragmatica.aether.config.SliceConfig.sliceConfig())
            .artifactRepo(org.pragmatica.dht.DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.some(HermeticStorage.withControlStorageIn(storageRoot,
                org.pragmatica.config.ConfigurationProvider.builder().build())))
            .environment(Option.none()).managementHttpProtocol(org.pragmatica.aether.config.HttpProtocol.H1).storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false)).build();
    }

    private static Object component(Object instance, String name) throws ReflectiveOperationException {
        var method = instance.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(instance);
    }
}
