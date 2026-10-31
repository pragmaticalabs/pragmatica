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

        /// `aether-<cluster>-node-<26-character ulid>` is 39 characters plus the cluster name: a cluster name of 24 characters is
        /// the longest whose minted id still names a server. A longer one is refused at the provider, never truncated.
        @Test
        void mintNodeId_fitsTheServerNameGrammar_forClusterNamesUpTo24Characters() {
            var fits = ProvisionContext.mintNodeId(maybeClusterName("a".repeat(24)));
            var tooLong = ProvisionContext.mintNodeId(maybeClusterName("a".repeat(25)));

            assertThat(OperationId.operationId(fits).isSuccess()).isTrue();
            assertThat(OperationId.operationId(tooLong).isFailure()).isTrue();
        }
    }

    @Nested
    class OnTheRequest {
        @Test
        void named_takesTheOperationIdFromTheContextNodeId() {
            var request = request("eu-1-core-0").named();

            assertThat(request.operationId().map(OperationId::value)).isEqualTo(Option.some("eu-1-core-0"));
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
            assertThat(provider.requests.getFirst().operationId().map(OperationId::value)).isEqualTo(Option.some("eu-1-core-0"));
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
