package org.pragmatica.aether.environment.azure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.cloud.azure.api.ResourceRow;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.azure.AzureConfig.azureConfig;

class AzureDiscoveryProviderTest {

    private static final AzureEnvironmentConfig BASE_CONFIG = AzureEnvironmentConfig.azureEnvironmentConfig(
        azureConfig("tenant", "client", "secret", "sub", "rg", "eastus"),
        "Standard_B2s", "Canonical:ubuntu:22_04:latest",
        "azureuser", "ssh-ed25519 AAAA", "subnet-id", "").unwrap();

    private TestAzureClient testClient;
    private AzureDiscoveryProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestAzureClient();
        var config = BASE_CONFIG.withDiscovery("test-cluster").withSelfVmName("self-vm");
        provider = AzureDiscoveryProvider.azureDiscoveryProvider(testClient, config);
    }

    @Nested
    class DiscoverPeersTests {

        @Test
        void discoverPeers_extractsHostAndPort() {
            testClient.queryResourcesResponse = Promise.success(List.of(
                resourceRow("vm-1", Map.of("aether-cluster", "test-cluster", "aether-port", "9200"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(AzureDiscoveryProviderTest::assertSinglePeerWithPort9200);
        }

        @Test
        void discoverPeers_defaultsPortTo9100() {
            testClient.queryResourcesResponse = Promise.success(List.of(
                resourceRow("vm-1", Map.of("aether-cluster", "test-cluster"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().port()).isEqualTo(9100));
        }

        @Test
        void discoverPeers_usesVmNameAsHost() {
            testClient.queryResourcesResponse = Promise.success(List.of(
                resourceRow("my-vm-name", Map.of("aether-cluster", "test-cluster"))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().host()).isEqualTo("my-vm-name"));
        }

        @Test
        void discoverPeers_usesClusterTagQuery() {
            testClient.queryResourcesResponse = Promise.success(List.of());

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastQueryResourcesKql).contains("aether-cluster");
            assertThat(testClient.lastQueryResourcesKql).contains("test-cluster");
        }
    }

    @Nested
    class RegisterSelfTests {

        @Test
        void registerSelf_setsCorrectTags() {
            testClient.updateTagsResponse = Promise.success(AzureComputeProviderTest.runningVm("self-vm"));
            var self = new PeerInfo("10.0.0.1", 9100, Map.of("role", "worker"));

            provider.registerSelf(self)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastUpdateTagsVmName).isEqualTo("self-vm");
            assertThat(testClient.lastUpdateTags).containsEntry("aether-cluster", "test-cluster");
            assertThat(testClient.lastUpdateTags).containsEntry("aether-port", "9100");
            assertThat(testClient.lastUpdateTags).containsEntry("aether-role", "worker");
        }

        @Test
        void registerSelf_failsWhenNoSelfVmName() {
            var configNoSelf = BASE_CONFIG.withDiscovery("test-cluster");
            var providerNoSelf = AzureDiscoveryProvider.azureDiscoveryProvider(testClient, configNoSelf);
            var self = new PeerInfo("10.0.0.1", 9100, Map.of());

            providerNoSelf.registerSelf(self)
                          .await()
                          .onSuccess(unit -> assertThat(unit).isNull())
                          .onFailure(AzureDiscoveryProviderTest::assertOperationNotSupported);
        }
    }

    @Nested
    class DeregisterSelfTests {

        @Test
        void deregisterSelf_clearsTags() {
            testClient.updateTagsResponse = Promise.success(AzureComputeProviderTest.runningVm("self-vm"));

            provider.deregisterSelf()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastUpdateTagsVmName).isEqualTo("self-vm");
            assertThat(testClient.lastUpdateTags).isEmpty();
        }

        @Test
        void deregisterSelf_failsWhenNoSelfVmName() {
            var configNoSelf = BASE_CONFIG.withDiscovery("test-cluster");
            var providerNoSelf = AzureDiscoveryProvider.azureDiscoveryProvider(testClient, configNoSelf);

            providerNoSelf.deregisterSelf()
                          .await()
                          .onSuccess(unit -> assertThat(unit).isNull())
                          .onFailure(AzureDiscoveryProviderTest::assertOperationNotSupported);
        }
    }

    // --- Assertion helpers ---

    private static void assertSinglePeerWithPort9200(List<PeerInfo> peers) {
        assertThat(peers).hasSize(1);
        assertThat(peers.getFirst().host()).isEqualTo("vm-1");
        assertThat(peers.getFirst().port()).isEqualTo(9200);
    }

    private static void assertOperationNotSupported(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.OperationNotSupported.class);
    }

    // --- Resource row factory helpers ---

    private static ResourceRow resourceRow(String name, Map<String, String> tags) {
        return new ResourceRow("/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/" + name,
                                name, "microsoft.compute/virtualmachines", "eastus", tags, Map.of());
    }
}
