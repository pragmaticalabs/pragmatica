// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.environment.hetzner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.CloudProviderSupport;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Firewall;
import org.pragmatica.cloud.hetzner.api.FloatingIp;
import org.pragmatica.cloud.hetzner.api.LoadBalancer;
import org.pragmatica.cloud.hetzner.api.Network;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

class HetznerComputeProviderTest {

    private static final HetznerEnvironmentConfig CONFIG = HetznerEnvironmentConfig.hetznerEnvironmentConfig(
        hetznerConfig("test-token"),
        "cx22", "ubuntu-24.04", "fsn1",
        List.of(1L, 2L), List.of(10L), List.of(5L),
        "#!/bin/bash\necho hello").unwrap();

    private static HetznerEnvironmentConfig configWith(String serverType, List<Long> sshKeyIds) {
        return HetznerEnvironmentConfig.hetznerEnvironmentConfig(
            hetznerConfig("test-token"),
            serverType, "ubuntu-24.04", "fsn1",
            sshKeyIds, List.of(10L), List.of(5L),
            "#!/bin/bash\necho hello").unwrap();
    }

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

        @Test
        void provision_capacityUnavailable_mapsToCapacityUnavailableWithAttemptedZone() {
            // Hetzner placement-capacity signal: 412 with code "resource_unavailable"
            // ("error during placement"). Must surface as the RETRYABLE CapacityUnavailable
            // carrying the attempted zone so the bootstrap can rotate to the next zone.
            testClient.createServerResponse =
                new HetznerError.ApiError(412, "resource_unavailable", "error during placement").promise();
            var context = ProvisionContext.provisionContext("cluster-x",
                                                             "core",
                                                             "eu-1",
                                                             ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context)
                                    .unwrap()
                                    .withPlacement(PlacementHint.zoneHint("nbg1"));

            provider.provision(spec)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(HetznerComputeProviderTest::assertCapacityUnavailableInNbg1);
        }

        @Test
        void provision_nonCapacityApiError_mapsToProvisionFailed() {
            // A 412 with a DIFFERENT code (or any other status) is NOT a capacity signal —
            // it must stay ProvisionFailed (non-retryable), so rotation does not waste attempts.
            testClient.createServerResponse =
                new HetznerError.ApiError(412, "uniqueness_error", "server name taken").promise();

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(HetznerComputeProviderTest::assertProvisionFailedError);
        }

        @Test
        void provision_contextWithNodeId_setsAetherNodeIdLabelOnServer() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            var context = ProvisionContext.provisionContext("cluster-x",
                                                              "core",
                                                              "eu-1",
                                                              ProvisionContext.PROVISIONED_BY_CTM)
                                                       .withNodeId("aether-core-node-test123");
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();

