package org.pragmatica.aether.environment.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.PeerInfo;
import org.pragmatica.cloud.aws.AwsError;
import org.pragmatica.cloud.aws.api.Instance;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.aws.AwsConfig.awsConfig;

class AwsDiscoveryProviderTest {

    private static final AwsEnvironmentConfig BASE_CONFIG = AwsEnvironmentConfig.awsEnvironmentConfig(
        awsConfig("test-key", "test-secret", "us-east-1"),
        "ami-12345", "t3.medium",
        Option.none(), List.of(), "subnet-abc", "").unwrap();

    private TestAwsClient testClient;
    private AwsDiscoveryProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestAwsClient();
        var config = BASE_CONFIG.withDiscovery("test-cluster");
        provider = AwsDiscoveryProvider.awsDiscoveryProvider(testClient, config);
    }

    @Nested
    class DiscoverPeersTests {

        @Test
        void discoverPeers_extractsHostAndPort() {
            testClient.describeResponse = Promise.success(
                TestAwsClient.describeResponseWith(List.of(
                    instanceWithTags("i-1", "10.0.0.1", null,
                                     Map.of("aether-cluster", "test-cluster", "aether-port", "9200")))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(AwsDiscoveryProviderTest::assertSinglePeerWithPort9200);
        }

        @Test
        void discoverPeers_usesPrivateIpOverPublic() {
            testClient.describeResponse = Promise.success(
                TestAwsClient.describeResponseWith(List.of(
                    instanceWithTags("i-1", "10.0.0.1", "5.6.7.8",
                                     Map.of("aether-cluster", "test-cluster")))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().host()).isEqualTo("10.0.0.1"));
        }

        @Test
        void discoverPeers_usesPublicIpWhenNoPrivate() {
            testClient.describeResponse = Promise.success(
                TestAwsClient.describeResponseWith(List.of(
                    instanceWithTags("i-1", null, "5.6.7.8",
                                     Map.of("aether-cluster", "test-cluster")))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().host()).isEqualTo("5.6.7.8"));
        }

        @Test
        void discoverPeers_defaultsPortTo9100() {
            testClient.describeResponse = Promise.success(
                TestAwsClient.describeResponseWith(List.of(
                    instanceWithTags("i-1", "10.0.0.1", null,
                                     Map.of("aether-cluster", "test")))));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers.getFirst().port()).isEqualTo(9100));
        }

        @Test
        void discoverPeers_usesClusterTagFilter() {
            testClient.describeResponse = Promise.success(TestAwsClient.emptyDescribeResponse());

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastDescribeTagKey).isEqualTo("aether-cluster");
            assertThat(testClient.lastDescribeTagValue).isEqualTo("test-cluster");
        }

        @Test
        void discoverPeers_filtersNonRunningInstances() {
            var stoppedInstance = new Instance("i-2", "t3.medium", "ami-12345",
                                               "10.0.0.2", null,
                                               new Instance.InstanceState("stopped", 80),
                                               tagSet(Map.of("aether-cluster", "test-cluster")));
            var runningInstance = instanceWithTags("i-1", "10.0.0.1", null,
                                                   Map.of("aether-cluster", "test-cluster"));

            testClient.describeResponse = Promise.success(
                TestAwsClient.describeResponseWith(List.of(runningInstance, stoppedInstance)));

            provider.discoverPeers()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(peers -> assertThat(peers).hasSize(1));
        }

        @Test
        void discoverPeers_failure_mapsToDiscoveryError() {
            testClient.describeResponse = new AwsError.ApiError(500, "InternalError", "Fail").promise();

            provider.discoverPeers()
                    .await()
                    .onSuccess(peers -> assertThat(peers).isNull())
                    .onFailure(AwsDiscoveryProviderTest::assertDiscoveryFailed);
        }
    }

    @Nested
    class RegisterDeregisterTests {

        @Test
        void registerSelf_returnsOperationNotSupported() {
            var self = new PeerInfo("10.0.0.1", 9100, Map.of());

            provider.registerSelf(self)
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(AwsDiscoveryProviderTest::assertOperationNotSupported);
        }

        @Test
        void deregisterSelf_returnsOperationNotSupported() {
            provider.deregisterSelf()
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(AwsDiscoveryProviderTest::assertOperationNotSupported);
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

    private static void assertDiscoveryFailed(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.DiscoveryFailed.class);
    }

    // --- Instance factory helpers ---

    private static Instance instanceWithTags(String instanceId, String privateIp, String publicIp,
                                              Map<String, String> tags) {
        return new Instance(instanceId, "t3.medium", "ami-12345",
                            privateIp, publicIp,
                            new Instance.InstanceState("running", 16),
                            tagSet(tags));
    }

    private static Instance.TagSet tagSet(Map<String, String> tags) {
        var tagItems = tags.entrySet()
                           .stream()
                           .map(e -> new Instance.Tag(e.getKey(), e.getValue()))
                           .toList();
        return new Instance.TagSet(tagItems);
    }
}
