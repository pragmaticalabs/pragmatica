// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.environment.aws;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.MarketOptions;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.RouteChange;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.cloud.aws.AwsConfig;
import org.pragmatica.cloud.aws.AwsError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.cloud.aws.AwsConfig.awsConfig;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// LocalStack AWS contract-regression sensor (RFC-0016 §4.2/§4.3, W5). Exercises the REAL
/// [AwsClient] and the AWS environment providers (compute / load-balancer / secrets / discovery)
/// against a LocalStack Community-tier testcontainer, retargeted via [AwsConfig#withEndpointOverride].
/// It is the AWS provisioning-surface sensor that runs BEFORE any live AWS credential exists — its
/// job is to assert REQUEST CORRECTNESS and response MAPPING, not AWS semantics LocalStack fakes.
///
/// Lazy Docker gate: the container is a plain `static` field (NOT `@Container`, class NOT
/// `@Testcontainers`). A `@BeforeEach` [org.junit.jupiter.api.Assumptions#assumeTrue] probe SKIPS
/// every test individually (visible, counted) when no Docker daemon is present, and `@BeforeAll`
/// starts the container ONLY after the same probe passes — so nothing eager-inits before the check
/// (the #466 anti-pattern of an eager `@Container` field is avoided) and each test reports SKIPPED,
/// never ERROR.
///
/// Per-surface fidelity caveats (LocalStack Community fakes some AWS behavior):
///  - Compute: the EC2 mock echoes instance-type / image / tags via DescribeInstances but does NOT
///    surface UserData there, and returns instances `running` immediately (no real boot). We assert
///    the request fields it echoes and the provider's InstanceInfo mapping — not boot semantics.
///  - LoadBalancer: ELBv2 (ALB/NLB target groups) is a LocalStack **Pro** service — the Community
///    image returns a Query-protocol error for every elbv2 call, so the register → describe →
///    deregister happy path is NOT runnable here (it is covered by the [AwsClient] unit tests and a
///    deferred live/Pro smoke, §4.3). What Community CAN assert is the #483 production-critical
///    property: the client speaks the Query protocol and every ELBv2 response path resolves
///    PROMPTLY with a typed [AwsError] — never an unresolved 90 s hang.
///  - Secrets: SecretsManager is a JSON protocol; LocalStack Community supports create/get with
///    full fidelity for the round-trip asserted here.
///  - Discovery: EC2 tag-filter DescribeInstances is faithful; port defaults to 9100 (no
///    `aether-port` tag is written by the provider).
///
/// Spot is EXCLUDED (LocalStack-Pro-only, §4.3) — it is covered by unit tests
/// (`AwsComputeProviderTest.createFrom_spotMarket_*`) plus a planned live spot smoke.
class AwsLocalStackContractTest {
    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName.parse("localstack/localstack:3.8");
    private static final String REGION = "us-east-1";
    private static final String INSTANCE_SIZE = "t3.micro";
    private static final String FALLBACK_AMI = "ami-1234567890abcdef0";
    private static final TimeSpan TEST_AWAIT = timeSpan(90).seconds();

    private static LocalStackContainer localstack;
    private static AwsClient client;
    private static String vpcId;
    private static String subnetId;
    private static String securityGroupId;
    private static String contractAmi;

    @BeforeAll
    static void startLocalStack() {
        if (!dockerAvailable()) {
            return;
        }
        localstack = new LocalStackContainer(LOCALSTACK_IMAGE).withEnv("SERVICES", "ec2,elbv2,secretsmanager");
        localstack.start();
        client = AwsClient.awsClient(localStackConfig());
        prepareNetwork();
    }

    @BeforeEach
    void requireDocker() {
        assumeTrue(dockerAvailable(),
                   "AWS LocalStack contract suite requires a Docker daemon (skipped cleanly when absent)");
    }

    @AfterAll
    static void stopLocalStack() {
        if (localstack != null) {
            localstack.stop();
        }
    }

    @Nested
    class ComputeContract {
        @Test
        @Timeout(120)
        void createFrom_onDemand_mapsInstanceInfo() {
            var info = provisionCore("compute-map");

            assertThat(info.id().value()).startsWith("i-");
            assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING);
            assertThat(info.type()).isEqualTo(InstanceType.ON_DEMAND);

            terminateQuietly(info.id().value());
        }

        @Test
        @Timeout(120)
        void createFrom_onDemand_echoesRequestFieldsToRunInstances() {
            var id = provisionCore("compute-echo").id().value();

            assertThat(describeField(id, "InstanceType")).isEqualTo(INSTANCE_SIZE);
            assertThat(describeField(id, "ImageId")).isEqualTo(contractAmi);
            assertThat(describeTagValue(id, "aether-managed")).isEqualTo("true");
            assertThat(describeTagValue(id, "aether-role")).isEqualTo("core");

            terminateQuietly(id);
        }