            provider.provision(spec).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest).isNotNull();
            assertThat(testClient.lastCreateServerRequest.labels())
                    .containsEntry(HetznerComputeProvider.NODE_ID_LABEL, "aether-core-node-test123")
                    .containsEntry("aether-cluster", "cluster-x")
                    .containsEntry("aether-role", "core")
                    .containsEntry("aether-source", "eu-1");
        }

        @Test
        void listInstances_withDottedNodeIdTag_translatesToHetznerLabel() {
            testClient.listServersResponse = Promise.success(List.of(
                serverWithLabels(7, "matched", Map.of(HetznerComputeProvider.NODE_ID_LABEL, "aether-core-node-x"))));

            provider.listInstances(Map.of(HetznerComputeProvider.UPPER_LAYER_NODE_ID_TAG, "aether-core-node-x"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances).hasSize(1));

            assertThat(testClient.lastLabelSelector)
                    .as("upper-layer dotted aether.node-id translated to native hyphenated form")
                    .isEqualTo("aether-node-id=aether-core-node-x");
        }
    }

    @Nested
    class ProfileInheritanceTests {

        @Test
        void provision_specConcreteInstanceType_usedAsServerType() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));

            provider.provision(ctmSpec("ccx23")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.serverType()).isEqualTo("ccx23");
        }

        @Test
        void provision_specDefaultSentinel_fallsBackToConfigServerType() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));

            provider.provision(ctmSpec("default")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.serverType()).isEqualTo("cx22");
        }

        @Test
        void provision_noServerTypeResolvable_failsLoudWithoutHardcodedDefault() {
            var typelessProvider = HetznerComputeProvider.hetznerComputeProvider(testClient,
                                                                                configWith("", List.of(1L, 2L))).unwrap();

            typelessProvider.provision(ctmSpec("default"))
                            .await()
                            .onSuccess(info -> assertThat(info).isNull())
                            .onFailure(HetznerComputeProviderTest::assertProvisionFailedError);

            assertThat(testClient.lastCreateServerRequest)
                    .as("provision must fail before createServer — no cx33 fallback")
                    .isNull();
        }

        @Test
        void provision_configHasSshKeyIds_usedWithoutLookup() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));

            provider.provision(ctmSpec("cx22")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.sshKeys()).containsExactly(1L, 2L);
            assertThat(testClient.listSshKeysCalled)
                    .as("config already carries ssh_key_ids — no provider-side lookup")
                    .isFalse();
        }

        @Test
        void provision_emptyConfigSshKeyIds_looksUpBootstrapPrefixedKeys() {
            var keylessProvider = HetznerComputeProvider.hetznerComputeProvider(testClient,
                                                                               configWith("cx22", List.of())).unwrap();
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            testClient.listSshKeysResponse = Promise.success(List.of(
                new SshKey(7, "aether-bootstrap-op", "aa:bb", "ssh-ed25519 AAAA"),
                new SshKey(9, "someones-laptop", "cc:dd", "ssh-ed25519 BBBB")));

            keylessProvider.provision(ctmSpec("cx22")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.listSshKeysCalled).isTrue();
            assertThat(testClient.lastCreateServerRequest.sshKeys())
                    .as("only aether-bootstrap-prefixed keys are attached")
                    .containsExactly(7L);
        }

        @Test
        void provision_emptyConfigSshKeyIdsAndNoBootstrapKeys_createsWithoutSshKeys() {
            var keylessProvider = HetznerComputeProvider.hetznerComputeProvider(testClient,
                                                                               configWith("cx22", List.of())).unwrap();
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            testClient.listSshKeysResponse = Promise.success(List.of(
                new SshKey(9, "someones-laptop", "cc:dd", "ssh-ed25519 BBBB")));

            keylessProvider.provision(ctmSpec("cx22")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.sshKeys()).isEmpty();
        }

        @Test
        void createServerPayload_serializesServerTypeSshKeysAndLabels() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));

            provider.provision(ctmSpec("ccx23")).await().onFailure(cause -> assertThat(cause).isNull());

            var json = JsonMapper.defaultJsonMapper().writeAsString(testClient.lastCreateServerRequest).unwrap();

            assertThat(json)
                    .contains("\"server_type\":\"ccx23\"")
                    .contains("\"ssh_keys\":[1,2]")
                    .contains("\"labels\":")
                    .contains("aether-cluster")
                    .contains("aether-node-id");
        }

        private ProvisionSpec ctmSpec(String instanceSize) {
            var context = ProvisionContext.provisionContext("cluster-x",
                                                            "core",
                                                            "eu-1",
                                                            ProvisionContext.PROVISIONED_BY_CTM)
                                          .withNodeId("aether-core-node-test123");

            return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, instanceSize, "core", context).unwrap();
        }
    }

    @Nested
    class LabelWiringTests {

        @Test
        void bootstrapContext_stampsRealClusterAndRole() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            var context = ProvisionContext.forBootstrap("prod-cluster", "core", "eu-1", "eu-1-core-0");
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();

            provider.provision(spec).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.labels())
                    .containsEntry("aether-cluster", "prod-cluster")
                    .containsEntry("aether-role", "core")
                    .containsEntry(HetznerComputeProvider.NODE_ID_LABEL, "eu-1-core-0");
        }

        @Test
        void replacementContext_stampsRealClusterAndRole() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            var context = ProvisionContext.forReplacement("prod-cluster", "core", "node-abc", "peers", 5);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();

            provider.provision(spec).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.labels())
                    .containsEntry("aether-cluster", "prod-cluster")
                    .containsEntry("aether-role", "core")
                    .containsEntry(HetznerComputeProvider.NODE_ID_LABEL, "node-abc");
        }

        @Test
        void waveNodeGroupWithClusterTag_stampsRealCluster() {
            // #442 v2b — WaveExecutor now threads the real cluster name into the group tags; this
            // proves that input reaches the VM label via CloudProviderSupport.toContext → provider.
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            var group = NodeGroupConfig.nodeGroupConfig("eu-1", "core", 1, "cx22", "fsn1",
                                                        Map.of("aether-cluster", "prod-cluster",
                                                               "aether-source", "eu-1",
                                                               "aether-role", "core"));

            CloudProviderSupport.provisionVia(provider, group).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.labels())
                    .containsEntry("aether-cluster", "prod-cluster")
                    .containsEntry("aether-role", "core");
        }

        @Test
        void emptyClusterContext_fallsBackToConfigDiscoveryName() {
            // On a RUNNING node the provider config carries the cluster name (from [cloud.discovery]
            // cluster_name), so even an empty-tag context resolves a real name rather than "unknown".
            // This is why the wave gap is latent in production and surfaces only when the config name
            // is also absent (e.g. an older jar) — the label is never left blank.
            var configWithName = CONFIG.withDiscovery("prod-cluster");
            var providerWithName = HetznerComputeProvider.hetznerComputeProvider(testClient, configWithName).unwrap();
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            var group = NodeGroupConfig.nodeGroupConfig("eu-1", "core", 1, "cx22", "fsn1", Map.of());

            CloudProviderSupport.provisionVia(providerWithName, group).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.labels().get("aether-cluster"))
                    .as("empty context resolves the config discovery name, never left as 'unknown'")
                    .isNotEqualTo("unknown");
        }

        @Test
        void invalidClusterName_sanitizedToHetznerConstraints() {
            // A cluster name with characters outside Hetzner's label-value alphabet must be coerced
            // deterministically (prefix-preserved) rather than sent raw — a raw invalid value makes
            // Hetzner reject the whole create.
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            var context = ProvisionContext.forBootstrap("my cluster!", "core", "eu-1", "eu-1-core-0");
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();

            provider.provision(spec).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.labels())
                    .containsEntry("aether-cluster", "my-cluster");
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

        @Test
        void listInstances_withTagFilter_usesLabelSelector() {
            testClient.listServersResponse = Promise.success(List.of(
                serverWithLabels(1, "server-1", Map.of("env", "prod"))));

            provider.listInstances(Map.of("env", "prod"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances).hasSize(1));

            assertThat(testClient.lastLabelSelector).isEqualTo("env=prod");
        }
    }

    @Nested
    class RestartTests {

        @Test
        void restart_success_callsReboot() {
            testClient.rebootServerResponse = Promise.success(Unit.unit());

            provider.restart(new InstanceId("42"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastRebootServerId).isEqualTo(42L);
        }
    }

    @Nested
    class ApplyTagsTests {

        @Test
        void applyTags_success_updatesLabels() {
            testClient.updateLabelsResponse = Promise.success(Unit.unit());
            var tags = Map.of("env", "prod", "team", "aether");

            provider.applyTags(new InstanceId("42"), tags)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastUpdateLabelsServerId).isEqualTo(42L);
            assertThat(testClient.lastUpdateLabels).isEqualTo(tags);
        }

        @Test
        void applyTags_mergesWithExistingLabels_preservingClusterRoleSource() {
            // #442 v2b — the node self-stamps ONLY aether-node-id at join
            // (AetherNode.tagMatchingInstance). Hetzner replaces the whole label map, so applyTags
            // MUST merge (read-modify-write) or it wipes the create-stamped cluster/role/source down
            // to just node-id — the exact field symptom. Config-independent: the base set is read
            // from the VM's existing labels, so this also proves the replacement-of-replacement chain.
            testClient.getServerResponse = Promise.success(serverWithLabels(42, "n",
                Map.of("aether-cluster", "cloud-test-b",
                       "aether-role", "core",
                       "aether-source", "hetzner-eu",
                       "aether-node-id", "old-id")));
            testClient.updateLabelsResponse = Promise.success(Unit.unit());

            provider.applyTags(new InstanceId("42"), Map.of("aether-node-id", "new-id"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastUpdateLabels)
                    .as("applyTags MERGES: all four labels survive; node-id is updated, not sole")
                    .containsEntry("aether-cluster", "cloud-test-b")
                    .containsEntry("aether-role", "core")
                    .containsEntry("aether-source", "hetzner-eu")
                    .containsEntry("aether-node-id", "new-id");
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
            var server = new Server(1, "test", "running", serverType(), image(), null, null, Map.of());
            var addresses = HetznerComputeProvider.collectAddresses(server);

            assertThat(addresses).isEmpty();
        }
    }

    @Nested
    class LabelMappingTests {

        @Test
        void toInstanceInfo_mapsLabels() {
            var labels = Map.of("env", "prod", "aether-cluster", "test");
            var server = serverWithLabels(1, "test", labels);
            var info = HetznerComputeProvider.toInstanceInfo(server);

            assertThat(info.tags()).isEqualTo(labels);
        }

        @Test
        void toInstanceInfo_nullLabels_returnsEmptyMap() {
            var server = new Server(1, "test", "running", serverType(), image(),
                                    publicNet("1.2.3.4"), List.of(), null);
            var info = HetznerComputeProvider.toInstanceInfo(server);

            assertThat(info.tags()).isEmpty();
        }

        @Test
        void toLabelSelector_formatsCorrectly() {
            var tags = Map.of("key1", "val1");
            var selector = HetznerComputeProvider.toLabelSelector(tags);

            assertThat(selector).isEqualTo("key1=val1");
        }

        @Test
        void toLabelSelector_emptyMap_returnsEmptyString() {
            assertThat(HetznerComputeProvider.toLabelSelector(Map.of())).isEmpty();
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
        void secrets_returnsEnvProvider() {
            var integration = HetznerEnvironmentIntegration.hetznerEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.secrets().isPresent()).isTrue();
        }

        @Test
        void discovery_presentWhenClusterNameSet() {
            var configWithDiscovery = CONFIG.withDiscovery("my-cluster");
            var integration = HetznerEnvironmentIntegration.hetznerEnvironmentIntegration(testClient, configWithDiscovery).unwrap();

            assertThat(integration.discovery().isPresent()).isTrue();
        }

        @Test
        void discovery_emptyWhenNoClusterName() {
            var integration = HetznerEnvironmentIntegration.hetznerEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.discovery().isPresent()).isFalse();
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

    private static void assertCapacityUnavailableInNbg1(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.CapacityUnavailable.class);
        assertThat(((EnvironmentError.CapacityUnavailable) cause).zone()).isEqualTo("nbg1");
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
                          publicNet("1.2.3.4"), List.of(), Map.of());
    }

    private static Server initializingServer(long id, String name) {
        return new Server(id, name, "initializing", serverType(), image(),
                          publicNet("5.6.7.8"), List.of(), Map.of());
    }

    private static Server serverWithAddresses(String publicIp, List<String> privateIps) {
        var privateNets = privateIps.stream()
                                    .map(ip -> new Server.PrivateNet(1L, ip))
                                    .toList();
        return new Server(1, "test", "running", serverType(), image(),
                          publicNet(publicIp), privateNets, Map.of());
    }

    private static Server serverWithLabels(long id, String name, Map<String, String> labels) {
        return new Server(id, name, "running", serverType(), image(),
                          publicNet("1.2.3.4"), List.of(), labels);
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

    /// Test stub for HetznerClient that returns canned responses and captures arguments.
    static final class TestHetznerClient implements HetznerClient {
        Promise<Server> createServerResponse = Promise.success(runningServer(1, "default"));
        Promise<Unit> deleteServerResponse = Promise.success(Unit.unit());
        Promise<Server> getServerResponse = Promise.success(runningServer(1, "default"));
        Promise<List<Server>> listServersResponse = Promise.success(List.of());
        Promise<Unit> rebootServerResponse = Promise.success(Unit.unit());
        Promise<Unit> updateLabelsResponse = Promise.success(Unit.unit());
        Promise<List<SshKey>> listSshKeysResponse = Promise.success(List.of());

        long lastDeletedServerId;
        long lastGetServerId;
        long lastRebootServerId;
        long lastUpdateLabelsServerId;
        Map<String, String> lastUpdateLabels;
        String lastLabelSelector;
        CreateServerRequest lastCreateServerRequest;
        boolean listSshKeysCalled;

        @Override
        public Promise<Server> createServer(CreateServerRequest request) {
            lastCreateServerRequest = request;
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
        public Promise<List<Server>> listServers(String labelSelector) {
            lastLabelSelector = labelSelector;
            return listServersResponse;
        }

        @Override
        public Promise<Unit> updateServerLabels(long serverId, Map<String, String> labels) {
            lastUpdateLabelsServerId = serverId;
            lastUpdateLabels = labels;
            return updateLabelsResponse;
        }

        @Override
        public Promise<Unit> rebootServer(long serverId) {
            lastRebootServerId = serverId;
            return rebootServerResponse;
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
            listSshKeysCalled = true;
            return listSshKeysResponse;
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

        @Override
        public Promise<List<FloatingIp>> listFloatingIps() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<FloatingIp> getFloatingIp(long floatingIpId) {
            return Promise.success(new FloatingIp(floatingIpId, "1.2.3.4", "ipv4", null, new FloatingIp.Location("fsn1", "Falkenstein", "DE"), Map.of()));
        }

        @Override
        public Promise<Unit> assignFloatingIp(long floatingIpId, long serverId) {
            return Promise.success(Unit.unit());
        }
    }
}
