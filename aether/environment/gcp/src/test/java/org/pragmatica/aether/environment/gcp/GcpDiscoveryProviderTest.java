package org.pragmatica.aether.environment.gcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.cloud.gcp.api.Instance;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.gcp.GcpConfig.gcpConfig;

class GcpDiscoveryProviderTest {

    private static final GcpEnvironmentConfig BASE_CONFIG = GcpEnvironmentConfig.gcpEnvironmentConfig(
        gcpConfig("test-project", "us-central1-a", "test@test.iam.gserviceaccount.com", "fake-key"),
        "e2-medium", "projects/debian-cloud/global/images/debian-12",
        "global/networks/default", "regions/us-central1/subnetworks/default", "").unwrap();

    private TestGcpClient testClient;
    private GcpDiscoveryProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestGcpClient();
        var config = BASE_CONFIG.withDiscovery("test-cluster").withSelfInstanceName("self-instance");
        provider = GcpDiscoveryProvider.gcpDiscoveryProvider(testClient, config);
    }

    @Nested
    class DiscoverPeersTests {

        @Test
        void discoverPeers_extractsHostAndPort() {
            testClient.listInstancesResponse = Promise.success(List.of(
                instanceWithLabels("server-1", "10.0.0.1",
                    Map.of("aether-cluster", "test-cluster", "aether-port", "9200"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(GcpDiscoveryProviderTest::assertSinglePeerWithPort9200);
        }

        @Test
        void discoverPeers_usesNetworkIp() {
            testClient.listInstancesResponse = Promise.success(List.of(
                instanceWithLabels("server-1", "10.0.0.1",
                    Map.of("aether-cluster", "test-cluster"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().host()).isEqualTo("10.0.0.1"));
        }

        @Test
        void discoverPeers_parsesPortFromLabel() {
            testClient.listInstancesResponse = Promise.success(List.of(
                instanceWithLabels("server-1", "1.2.3.4",
                    Map.of("aether-cluster", "test", "aether-port", "8080"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().port()).isEqualTo(8080));
        }

        @Test
        void discoverPeers_defaultsPortTo9100() {
            testClient.listInstancesResponse = Promise.success(List.of(
                instanceWithLabels("server-1", "1.2.3.4",
                    Map.of("aether-cluster", "test"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().port()).isEqualTo(9100));
        }

        @Test
        void discoverPeers_usesClusterLabelFilter() {
            testClient.listInstancesResponse = Promise.success(List.of());

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastLabelFilter).isEqualTo("labels.aether-cluster=test-cluster");
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

            assertThat(testClient.lastSetLabelsInstanceName).isEqualTo("self-instance");
            assertThat(testClient.lastSetLabels).containsEntry("aether-cluster", "test-cluster");
            assertThat(testClient.lastSetLabels).containsEntry("aether-port", "9100");
            assertThat(testClient.lastSetLabels).containsEntry("aether-role", "worker");
        }

        @Test
        void registerSelf_failsWhenNoSelfInstanceName() {
            var configNoSelf = BASE_CONFIG.withDiscovery("test-cluster");
            var providerNoSelf = GcpDiscoveryProvider.gcpDiscoveryProvider(testClient, configNoSelf);
            var self = new PeerInfo("10.0.0.1", 9100, Map.of());

            providerNoSelf.registerSelf(self)
                          .await()
                          .onSuccess(unit -> assertThat(unit).isNull())
                          .onFailure(GcpDiscoveryProviderTest::assertOperationNotSupported);
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

            assertThat(testClient.lastSetLabelsInstanceName).isEqualTo("self-instance");
            assertThat(testClient.lastSetLabels).isEmpty();
        }

        @Test
        void deregisterSelf_failsWhenNoSelfInstanceName() {
            var configNoSelf = BASE_CONFIG.withDiscovery("test-cluster");
            var providerNoSelf = GcpDiscoveryProvider.gcpDiscoveryProvider(testClient, configNoSelf);

            providerNoSelf.deregisterSelf()
                          .await()
                          .onSuccess(unit -> assertThat(unit).isNull())
                          .onFailure(GcpDiscoveryProviderTest::assertOperationNotSupported);
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

    // --- Instance factory helpers ---

    private static Instance instanceWithLabels(String name, String networkIp, Map<String, String> labels) {
        return new Instance(name, "RUNNING", "us-central1-a",
            List.of(new Instance.NetworkInterface(networkIp, "default")),
            labels);
    }
}
