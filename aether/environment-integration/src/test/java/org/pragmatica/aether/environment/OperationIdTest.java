// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.ClusterName.maybeClusterName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;


/// G1-a: the operation id is the lower-case node id, and it is the provider-side server name. Provider names are case-sensitive
/// (P-G1 A4), so the grammar and the mint are pinned here, not left to a provider test.
class OperationIdTest {
    @Nested
    class Grammar {
        @Test
        void operationId_lowerCaseHostnameLabel_parses() {
            assertThat(OperationId.operationId("aether-prod-node-01jabcdefghjkmnpqrstvwxyz0").isSuccess()).isTrue();
            assertThat(OperationId.operationId("n0").isSuccess()).isTrue();
            assertThat(OperationId.operationId("0").isSuccess()).isTrue();
        }

        @Test
        void operationId_upperCase_isRefused() {
            assertThat(OperationId.operationId("Node-1").isFailure()).isTrue();
        }

        @Test
        void operationId_outsideTheLabelAlphabet_isRefused() {
            assertThat(OperationId.operationId("node_1").isFailure()).isTrue();
            assertThat(OperationId.operationId("node.1").isFailure()).isTrue();
            assertThat(OperationId.operationId("-node").isFailure()).isTrue();
            assertThat(OperationId.operationId("node-").isFailure()).isTrue();
            assertThat(OperationId.operationId("").isFailure()).isTrue();
            assertThat(OperationId.operationId(null).isFailure()).isTrue();
        }

        @Test
        void operationId_atMost63Characters() {
            assertThat(OperationId.operationId("a".repeat(63)).isSuccess()).isTrue();
            assertThat(OperationId.operationId("a".repeat(64)).isFailure()).isTrue();
        }
    }

    @Nested
    class Mint {
        @Test
        void mintNodeId_isLowerCaseAndAnOperationId_forEveryMint() {
            var minted = new ArrayList<String>();

            for (int i = 0; i < 200; i++) {
                minted.add(ProvisionContext.mintNodeId(maybeClusterName("prod")));
            }

            assertThat(minted).allSatisfy(id -> assertThat(id).isEqualTo(id.toLowerCase()));
            assertThat(minted).allSatisfy(id -> assertThat(OperationId.operationId(id).isSuccess()).as(id).isTrue());
            assertThat(minted).doesNotHaveDuplicates();
        }

        @Test
        void mintNodeId_withoutACluster_isAnOperationId() {
            var id = ProvisionContext.mintNodeId(Option.empty());

            assertThat(id).startsWith("aether-node-");
            assertThat(OperationId.operationId(id).isSuccess()).isTrue();
        }

        /// Cloud node ids carry no cluster (`aether-node-<26-character ulid>`, 38 characters), the server name `<cluster>-<nodeId>` does, so the
        /// longest cluster name is 24 (24 + 1 + 38 = 63). A longer one is refused, never truncated.
        @Test
        void mintCloudNodeId_isClusterFree_andFitsTheScopedServerNameGrammar_forClusterNamesUpToTheLongest() {
            var id = ProvisionContext.mintCloudNodeId();
            var longest = OperationId.longestClusterNameForMintedIds();
            var fits = clusterName("a".repeat(longest)).unwrap();
            var tooLong = clusterName("a".repeat(longest + 1)).unwrap();

            assertThat(id).matches("aether-node-[0-9a-hjkmnp-tv-z]{26}").hasSize(38);
            assertThat(longest).isEqualTo(24);
            assertThat(OperationId.forNode(fits, id).unwrap().value()).hasSize(63);
            assertThat(OperationId.forNode(tooLong, id).isFailure()).isTrue();
        }

        @Test
        void resolveNodeId_withoutACallersId_isACloudId() {
            var context = ProvisionContext.provisionContext(maybeClusterName("prod"), "core", sourceNameOrDefault("eu-1"), ProvisionContext.PROVISIONED_BY_CTM);

            assertThat(context.resolveNodeId()).matches("aether-node-[0-9a-hjkmnp-tv-z]{26}");
        }

        @Test
        void dockerIds_keepTheCluster() {
            assertThat(ProvisionContext.mintNodeId(maybeClusterName("prod"))).startsWith("aether-prod-node-");
            assertThat(ProvisionContext.coreNodeNamePrefix(maybeClusterName("prod"))).isEqualTo("aether-prod-node");
        }

        @Test
        void aProductionEuClusterFitsWithRoomToSpare() {
            var cluster = clusterName("production-eu").unwrap();

            assertThat(OperationId.forNode(cluster, ProvisionContext.mintCloudNodeId()).unwrap().value()).hasSize(13 + 1 + 38);
        }

