package org.pragmatica.aether.environment.hetzner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Firewall;
import org.pragmatica.cloud.hetzner.api.LoadBalancer;
import org.pragmatica.cloud.hetzner.api.Network;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

class HetznerComputeProviderTest {

    private static final HetznerEnvironmentConfig CONFIG = HetznerEnvironmentConfig.hetznerEnvironmentConfig(
        hetznerConfig("test-token"),
        "cx22", "ubuntu-24.04", "fsn1",
        List.of(1L, 2L), List.of(10L), List.of(5L),
        "#!/bin/bash\necho hello").unwrap();

    private TestHetznerClient testClient;
    private HetznerComputeProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestHetznerClient();
        provider = HetznerComputeProvider.hetznerComputeProvider(testClient, CONFIG).unwrap();
    }

    @Nested
    class ProvisionTests {

        @Test
        void provision_success_returnsInstanceInfo() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(HetznerComputeProviderTest::assertProvisionedInstanceInfo);
        }

        @Test
        void provision_failure_mapsToEnvironmentError() {
            testClient.createServerResponse = new HetznerError.ApiError(500, "server_error", "Internal error").promise();

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(HetznerComputeProviderTest::assertProvisionFailedError);
        }
    }

    @Nested
    class TerminateTests {

        @Test
        void terminate_success_returnsUnit() {
            testClient.deleteServerResponse = Promise.success(Unit.unit());

            provider.terminate(new InstanceId("42"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastDeletedServerId).isEqualTo(42L);
        }

        @Test
        void terminate_failure_mapsToEnvironmentError() {
            testClient.deleteServerResponse = new HetznerError.ApiError(404, "not_found", "Not found").promise();

            provider.terminate(new InstanceId("99"))
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(HetznerComputeProviderTest::assertTerminateFailedError);
        }
    }

    @Nested
    class ListInstancesTests {

        @Test
        void listInstances_success_returnsMappedList() {
            testClient.listServersResponse = Promise.success(List.of(
                runningServer(1, "server-1"),
                initializingServer(2, "server-2")));

            provider.listInstances()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(HetznerComputeProviderTest::assertTwoInstanceList);
        }

        @Test
        void listInstances_empty_returnsEmptyList() {
            testClient.listServersResponse = Promise.success(List.of());

            provider.listInstances()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances).isEmpty());
        }

        @Test
        void listInstances_failure_mapsToEnvironmentError() {
            testClient.listServersResponse = new HetznerError.ApiError(500, "server_error", "Fail").promise();

            provider.listInstances()
                    .await()
                    .onSuccess(list -> assertThat(list).isNull())
                    .onFailure(HetznerComputeProviderTest::assertListInstancesFailedError);
        }
    }

    @Nested
    class InstanceStatusTests {

        @Test
        void instanceStatus_success_returnsInstanceInfo() {
            testClient.getServerResponse = Promise.success(runningServer(42, "my-server"));

            provider.instanceStatus(new InstanceId("42"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(HetznerComputeProviderTest::assertRunningInstance42);

            assertThat(testClient.lastGetServerId).isEqualTo(42L);
        }

        @Test
        void instanceStatus_failure_mapsToEnvironmentError() {
            testClient.getServerResponse = new HetznerError.ApiError(404, "not_found", "Not found").promise();

            provider.instanceStatus(new InstanceId("999"))
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(HetznerComputeProviderTest::assertProvisionFailedError);
        }
    }

    @Nested
    class StatusMappingTests {

        @Test
        void mapStatus_initializing_returnsProvisioning() {
            assertThat(HetznerComputeProvider.mapStatus("initializing")).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapStatus_starting_returnsProvisioning() {
            assertThat(HetznerComputeProvider.mapStatus("starting")).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapStatus_running_returnsRunning() {
            assertThat(HetznerComputeProvider.mapStatus("running")).isEqualTo(InstanceStatus.RUNNING);
        }

        @Test
        void mapStatus_stopping_returnsStopping() {
            assertThat(HetznerComputeProvider.mapStatus("stopping")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_off_returnsStopping() {
            assertThat(HetznerComputeProvider.mapStatus("off")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_deleting_returnsStopping() {
            assertThat(HetznerComputeProvider.mapStatus("deleting")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_unknown_returnsTerminated() {
            assertThat(HetznerComputeProvider.mapStatus("unknown")).isEqualTo(InstanceStatus.TERMINATED);
        }
    }

    @Nested
    class AddressCollectionTests {

        @Test
        void collectAddresses_withPublicAndPrivate_returnsBoth() {
            var server = serverWithAddresses("1.2.3.4", List.of("10.0.0.1", "10.0.0.2"));
            var addresses = HetznerComputeProvider.collectAddresses(server);

            assertThat(addresses).containsExactly("1.2.3.4", "10.0.0.1", "10.0.0.2");
        }

        @Test
        void collectAddresses_publicOnly_returnsPublic() {
            var server = serverWithAddresses("1.2.3.4", List.of());
            var addresses = HetznerComputeProvider.collectAddresses(server);

            assertThat(addresses).containsExactly("1.2.3.4");
        }

        @Test
        void collectAddresses_noAddresses_returnsEmpty() {
            var server = new Server(1, "test", "running", serverType(), image(), null, null);
            var addresses = HetznerComputeProvider.collectAddresses(server);

            assertThat(addresses).isEmpty();
        }
    }

    @Nested
    class EnvironmentIntegrationTests {

        @Test
        void compute_returnsProvider() {
            var integration = HetznerEnvironmentIntegration.hetznerEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.compute().isPresent()).isTrue();
        }

        @Test
        void secrets_returnsEmpty() {
            var integration = HetznerEnvironmentIntegration.hetznerEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.secrets().isPresent()).isFalse();
        }
    }

    // --- Assertion helpers ---

    private static void assertProvisionedInstanceInfo(org.pragmatica.aether.environment.InstanceInfo info) {
        assertThat(info.id().value()).isEqualTo("42");
        assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(info.addresses()).contains("1.2.3.4");
        assertThat(info.type()).isEqualTo(InstanceType.ON_DEMAND);
    }

    private static void assertRunningInstance42(org.pragmatica.aether.environment.InstanceInfo info) {
        assertThat(info.id().value()).isEqualTo("42");
        assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING);
    }

    private static void assertProvisionFailedError(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.ProvisionFailed.class);
    }

    private static void assertTerminateFailedError(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.TerminateFailed.class);
    }

    private static void assertListInstancesFailedError(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.ListInstancesFailed.class);
    }

    private static void assertTwoInstanceList(java.util.List<org.pragmatica.aether.environment.InstanceInfo> instances) {
        assertThat(instances).hasSize(2);
        assertThat(instances.get(0).id().value()).isEqualTo("1");
        assertThat(instances.get(0).status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instances.get(1).id().value()).isEqualTo("2");
        assertThat(instances.get(1).status()).isEqualTo(InstanceStatus.PROVISIONING);
    }

    // --- Server factory helpers ---

    private static Server runningServer(long id, String name) {
        return new Server(id, name, "running", serverType(), image(),
                          publicNet("1.2.3.4"), List.of());
    }

    private static Server initializingServer(long id, String name) {
        return new Server(id, name, "initializing", serverType(), image(),
                          publicNet("5.6.7.8"), List.of());
    }

    private static Server serverWithAddresses(String publicIp, List<String> privateIps) {
        var privateNets = privateIps.stream()
                                    .map(ip -> new Server.PrivateNet(1L, ip))
                                    .toList();
        return new Server(1, "test", "running", serverType(), image(),
                          publicNet(publicIp), privateNets);
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

    /// Test stub for HetznerClient that returns canned responses.
    static final class TestHetznerClient implements HetznerClient {
        Promise<Server> createServerResponse = Promise.success(runningServer(1, "default"));
        Promise<Unit> deleteServerResponse = Promise.success(Unit.unit());
        Promise<Server> getServerResponse = Promise.success(runningServer(1, "default"));
        Promise<List<Server>> listServersResponse = Promise.success(List.of());

        long lastDeletedServerId;
        long lastGetServerId;

        @Override
        public Promise<Server> createServer(CreateServerRequest request) {
            return createServerResponse;
        }

        @Override
        public Promise<Unit> deleteServer(long serverId) {
            lastDeletedServerId = serverId;
            return deleteServerResponse;
        }

        @Override
        public Promise<Server> getServer(long serverId) {
            lastGetServerId = serverId;
            return getServerResponse;
        }

        @Override
        public Promise<List<Server>> listServers() {
            return listServersResponse;
        }

        @Override
        public Promise<SshKey> createSshKey(SshKey.CreateSshKeyRequest request) {
            return Promise.success(new SshKey(1, "test-key", "aa:bb:cc", "ssh-ed25519 AAAA"));
        }

        @Override
        public Promise<Unit> deleteSshKey(long sshKeyId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<SshKey>> listSshKeys() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<List<Network>> listNetworks() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Network> getNetwork(long networkId) {
            return Promise.success(new Network(networkId, "test-net", "10.0.0.0/8", List.of()));
        }

        @Override
        public Promise<List<Firewall>> listFirewalls() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Unit> applyFirewall(long firewallId, long serverId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<LoadBalancer> createLoadBalancer(LoadBalancer.CreateLoadBalancerRequest request) {
            return Promise.success(new LoadBalancer(1, "test-lb",
                                                    new LoadBalancer.LbType(1, "lb11", "LB11"),
                                                    new LoadBalancer.Algorithm("round_robin"),
                                                    List.of()));
        }

        @Override
        public Promise<Unit> deleteLoadBalancer(long loadBalancerId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<LoadBalancer>> listLoadBalancers() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Unit> addTarget(long loadBalancerId, long serverId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> removeTarget(long loadBalancerId, long serverId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> addIpTarget(long loadBalancerId, String ip) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> removeIpTarget(long loadBalancerId, String ip) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<LoadBalancer> getLoadBalancer(long loadBalancerId) {
            return Promise.success(new LoadBalancer(loadBalancerId, "test-lb",
                                                    new LoadBalancer.LbType(1, "lb11", "LB11"),
                                                    new LoadBalancer.Algorithm("round_robin"),
                                                    List.of()));
        }
    }
}