        @Test
        @Timeout(120)
        void instanceStatus_afterCreate_reportsRunningWithManagedTags() {
            var id = provisionCore("compute-status").id().value();
            var observed = awaitSuccess(computeProvider().instanceStatus(new InstanceId(id)));

            assertThat(observed.status()).isEqualTo(InstanceStatus.RUNNING);
            assertThat(observed.tags()).containsEntry("aether-managed", "true").containsEntry("aether-role", "core");
            assertThat(observed.nodeId().or("")).isEqualTo("node-core");

            terminateQuietly(id);
        }

        @Test
        @Timeout(120)
        void listInstances_byManagedTagFilter_includesCreatedInstance() {
            var id = provisionCore("compute-list").id().value();
            var instances = awaitSuccess(computeProvider().listInstances(Map.of("aether-managed", "true")));

            assertThat(instances).anyMatch(instance -> id.equals(instance.id().value()));

            terminateQuietly(id);
        }

        @Test
        @Timeout(120)
        void terminate_afterCreate_instanceLeavesRunningState() {
            var id = provisionCore("compute-term").id().value();

            awaitSuccess(computeProvider().terminate(new InstanceId(id)));

            assertThat(describeField(id, "State.Name")).isIn("shutting-down", "terminated", "stopped");
        }
    }

    @Nested
    class LoadBalancerContract {
        /// A syntactically valid target-group ARN — ELBv2 being Pro-only, LocalStack Community
        /// rejects the call regardless of whether the group exists, so no setup is needed.
        private static final String DUMMY_TG_ARN =
            "arn:aws:elasticloadbalancing:" + REGION + ":000000000000:targetgroup/aether-contract/0000000000000000";

        /// ELBv2 is Pro-only in Community (see the class caveat): the happy-path round-trip cannot
        /// run. This asserts the #483 property that DOES hold on Community — register / describe /
        /// deregister each resolve promptly with a typed [AwsError] (the Query-protocol XML error
        /// parsed), never leaving the promise unresolved for 90 s.
        @Test
        @Timeout(120)
        void elbv2Operations_boundedAndTyped_neverHang() {
            var lb = AwsLoadBalancerProvider.awsLoadBalancerProvider(client, DUMMY_TG_ARN).unwrap();

            assertBoundedTypedFailure(lb.onRouteChanged(new RouteChange("GET", "/", Set.of("i-contract"))));
            assertBoundedTypedFailure(lb.loadBalancerInfo());
            assertBoundedTypedFailure(lb.onNodeRemoved("i-contract"));
        }

        private void assertBoundedTypedFailure(Promise<?> promise) {
            promise.await(TEST_AWAIT)
                   .onSuccess(value -> assertThat(value).as("ELBv2 is Pro-only in Community; expected a typed AwsError, not success")
                                                        .isNull())
                   .onFailure(cause -> assertThat(cause).as("ELBv2 path must fail fast and typed, never hang: %s", cause.message())
                                                        .isInstanceOf(AwsError.class));
        }
    }

    @Nested
    class SecretsContract {
        @Test
        @Timeout(120)
        void createSecret_thenResolveSecret_returnsValue() {
            awslocalText("secretsmanager", "create-secret",
                         "--name", "aether/contract-secret", "--secret-string", "s3cr3t-value");
            var provider = AwsSecretsProvider.awsSecretsProvider(client).unwrap();

            var value = awaitSuccess(provider.resolveSecret("aether/contract-secret"));

            assertThat(value).isEqualTo("s3cr3t-value");
        }

        @Test
        @Timeout(120)
        void resolveSecret_missingSecret_fails() {
            var provider = AwsSecretsProvider.awsSecretsProvider(client).unwrap();

            provider.resolveSecret("aether/does-not-exist")
                    .await(TEST_AWAIT)
                    .onSuccess(value -> assertThat(value).as("expected a missing-secret failure").isNull())
                    .onFailure(cause -> assertThat(cause).isNotNull());
        }
    }

    @Nested
    class DiscoveryContract {
        @Test
        @Timeout(120)
        void discoverPeers_byClusterTag_listsTaggedInstance() {
            var id = provisionCore("discovery-present").id().value();
            var discovery = AwsDiscoveryProvider.awsDiscoveryProvider(client,
                                                                      computeConfig().withDiscovery(clusterName("discovery-present").unwrap()));

            var peers = awaitSuccess(discovery.discoverPeers());
            var peer = peers.stream()
                            .filter(candidate -> "discovery-present".equals(candidate.metadata().get("aether-cluster")))
                            .findFirst()
                            .orElseThrow();

            assertThat(peer.host()).isNotBlank();
            assertThat(peer.port()).isEqualTo(9100);
            assertThat(peer.metadata()).containsEntry("aether-role", "core");

            terminateQuietly(id);
        }

        @Test
        @Timeout(120)
        void discoverPeers_differentCluster_excludesInstance() {
            var id = provisionCore("discovery-scoped").id().value();
            var discovery = AwsDiscoveryProvider.awsDiscoveryProvider(client,
                                                                      computeConfig().withDiscovery(clusterName("discovery-absent").unwrap()));

            var peers = awaitSuccess(discovery.discoverPeers());

            assertThat(peers).noneMatch(peer -> "discovery-scoped".equals(peer.metadata().get("aether-cluster")));

            terminateQuietly(id);
        }
    }

    // --- Docker gate (the ONE small helper; mirrors the aether-deployment schema-test pattern) ---

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    // --- Container-derived wiring ---

    private static AwsConfig localStackConfig() {
        return awsConfig(localstack.getAccessKey(), localstack.getSecretKey(), REGION)
                   .withEndpointOverride(localstack.getEndpoint().toString());
    }

    private static void prepareNetwork() {
        vpcId = awslocalText("ec2", "create-vpc", "--cidr-block", "10.0.0.0/16",
                             "--query", "Vpc.VpcId", "--output", "text");
        subnetId = awslocalText("ec2", "create-subnet", "--vpc-id", vpcId, "--cidr-block", "10.0.1.0/24",
                                "--query", "Subnet.SubnetId", "--output", "text");
        securityGroupId = awslocalText("ec2", "create-security-group", "--group-name", "aether-contract-sg",
                                       "--description", "aether contract test SG", "--vpc-id", vpcId,
                                       "--query", "GroupId", "--output", "text");
        contractAmi = firstUsable(awslocalText("ec2", "describe-images",
                                               "--query", "Images[0].ImageId", "--output", "text"),
                                  FALLBACK_AMI);
    }

    private static AwsEnvironmentConfig computeConfig() {
        return AwsEnvironmentConfig.awsEnvironmentConfig(localStackConfig(),
                                                         contractAmi,
                                                         INSTANCE_SIZE,
                                                         Option.empty(),
                                                         List.of(securityGroupId),
                                                         subnetId,
                                                         "").unwrap();
    }

    private static AwsComputeProvider computeProvider() {
        return AwsComputeProvider.awsComputeProvider(client, computeConfig()).unwrap();
    }

    private static ProvisionRequest onDemandRequest(ClusterName cluster, String role) {
        return new ProvisionRequest(InstanceType.ON_DEMAND,
                                    INSTANCE_SIZE,
                                    contractAmi,
                                    "",
                                    Option.some("aether-contract"),
                                    MarketOptions.ON_DEMAND,
                                    ProvisionContext.forBootstrap(cluster, role, sourceNameOrDefault("contract-src"), "node-" + role));
    }

    private static InstanceInfo provisionCore(String cluster) {
        return awaitSuccess(computeProvider().createFrom(onDemandRequest(clusterName(cluster).unwrap(), "core")));
    }

    private static void terminateQuietly(String instanceId) {
        computeProvider().terminate(new InstanceId(instanceId)).await(TEST_AWAIT);
    }

    // --- awslocal CLI bridge (setup + request-echo verification via the real botocore protocol) ---

    private static String describeField(String instanceId, String field) {
        return awslocalText("ec2", "describe-instances", "--instance-ids", instanceId,
                            "--query", "Reservations[0].Instances[0]." + field, "--output", "text");
    }

    private static String describeTagValue(String instanceId, String tagKey) {
        return awslocalText("ec2", "describe-instances", "--instance-ids", instanceId,
                            "--query", "Reservations[0].Instances[0].Tags[?Key=='" + tagKey + "']|[0].Value",
                            "--output", "text");
    }

    private static String awslocalText(String... args) {
        var command = commandWith(args);

        try {
            var result = localstack.execInContainer(command);

            assertThat(result.getExitCode())
                .as("awslocal %s failed: %s", String.join(" ", args), result.getStderr())
                .isZero();

            return result.getStdout().trim();
        } catch (IOException e) {
            throw new IllegalStateException("awslocal exec failed: " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("awslocal exec interrupted: " + String.join(" ", args), e);
        }
    }

    private static String[] commandWith(String... args) {
        var command = new String[args.length + 1];

        command[0] = "awslocal";
        System.arraycopy(args, 0, command, 1, args.length);

        return command;
    }

    private static String firstUsable(String value, String fallback) {
        return value == null || value.isBlank() || "None".equals(value)
               ? fallback
               : value;
    }

    // --- Promise assertion helpers ---

    private static <T> T awaitSuccess(Promise<T> promise) {
        return promise.await(TEST_AWAIT)
                      .onFailure(AwsLocalStackContractTest::failOnCause)
                      .unwrap();
    }

    private static void failOnCause(Cause cause) {
        assertThat(cause).as("unexpected failure: %s", cause.message()).isNull();
    }
}