        @Test
        void operationIdLength_isTheClusterPlusOnePlusTheLongestIdThePoolCanBeGiven() {
            assertThat(OperationId.longestMintedIdLength("s", "core")).as("minted cloud id, no cluster in it").isEqualTo(38);
            assertThat(OperationId.operationIdLength(3, "s".repeat(39), "core")).as("worker id 59: 3 + 1 + 59").isEqualTo(63);
            assertThat(OperationId.operationIdLength(3, "s".repeat(40), "core")).isEqualTo(64);
            assertThat(OperationId.longestClusterNameFor("s".repeat(39), "core")).isEqualTo(3);
            assertThat(OperationId.longestClusterNameFor("hetzner-eu", "worker")).as("the minted cloud id dominates a short pool").isEqualTo(24);
            assertThat(OperationId.operationIdLength(24, "hetzner-eu", "worker")).as("exactly 63").isEqualTo(63);
            assertThat(OperationId.operationIdLength(25, "hetzner-eu", "worker")).isEqualTo(64);
            assertThat(OperationId.operationIdLength(13, "hetzner-eu", "worker")).as("production-eu").isEqualTo(52);
        }

        @Test
        void forNode_scopesTheNodeIdByTheCluster() {
            assertThat(OperationId.forNode(clusterName("prod").unwrap(), "eu-1-core-0").unwrap().value()).isEqualTo("prod-eu-1-core-0");
            assertThat(OperationId.forNode(clusterName("staging").unwrap(), "eu-1-core-0").unwrap().value()).isEqualTo("staging-eu-1-core-0");
        }
    }

    @Nested
    class OnTheRequest {
        @Test
        void named_takesTheOperationIdFromTheContextNodeId() {
            var request = request("eu-1-core-0").named();

            assertThat(request.operationId().map(OperationId::value)).isEqualTo(Option.some("prod-eu-1-core-0"));
        }

        @Test
        void named_nodeIdOutsideTheGrammar_leavesItEmpty() {
            assertThat(request("Node-1").named().operationId()).isEqualTo(Option.empty());
        }

        @Test
        void resolve_neverNamesTheRequest_onlyTheProviderBoundaryDoes() {
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context("eu-1-core-0")).unwrap();
            var defaults = ProviderDefaults.providerDefaults("cx22", "img", "img", "fsn1", Option.empty(), true);

            assertThat(ProvisionRequest.resolve(spec, defaults).unwrap().operationId()).isEqualTo(Option.empty());
        }

        @Test
        void refuseOperationId_isTypedWhenTheRequestNamesAnOperation_andEmptyWhenItDoesNot() {
            assertThat(request("eu-1-core-0").named().refuseOperationId("aws").unwrap())
                    .isInstanceOf(EnvironmentError.OperationIdUnsupported.class);
            assertThat(request("eu-1-core-0").refuseOperationId("aws")).isEqualTo(Option.empty());
        }

        private ProvisionRequest request(String nodeId) {
            return new ProvisionRequest(InstanceType.ON_DEMAND, "cx22", "img", "fsn1", Option.empty(), MarketOptions.ON_DEMAND, context(nodeId));
        }

        private ProvisionContext context(String nodeId) {
            return ProvisionContext.forBootstrap(clusterName("prod").unwrap(), "core", sourceNameOrDefault("eu-1"), nodeId);
        }
    }

    /// The boundary hands an operation id only to a provider that declares it honours one.
    @Nested
    class AtTheProviderBoundary {
        @Test
        void provision_providerThatHonoursOne_isHandedTheNodeIdAsTheOperationId() {
            var provider = new CapturingProvider(true);

            provider.provision(spec("eu-1-core-0")).await();

            assertThat(provider.requests).hasSize(1);
            assertThat(provider.requests.getFirst().operationId().map(OperationId::value)).isEqualTo(Option.some("prod-eu-1-core-0"));
        }

        @Test
        void provision_providerThatDoesNot_isHandedNone() {
            var provider = new CapturingProvider(false);

            provider.provision(spec("eu-1-core-0")).await();

            assertThat(provider.requests).hasSize(1);
            assertThat(provider.requests.getFirst().operationId()).isEqualTo(Option.empty());
        }

        private ProvisionSpec spec(String nodeId) {
            var context = ProvisionContext.forBootstrap(clusterName("prod").unwrap(), "core", sourceNameOrDefault("eu-1"), nodeId);

            return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();
        }
    }

    private static final class CapturingProvider implements ComputeProvider {
        final List<ProvisionRequest> requests = new ArrayList<>();
        private final boolean honours;

        CapturingProvider(boolean honours) {
            this.honours = honours;
        }

        @Override
        public boolean honoursOperationId() {
            return honours;
        }

        @Override
        public ProviderDefaults providerDefaults() {
            return ProviderDefaults.providerDefaults("cx22", "img", "img", "fsn1", Option.empty(), true);
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            requests.add(request);

            return EnvironmentError.operationNotSupported("captured").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return EnvironmentError.operationNotSupported("captured").promise();
        }
    }
}
