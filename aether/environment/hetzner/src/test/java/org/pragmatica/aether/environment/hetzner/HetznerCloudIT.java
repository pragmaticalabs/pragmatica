package org.pragmatica.aether.environment.hetzner;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Cause;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.InstanceId.instanceId;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

/// Integration tests for the Hetzner Cloud provider.
/// Requires HETZNER_API_TOKEN environment variable to be set.
/// Run with: mvn verify -Djbct.skip=true -Pwith-cloud-tests -pl aether/environment/hetzner -am
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HetznerCloudIT {

    static HetznerClient client;
    static HetznerComputeProvider provider;
    static long createdServerId;

    @BeforeAll
    static void setUp() {
        var token = System.getenv("HETZNER_API_TOKEN");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "HETZNER_API_TOKEN not set");

        var config = hetznerConfig(token);
        client = HetznerClient.hetznerClient(config);

        var envConfig = HetznerEnvironmentConfig.hetznerEnvironmentConfig(
            config, "cx22", "ubuntu-24.04", "fsn1",
            List.of(), List.of(), List.of(), "");

        provider = HetznerComputeProvider.hetznerComputeProvider(client, envConfig);
    }

    @Test
    @Order(1)
    void listInstances_returnsWithoutError() {
        provider.listInstances()
                .await()
                .onFailure(HetznerCloudIT::failWithCause);
    }

    @Test
    @Order(2)
    void provision_createsServer() {
        provider.provision(InstanceType.ON_DEMAND)
                .await()
                .onFailure(HetznerCloudIT::failWithCause)
                .onSuccess(HetznerCloudIT::captureCreatedServer);
    }

    @Test
    @Order(3)
    void instanceStatus_returnsInfo() {
        Assumptions.assumeTrue(createdServerId > 0, "No server created in previous test");

        provider.instanceStatus(instanceId(String.valueOf(createdServerId)))
                .await()
                .onFailure(HetznerCloudIT::failWithCause)
                .onSuccess(HetznerCloudIT::assertMatchesCreatedServer);
    }

    @Test
    @Order(4)
    void terminate_deletesServer() {
        Assumptions.assumeTrue(createdServerId > 0, "No server created");

        provider.terminate(instanceId(String.valueOf(createdServerId)))
                .await()
                .onFailure(HetznerCloudIT::failWithCause);
    }

    @Test
    @Order(10)
    void listSshKeys_works() {
        client.listSshKeys()
              .await()
              .onFailure(HetznerCloudIT::failWithCause)
              .onSuccess(HetznerCloudIT::assertNonNullList);
    }

    @Test
    @Order(11)
    void listNetworks_works() {
        client.listNetworks()
              .await()
              .onFailure(HetznerCloudIT::failWithCause)
              .onSuccess(HetznerCloudIT::assertNonNullList);
    }

    @Test
    @Order(12)
    void listFirewalls_works() {
        client.listFirewalls()
              .await()
              .onFailure(HetznerCloudIT::failWithCause)
              .onSuccess(HetznerCloudIT::assertNonNullList);
    }

    @Test
    @Order(13)
    void listLoadBalancers_works() {
        client.listLoadBalancers()
              .await()
              .onFailure(HetznerCloudIT::failWithCause)
              .onSuccess(HetznerCloudIT::assertNonNullList);
    }

    // --- Assertion helpers ---

    private static void failWithCause(Cause cause) {
        fail("Operation failed: " + cause.message());
    }

    private static void captureCreatedServer(InstanceInfo info) {
        createdServerId = Long.parseLong(info.id().value());
        assertThat(info.status()).isNotNull();
    }

    private static void assertMatchesCreatedServer(InstanceInfo info) {
        assertThat(info.id().value()).isEqualTo(String.valueOf(createdServerId));
    }

    private static <T> void assertNonNullList(List<T> list) {
        assertThat(list).isNotNull();
    }
}
