package org.pragmatica.aether.environment.hetzner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

class HetznerDiscoveryProviderTest {

    private static final HetznerEnvironmentConfig BASE_CONFIG = HetznerEnvironmentConfig.hetznerEnvironmentConfig(
        hetznerConfig("test-token"),
        "cx22", "ubuntu-24.04", "fsn1",
        List.of(), List.of(), List.of(), "").unwrap();

    private HetznerComputeProviderTest.TestHetznerClient testClient;
    private HetznerDiscoveryProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new HetznerComputeProviderTest.TestHetznerClient();
        var config = BASE_CONFIG.withDiscovery("test-cluster").withSelfServerId(100L);
        provider = HetznerDiscoveryProvider.hetznerDiscoveryProvider(testClient, config);
    }

    @Nested
    class DiscoverPeersTests {

        @Test
        void discoverPeers_extractsHostAndPort() {
            testClient.listServersResponse = Promise.success(List.of(
                serverWithPrivateIpAndLabels(1, "10.0.0.1", Map.of("aether-cluster", "test-cluster", "aether-port", "9200"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(HetznerDiscoveryProviderTest::assertSinglePeerWithPort9200);
        }

        @Test
        void discoverPeers_usesPrivateIpOverPublic() {
            testClient.listServersResponse = Promise.success(List.of(
                serverWithBothAddresses(1, "5.6.7.8", "10.0.0.1",
                                        Map.of("aether-cluster", "test-cluster"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().host()).isEqualTo("10.0.0.1"));
        }

        @Test
        void discoverPeers_usesPublicIpWhenNoPrivate() {
            testClient.listServersResponse = Promise.success(List.of(
                serverWithPublicOnly(1, "5.6.7.8", Map.of("aether-cluster", "test-cluster"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().host()).isEqualTo("5.6.7.8"));
        }

        @Test
        void discoverPeers_parsesPortFromLabel() {
            testClient.listServersResponse = Promise.success(List.of(
                serverWithPublicOnly(1, "1.2.3.4", Map.of("aether-cluster", "test", "aether-port", "8080"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().port()).isEqualTo(8080));
        }

        @Test
        void discoverPeers_defaultsPortTo9100() {
            testClient.listServersResponse = Promise.success(List.of(
                serverWithPublicOnly(1, "1.2.3.4", Map.of("aether-cluster", "test"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().port()).isEqualTo(9100));
        }

        @Test
        void discoverPeers_usesClusterLabelSelector() {
            testClient.listServersResponse = Promise.success(List.of());

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastLabelSelector).isEqualTo("aether-cluster=test-cluster");
        }
    }

    @Nested
    class RegisterSelfTests {

        @Test
        void registerSelf_setsCorrectLabels() {
            var self = new PeerInfo("10.0.0.1", 9100, Map.of("role", "worker"));

            provider.registerSelf(self)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastUpdateLabelsServerId).isEqualTo(100L);
            assertThat(testClient.lastUpdateLabels).containsEntry("aether-cluster", "test-cluster");
            assertThat(testClient.lastUpdateLabels).containsEntry("aether-port", "9100");
            assertThat(testClient.lastUpdateLabels).containsEntry("aether-role", "worker");
        }

        @Test
        void registerSelf_failsWhenNoSelfServerId() {
            var configNoSelf = BASE_CONFIG.withDiscovery("test-cluster");
            var providerNoSelf = HetznerDiscoveryProvider.hetznerDiscoveryProvider(testClient, configNoSelf);
            var self = new PeerInfo("10.0.0.1", 9100, Map.of());

            providerNoSelf.registerSelf(self)
                          .await()
                          .onSuccess(unit -> assertThat(unit).isNull())
                          .onFailure(HetznerDiscoveryProviderTest::assertOperationNotSupported);
        }
    }

    @Nested
    class DeregisterSelfTests {

        @Test
        void deregisterSelf_clearsLabels() {
            provider.deregisterSelf()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastUpdateLabelsServerId).isEqualTo(100L);
            assertThat(testClient.lastUpdateLabels).isEmpty();
        }

        @Test
        void deregisterSelf_failsWhenNoSelfServerId() {
            var configNoSelf = BASE_CONFIG.withDiscovery("test-cluster");
            var providerNoSelf = HetznerDiscoveryProvider.hetznerDiscoveryProvider(testClient, configNoSelf);

            providerNoSelf.deregisterSelf()
                          .await()
                          .onSuccess(unit -> assertThat(unit).isNull())
                          .onFailure(HetznerDiscoveryProviderTest::assertOperationNotSupported);
        }
    }

    // --- Assertion helpers ---

    private static void assertSinglePeerWithPort9200(List<PeerInfo> peers) {
        assertThat(peers).hasSize(1);
        assertThat(peers.getFirst().host()).isEqualTo("10.0.0.1");
        assertThat(peers.getFirst().port()).isEqualTo(9200);
    }

    private static void assertOperationNotSupported(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.OperationNotSupported.class);
    }

    // --- Server factory helpers ---

    private static Server serverWithPrivateIpAndLabels(long id, String privateIp, Map<String, String> labels) {
        return new Server(id, "server-" + id, "running", serverType(), image(),
                          null, List.of(new Server.PrivateNet(1L, privateIp)), labels);
    }

    private static Server serverWithBothAddresses(long id, String publicIp, String privateIp,
                                                   Map<String, String> labels) {
        return new Server(id, "server-" + id, "running", serverType(), image(),
                          publicNet(publicIp), List.of(new Server.PrivateNet(1L, privateIp)), labels);
    }

    private static Server serverWithPublicOnly(long id, String publicIp, Map<String, String> labels) {
        return new Server(id, "server-" + id, "running", serverType(), image(),
                          publicNet(publicIp), List.of(), labels);
    }

    private static Server.ServerType serverType() {
        return new Server.ServerType(1, "cx22", "CX22", 2, 4.0, 40);
    }

    private static Server.Image image() {
        return new Server.Image(1, "ubuntu-24.04", "Ubuntu 24.04", "ubuntu");
    }

    private static Server.PublicNet publicNet(String ipv4) {
        return new Server.PublicNet(new Server.Ipv4(ipv4), new Server.Ipv6("2001:db8::1"));
    }
}
