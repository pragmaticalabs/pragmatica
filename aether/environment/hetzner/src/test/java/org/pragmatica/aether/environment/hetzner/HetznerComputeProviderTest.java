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
import org.pragmatica.aether.environment.MarketOptions;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.HashMap;
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

    private static HetznerEnvironmentConfig configWithImage(String image) {
        return HetznerEnvironmentConfig.hetznerEnvironmentConfig(
            hetznerConfig("test-token"),
            "cx22", image, "fsn1",
            List.of(1L, 2L), List.of(10L), List.of(5L),
            "#!/bin/bash\necho hello").unwrap();
    }

    private TestHetznerClient testClient;
    private HetznerComputeProvider provider;

    /// [ComputeProvider#provision(InstanceType)] is a convenience seed whose context carries NO
    /// cluster name, so under RFC-0017 C2 it is refused unless the provider config supplies one.
    /// Tests exercising provisioning mechanics (image, instance info) use this; the refusal itself
    /// is covered by ClusterLabelPreconditionTests.
    private HetznerComputeProvider seededProvider() {
        return HetznerComputeProvider.hetznerComputeProvider(testClient, CONFIG.withDiscovery("test-cluster"))
                                     .unwrap();
    }

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

            seededProvider().provision(InstanceType.ON_DEMAND)
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
            // RFC-0016 W3 §3.3 — the lookup now matches the CLUSTER-SCOPED prefix
            // `aether-bootstrap-<cluster>` (ctmSpec context cluster = "cluster-x"), never the old
            // account-wide bare `aether-bootstrap`. Key 7 is scoped to this cluster and matches; the
            // laptop key does not. Same INTENT: empty config ids -> name-prefix lookup finds the
            // cluster's keys.
            testClient.listSshKeysResponse = Promise.success(List.of(
                new SshKey(7, "aether-bootstrap-cluster-x-op", "aa:bb", "ssh-ed25519 AAAA"),
                new SshKey(9, "someones-laptop", "cc:dd", "ssh-ed25519 BBBB")));

            keylessProvider.provision(ctmSpec("cx22")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.listSshKeysCalled).isTrue();
            assertThat(testClient.lastCreateServerRequest.sshKeys())
                    .as("only this cluster's aether-bootstrap-<cluster>-prefixed keys are attached")
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
        void provision_emptyConfigSshKeyIds_ignoresOtherClustersBootstrapKeys() {
            // RFC-0016 W3 §3.3 — account-wide bare-prefix guessing is DELETED: a key scoped to a
            // DIFFERENT cluster (`aether-bootstrap-other-cluster-*`) must NOT be attached when
            // provisioning for "cluster-x". Only this cluster's own scoped key (id 7) matches.
            var keylessProvider = HetznerComputeProvider.hetznerComputeProvider(testClient,
                                                                               configWith("cx22", List.of())).unwrap();
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            testClient.listSshKeysResponse = Promise.success(List.of(
                new SshKey(7, "aether-bootstrap-cluster-x-op", "aa:bb", "ssh-ed25519 AAAA"),
                new SshKey(8, "aether-bootstrap-other-cluster-op", "ee:ff", "ssh-ed25519 CCCC")));

            keylessProvider.provision(ctmSpec("cx22")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.sshKeys())
                    .as("a leader resolves ONLY its own cluster's scoped keys, never another cluster's")
                    .containsExactly(7L);
        }

        @Test
        void provision_emptyConfigSshKeyIds_prodDoesNotMatchProductionKeys() {
            // #444 — delimiter-boundary matching (`prefix + "-"`): provisioning for cluster "prod"
            // (prefix `aether-bootstrap-prod`) must NOT attach "production"'s keys. Under the old bare
            // `startsWith` this collided ("aether-bootstrap-production-op".startsWith("aether-bootstrap-prod"));
            // requiring the trailing '-' fixes non-delimiter string-prefix pairs like prod/production.
            var keylessProvider = HetznerComputeProvider.hetznerComputeProvider(testClient,
                                                                               configWith("cx22", List.of())).unwrap();
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            testClient.listSshKeysResponse = Promise.success(List.of(
                new SshKey(7, "aether-bootstrap-prod-op", "aa:bb", "ssh-ed25519 AAAA"),
                new SshKey(8, "aether-bootstrap-production-op", "ee:ff", "ssh-ed25519 CCCC")));
            var context = ProvisionContext.provisionContext("prod", "core", "eu-1", ProvisionContext.PROVISIONED_BY_CTM)
                                          .withNodeId("aether-core-node-prod");
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();

            keylessProvider.provision(spec).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.sshKeys())
                    .as("cluster 'prod' matches only 'aether-bootstrap-prod-*', never 'production'")
                    .containsExactly(7L);
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

        @Test
        void provision_bootstrapSeedWithConfigImage_usedAsCreateServerImage() {
            // #459 — the spec-level [source...] image lands in config.image() (threaded by
            // ProviderResolver for seeds); the bootstrap seed provision must carry it to the create
            // request instead of the hardcoded default.
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));

            seededProvider().provision(InstanceType.ON_DEMAND)
                            .await()
                            .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.image()).isEqualTo("ubuntu-24.04");
        }

        @Test
        void provision_ctmReplacementWithConfigImage_usedAsCreateServerImage() {
            // #459 — a CTM auto-heal replacement resolves the image from the leader's node
            // [cloud.compute] image (config.image()), so the replacement boots the same snapshot.
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));

            provider.provision(ctmSpec("cx22")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.image()).isEqualTo("ubuntu-24.04");
        }

        @Test
        void provision_noImageResolvable_fallsBackToLoudHardcodedDefault() {
            // #459 — unlike server_type (which fails loud), an unresolved image keeps the SAFE stock
            // default so the VM still boots; the fallback is made LOUD via a WARN (not asserted here)
            // so an operator who intended a snapshot sees the stock image was used.
            var imagelessProvider = HetznerComputeProvider.hetznerComputeProvider(testClient,
                                                                                  configWithImage("")).unwrap();
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));

            imagelessProvider.provision(ctmSpec("cx22")).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.image()).isEqualTo("ubuntu-22.04");
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
    class CreateFromTests {

        @Test
        void createFrom_carriesAllResolvedFields_toCreateServerRequest() {
            // The resolved ProvisionRequest is total: createFrom consumes instanceSize/image/zone/
            // userData verbatim (no provider-side re-derivation) and stamps the context labels.
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            var context = ProvisionContext.forBootstrap("prod-cluster", "core", "eu-1", "eu-1-core-0");
            var request = new ProvisionRequest(InstanceType.ON_DEMAND,
                                               "ccx23",
                                               "snapshot-42",
                                               "nbg1",
                                               Option.some("#!/bin/bash\necho boot"),
                                               MarketOptions.ON_DEMAND,
                                               context);

            provider.createFrom(request).await().onFailure(cause -> assertThat(cause).isNull());

            var sent = testClient.lastCreateServerRequest;
            assertThat(sent.serverType()).isEqualTo("ccx23");
            assertThat(sent.image()).isEqualTo("snapshot-42");
            assertThat(sent.location()).isEqualTo("nbg1");
            assertThat(sent.userData()).isEqualTo("#!/bin/bash\necho boot");
            assertThat(sent.labels())
                    .containsEntry("aether-cluster", "prod-cluster")
                    .containsEntry("aether-role", "core")
                    .containsEntry(HetznerComputeProvider.NODE_ID_LABEL, "eu-1-core-0");
        }

        @Test
        void createFrom_absentUserData_sentAsEmptyString() {
            testClient.createServerResponse = Promise.success(runningServer(42, "aether-test"));
            var context = ProvisionContext.forBootstrap("prod", "core", "eu-1", "n0");
            var request = new ProvisionRequest(InstanceType.ON_DEMAND, "cx22", "ubuntu-24.04", "fsn1",
                                               Option.empty(), MarketOptions.ON_DEMAND, context);

            provider.createFrom(request).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.userData()).isEmpty();
        }

        @Test
        void createFrom_spotRequest_rejectedLoudBeforeCreate() {
            // Hetzner has no spot product; a SPOT request (unreachable behind PF-16) must fail loud
            // rather than silently provision an on-demand server — no createServer call is issued.
            var context = ProvisionContext.forBootstrap("prod", "spot", "eu-1", "n0");
            var request = new ProvisionRequest(InstanceType.SPOT, "cx22", "ubuntu-24.04", "fsn1",
                                               Option.empty(), MarketOptions.spot(), context);

            provider.createFrom(request)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(HetznerComputeProviderTest::assertProvisionFailedError);

            assertThat(testClient.lastCreateServerRequest)
                    .as("spot request must fail before createServer")
                    .isNull();
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
            testClient.deleteServerResponse = new HetznerError.ApiError(500, "server_error", "Internal").promise();

            provider.terminate(new InstanceId("99"))
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(HetznerComputeProviderTest::assertTerminateFailedError);
        }

        /// A 404 on delete means the server is ALREADY GONE — the outcome terminate was asked for.
        /// Distinguishing it lets teardown be idempotent, so a destroy that failed for any other
        /// reason can succeed on retry instead of re-reporting "termination failed" forever.
        @Test
        void terminate_whenServerAlreadyGone_mapsToInstanceNotFound() {
            testClient.deleteServerResponse = new HetznerError.ApiError(404, "not_found", "Not found").promise();

            provider.terminate(new InstanceId("99"))
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EnvironmentError.InstanceNotFound.class));
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
        Promise<List<Firewall>> listFirewallsResponse = Promise.success(List.of());
        Promise<Firewall> createFirewallResponse = Promise.success(new Firewall(77,
                                                                                "fw",
                                                                                List.of(),
                                                                                Map.of()));

        String lastFirewallSelector;
        /// Selector-keyed overrides so a test can make the source-scoped lookup and the
        /// cluster-scoped fallback answer DIFFERENTLY — the two-call disambiguation in
        /// `resolveFirewallIds` is exactly the behaviour a single canned response cannot express.
        final Map<String, Promise<List<Firewall>>> firewallsBySelector = new HashMap<>();
        final List<String> firewallSelectors = new ArrayList<>();
        Firewall.CreateFirewallRequest lastCreateFirewallRequest;
        long lastSetRulesFirewallId;
        List<Firewall.Rule> lastSetRules;
        long lastDeletedFirewallId;
        int createFirewallCalls;
        int setFirewallRulesCalls;
        int deleteFirewallCalls;

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
        public Promise<List<Firewall>> listFirewalls(String labelSelector) {
            lastFirewallSelector = labelSelector;
            firewallSelectors.add(labelSelector);

            return firewallsBySelector.getOrDefault(labelSelector, listFirewallsResponse);
        }

        @Override
        public Promise<Firewall> createFirewall(Firewall.CreateFirewallRequest request) {
            lastCreateFirewallRequest = request;
            createFirewallCalls++;
            return createFirewallResponse;
        }

        @Override
        public Promise<Unit> setFirewallRules(long firewallId, List<Firewall.Rule> rules) {
            lastSetRulesFirewallId = firewallId;
            lastSetRules = rules;
            setFirewallRulesCalls++;
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> deleteFirewall(long firewallId) {
            lastDeletedFirewallId = firewallId;
            deleteFirewallCalls++;
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> removeFirewallFromResources(long firewallId, long serverId) {
            return Promise.success(Unit.unit());
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

    /// REQ-5.1.8.4. The risk here is not "does a rule appear" but "can this ever touch a firewall
    /// Aether did not create, drop a rule it did not own, or leave an unreclaimable resource behind"
    /// — the 2026-08-03 test-pg incident (#572) is what the last one costs.
    /// RFC-0017 C2 / #579. A server labelled `aether-cluster=unknown` is invisible to every scoped
    /// cleanup path, so it leaks as a billable orphan. Refusing to create it is the only cheap moment.
    @Nested
    class ClusterLabelPreconditionTests {

        @Test
        void createFrom_whenNoClusterNameResolves_refusesToCreateServer() {
            var context = ProvisionContext.provisionContext("", "core", "", ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();

            provider.provision(spec)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(cause -> assertThat(cause.message()).contains("aether-cluster=unknown")
                                                                  .contains("Refusing to provision"));

            assertThat(testClient.lastCreateServerRequest)
                    .as("no server may be created when its cluster cannot be identified")
                    .isNull();
        }

        @Test
        void createFrom_whenClusterNamePresent_stampsItAndCreates() {
            var context = ProvisionContext.provisionContext("prod-eu", "core", "", ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();

            provider.provision(spec)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.labels()).containsEntry("aether-cluster", "prod-eu");
        }
    }

    /// #444 residual — a CTM auto-heal replacement is built from a `SourceProfile`, which persists
    /// firewall RULES but never the created firewall's ID, so `config.firewallIds()` is empty on
    /// that path and every replacement used to be created with NO firewall association. A Hetzner
    /// server without one accepts ALL inbound traffic.
    ///
    /// The resolution is by label, and the interesting half is what happens when it finds nothing:
    /// "this source manages no ingress" (create — its bootstrap peers are equally open) and "a
    /// firewall exists but this source name did not select it" (refuse — its peers ARE firewalled)
    /// are indistinguishable from the source-scoped lookup alone, and are separated by a
    /// cluster-scoped second look.
    @Nested
    class FirewallAssociationTests {
        private static final String CLUSTER = "prod-eu";
        private static final String SOURCE = "hetzner-eu";
        private static final String SOURCE_SELECTOR = "aether-cluster=prod-eu,aether-source=hetzner-eu";
        private static final String CLUSTER_SELECTOR = "aether-cluster=prod-eu";

        /// Empty `firewallIds` is the CTM auto-heal shape — the bootstrap path is the one that
        /// carries them.
        private HetznerComputeProvider replacementProvider() {
            var config = HetznerEnvironmentConfig.hetznerEnvironmentConfig(hetznerConfig("test-token"),
                                                                            "cx22",
                                                                            "ubuntu-24.04",
                                                                            "fsn1",
                                                                            List.of(1L),
                                                                            List.of(10L),
                                                                            List.of(),
                                                                            "").unwrap();

            return HetznerComputeProvider.hetznerComputeProvider(testClient, config).unwrap();
        }

        private ProvisionRequest replacementRequest(String sourceName) {
            return new ProvisionRequest(InstanceType.ON_DEMAND,
                                        "cx22",
                                        "ubuntu-24.04",
                                        "fsn1",
                                        Option.empty(),
                                        MarketOptions.ON_DEMAND,
                                        ProvisionContext.forReplacement(CLUSTER,
                                                                        "core",
                                                                        sourceName,
                                                                        "node-01",
                                                                        "node-00:10.0.0.1:8090",
                                                                        3));
        }

        private static Firewall firewall(long id) {
            return new Firewall(id, "aether-prod-eu-hetzner-eu", List.of(), Map.of());
        }

        @Test
        void createFrom_whenConfigCarriesFirewallIds_usesThemWithoutLookup() {
            // The bootstrap path is unchanged: ProviderResolver already threaded the just-created
            // ids in, and they must win outright — no lookup, no chance of resolving differently.
            provider.createFrom(replacementRequest(SOURCE)).await().onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.firewalls()).extracting(CreateServerRequest.FirewallRef::firewall)
                                                                     .containsExactly(5L);
            assertThat(testClient.firewallSelectors).as("configured ids must short-circuit the lookup entirely")
                                                   .isEmpty();
        }

        @Test
        void createFrom_whenSourceFirewallResolves_attachesItToCreate() {
            // The defect itself: this is the association that was silently absent on every
            // CTM-provisioned replacement.
            testClient.firewallsBySelector.put(SOURCE_SELECTOR, Promise.success(List.of(firewall(91))));

            replacementProvider().createFrom(replacementRequest(SOURCE))
                                 .await()
                                 .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest.firewalls()).extracting(CreateServerRequest.FirewallRef::firewall)
                                                                     .containsExactly(91L);
        }

        @Test
        void createFrom_whenClusterManagesNoIngress_createsUnfirewalled() {
            // PF-23 explicitly endorses "manage ingress via your own security groups", so a source
            // with no `allow_ingress` has no firewall by design. Its bootstrap nodes are equally
            // unfirewalled — refusing here would kill auto-heal for a supported configuration and
            // buy no security at all.
            testClient.firewallsBySelector.put(SOURCE_SELECTOR, Promise.success(List.of()));
            testClient.firewallsBySelector.put(CLUSTER_SELECTOR, Promise.success(List.of()));

            replacementProvider().createFrom(replacementRequest(SOURCE))
                                 .await()
                                 .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.lastCreateServerRequest).isNotNull();
            assertThat(testClient.lastCreateServerRequest.firewalls()).isEmpty();
            assertThat(testClient.firewallSelectors).as("the cluster-scoped second look is what proves ingress is unmanaged")
                                                   .containsExactly(SOURCE_SELECTOR, CLUSTER_SELECTOR);
        }

        @Test
        void createFrom_whenClusterHasFirewallsButNoneForSource_refusesToCreate() {
            // The `replacementSourceName` -> "default" degradation: a firewall for this cluster
            // exists, this provision just failed to select it. Its peers ARE firewalled, so
            // creating the server would produce exactly the publicly-reachable node #444 is about.
            testClient.firewallsBySelector.put("aether-cluster=prod-eu,aether-source=default",
                                               Promise.success(List.of()));
            testClient.firewallsBySelector.put(CLUSTER_SELECTOR, Promise.success(List.of(firewall(91))));

            replacementProvider().createFrom(replacementRequest(ProvisionContext.DEFAULT_SOURCE_NAME))
                                 .await()
                                 .onSuccess(info -> assertThat(info).isNull())
                                 .onFailure(cause -> assertThat(cause.message()).contains("Refusing to provision")
                                                                                .contains("accept ALL inbound"));

            assertThat(testClient.lastCreateServerRequest).as("no server may be created less firewalled than its peers")
                                                          .isNull();
        }

        @Test
        void createFrom_whenFirewallLookupFails_refusesToCreate() {
            // Unknown firewall state is not evidence of a safe one. Fail rather than proceed.
            testClient.firewallsBySelector.put(SOURCE_SELECTOR,
                                               new HetznerError.ApiError(503, "unavailable", "service unavailable").promise());

            replacementProvider().createFrom(replacementRequest(SOURCE))
                                 .await()
                                 .onSuccess(info -> assertThat(info).isNull())
                                 .onFailure(cause -> assertThat(cause.message()).contains("Refusing to provision")
                                                                                .contains("UNKNOWN firewall state"));

            assertThat(testClient.lastCreateServerRequest).isNull();
        }
    }

    @Nested
    class OpenIngressTests {
        private static final String CLUSTER = "prod-eu";
        private static final String SOURCE = "hetzner-eu";

        private HetznerComputeProvider ingressProvider;

        @BeforeEach
        void setUpIngress() {
            ingressProvider = HetznerComputeProvider.hetznerComputeProvider(testClient,
                                                                            CONFIG.withDiscovery(CLUSTER))
                                                    .unwrap();
        }

        private static Firewall.Rule rule(int port, String protocol, String cidr) {
            return Firewall.Rule.inbound(port, protocol, cidr, "existing");
        }

        private static Firewall firewallWith(Firewall.Rule... rules) {
            return new Firewall(77, "aether-prod-eu-hetzner-eu", List.of(rules), Map.of());
        }

        @Test
        void openIngress_whenNoFirewallExists_createsFirewallLabelledForCleanup() {
            ingressProvider.openIngress(SOURCE, 8070, "tcp", "0.0.0.0/0", "app_http")
                           .await()
                           .onFailure(cause -> assertThat(cause).isNull())
                           .onSuccess(handle -> assertThat(handle.providerResourceId()).isEqualTo("77"));

            assertThat(testClient.createFirewallCalls).isEqualTo(1);
            // Without BOTH labels the firewall is invisible to tools/cloud-reaper.sh and leaks.
            assertThat(testClient.lastCreateFirewallRequest.labels()).containsEntry("aether-cluster", CLUSTER)
                                                                     .containsEntry("aether-source", SOURCE);
            assertThat(testClient.lastCreateFirewallRequest.rules()).singleElement()
                                                                     .satisfies(created -> {
                                                                         assertThat(created.direction()).isEqualTo("in");
                                                                         assertThat(created.port()).isEqualTo("8070");
                                                                         assertThat(created.protocol()).isEqualTo("tcp");
                                                                         assertThat(created.sourceIps()).containsExactly("0.0.0.0/0");
                                                                     });
        }

        @Test
        void openIngress_scopesLookupToBothClusterAndSource() {
            ingressProvider.openIngress(SOURCE, 8070, "tcp", "0.0.0.0/0", "app_http").await();

            assertThat(testClient.lastFirewallSelector).isEqualTo("aether-cluster=prod-eu,aether-source=hetzner-eu");
        }

        /// REQ-5.1.8.1 — "rules not listed are not touched". Hetzner has no add-one-rule action, so a
        /// patch that sent only the new rule would silently wipe every other rule on the firewall.
        @Test
        void openIngress_whenFirewallExists_sendsUnionOfRulesRatherThanReplacing() {
            var preexisting = rule(9000, "tcp", "10.0.0.0/8");

            testClient.listFirewallsResponse = Promise.success(List.of(firewallWith(preexisting)));

            ingressProvider.openIngress(SOURCE, 8070, "udp", "0.0.0.0/0", "app_http")
                           .await()
                           .onFailure(cause -> assertThat(cause).isNull())
                           .onSuccess(handle -> assertThat(handle.providerResourceId()).isEqualTo("77"));

            assertThat(testClient.createFirewallCalls).isZero();
            assertThat(testClient.lastSetRulesFirewallId).isEqualTo(77);
            assertThat(testClient.lastSetRules).hasSize(2)
                                                .anySatisfy(kept -> assertThat(kept.port()).isEqualTo("9000"))
                                                .anySatisfy(added -> assertThat(added.port()).isEqualTo("8070"));
        }

        /// A `"tcp+udp"` entry arrives as two calls, and bootstrap may be re-run. Neither may
        /// duplicate a rule nor issue a pointless write.
        @Test
        void openIngress_whenRuleAlreadyPresent_returnsSameHandleAndWritesNothing() {
            testClient.listFirewallsResponse = Promise.success(List.of(firewallWith(rule(8070, "tcp", "0.0.0.0/0"))));

            ingressProvider.openIngress(SOURCE, 8070, "tcp", "0.0.0.0/0", "app_http")
                           .await()
                           .onFailure(cause -> assertThat(cause).isNull())
                           .onSuccess(handle -> assertThat(handle.providerResourceId()).isEqualTo("77"));

            assertThat(testClient.createFirewallCalls).isZero();
            assertThat(testClient.setFirewallRulesCalls).isZero();
        }

        /// An unlabelled firewall cannot be reclaimed by any cleanup path. Refusing beats creating a
        /// resource that silently costs money forever.
        @Test
        void openIngress_whenClusterNameAbsent_refusesInsteadOfCreatingUnlabelledFirewall() {
            var noCluster = HetznerComputeProvider.hetznerComputeProvider(testClient, CONFIG).unwrap();

            noCluster.openIngress(SOURCE, 8070, "tcp", "0.0.0.0/0", "app_http")
                     .await()
                     .onSuccess(handle -> assertThat(handle).isNull())
                     .onFailure(cause -> assertThat(cause).isInstanceOf(EnvironmentError.OperationNotSupported.class));

            assertThat(testClient.createFirewallCalls).isZero();
        }

        @Test
        void closeIngress_whenLastRuleWithdrawn_deletesFirewall() {
            testClient.listFirewallsResponse = Promise.success(List.of(firewallWith(rule(8070, "tcp", "0.0.0.0/0"))));

            ingressProvider.closeIngress(SOURCE, 8070, "tcp", "0.0.0.0/0")
                           .await()
                           .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.deleteFirewallCalls).isEqualTo(1);
            assertThat(testClient.lastDeletedFirewallId).isEqualTo(77);
            assertThat(testClient.setFirewallRulesCalls).isZero();
        }

        @Test
        void closeIngress_whenOtherRulesRemain_keepsFirewallAndWritesRemainder() {
            testClient.listFirewallsResponse = Promise.success(List.of(firewallWith(rule(8070, "tcp", "0.0.0.0/0"),
                                                                                    rule(9000, "tcp", "10.0.0.0/8"))));

            ingressProvider.closeIngress(SOURCE, 8070, "tcp", "0.0.0.0/0")
                           .await()
                           .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.deleteFirewallCalls).isZero();
            assertThat(testClient.lastSetRules).singleElement()
                                                .satisfies(kept -> assertThat(kept.port()).isEqualTo("9000"));
        }

        @Test
        void closeIngress_whenRuleAbsent_writesNothing() {
            testClient.listFirewallsResponse = Promise.success(List.of(firewallWith(rule(9000, "tcp", "10.0.0.0/8"))));

            ingressProvider.closeIngress(SOURCE, 8070, "tcp", "0.0.0.0/0")
                           .await()
                           .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.deleteFirewallCalls).isZero();
            assertThat(testClient.setFirewallRulesCalls).isZero();
        }

        @Test
        void closeIngress_whenNoFirewallExists_succeedsWithoutWriting() {
            ingressProvider.closeIngress(SOURCE, 8070, "tcp", "0.0.0.0/0")
                           .await()
                           .onFailure(cause -> assertThat(cause).isNull());

            assertThat(testClient.deleteFirewallCalls).isZero();
            assertThat(testClient.setFirewallRulesCalls).isZero();
        }
    }
}
