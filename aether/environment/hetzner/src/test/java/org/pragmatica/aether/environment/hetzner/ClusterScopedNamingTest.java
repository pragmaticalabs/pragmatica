// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.hetzner;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerError;
import org.pragmatica.cloud.hetzner.api.Server;
import org.pragmatica.cloud.hetzner.api.Server.CreateServerRequest;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.hetzner.HetznerConfig.hetznerConfig;

/// G1-a, cluster-scoped operation id (CTO refinement of DG-1): a Hetzner project is shared by clusters, so the server name is
/// `<cluster>-<nodeId>`. Driven through the real [HetznerComputeProvider] over a project fake that refuses a duplicate name with the
/// `409 uniqueness_error` P-G1 measured (A2/A3). No cloud is called.
class ClusterScopedNamingTest {
    private final Project project = new Project();
    private final HetznerComputeProvider provider = HetznerComputeProvider.hetznerComputeProvider(project.client(), config()).unwrap();

    private ProvisionSpec spec(String cluster, String nodeId) {
        var context = ProvisionContext.forBootstrap(ClusterName.clusterName(cluster).unwrap(), "core", SourceName.sourceName("eu-1").unwrap(), nodeId);

        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "cx22", "core", context).unwrap();
    }

    /// Bootstrap ids are `<source>-<role>-<i>`: the same in every cluster of one project.
    @Test
    void twoClustersOneProject_sameSourceRoleAndIndex_bothCreate_eachNamedByItsOwnCluster() {
        var prod = provider.provision(spec("prod", "eu-1-core-0")).await();
        var staging = provider.provision(spec("staging", "eu-1-core-0")).await();

        assertThat(prod.isSuccess()).as("%s", prod).isTrue();
        assertThat(staging.isSuccess()).as("%s", staging).isTrue();
        assertThat(project.servers()).extracting(Server::name).containsExactly("prod-eu-1-core-0", "staging-eu-1-core-0");
        assertThat(project.servers().getFirst().labels()).containsEntry("aether.op", "prod-eu-1-core-0").containsEntry("aether-node-id", "eu-1-core-0");
    }

    @Test
    void aRerun_adoptsOnlyItsOwnClustersServer() {
        var prod = provider.provision(spec("prod", "eu-1-core-0")).await().unwrap();
        var staging = provider.provision(spec("staging", "eu-1-core-0")).await().unwrap();

        provider.provision(spec("prod", "eu-1-core-0")).await().onSuccess(info -> assertThat(info).isNull())
                .onFailure(cause -> assertThat(existingId(cause)).isEqualTo(prod.id().value()));
        provider.provision(spec("staging", "eu-1-core-0")).await().onSuccess(info -> assertThat(info).isNull())
                .onFailure(cause -> assertThat(existingId(cause)).isEqualTo(staging.id().value()));
        assertThat(project.servers()).hasSize(2);
    }

    @Test
    void mintedCoreIds_areScopedToo_andFitUpToTheLongestClusterName() {
        var longest = "a".repeat(org.pragmatica.aether.environment.OperationId.longestClusterNameForMintedIds());
        var id = ProvisionContext.mintNodeId(ClusterName.maybeClusterName(longest));

        assertThat(provider.provision(spec(longest, id)).await().isSuccess()).isTrue();
        assertThat(project.servers().getFirst().name()).isEqualTo(longest + "-" + id).hasSizeLessThanOrEqualTo(63);
    }

    @Test
    void aClusterNameTooLongForTheScopedId_isRefusedTypedWithZeroCreates_neverTruncated() {
        var tooLong = "a".repeat(org.pragmatica.aether.environment.OperationId.longestClusterNameForMintedIds() + 1);
        var id = ProvisionContext.mintNodeId(ClusterName.maybeClusterName(tooLong));

        assertThat(provider.provision(spec(tooLong, id)).await().isFailure()).isTrue();
        assertThat(project.createRequests).isEmpty();
    }

    /// The boundary: whatever id reaches `provision`, nothing outside the grammar reaches the provider as a name.
    @Test
    void provisionBoundary_refusesEveryIdOutsideTheGrammar_withZeroCreateCalls() {
        var bad = List.of("Aether-Prod-Node-1", "node-01ARZ3NDEKTSV4RRFFQ69G5FAV", "aether-prod-node-01k0aaaaaaaaaaaaaaaaaaaaaA", "trail-", "a_b", "",
                          "x".repeat(64), "aether-prod-node-01k0aaaaaaaaaaaaaaaaaaaaaa.", "ünïcode");

        for (var id : bad) {
            assertThat(provider.provision(spec("prod", id)).await().isFailure()).as("id [%s]", id).isTrue();
        }

        assertThat(project.createRequests).as("not one create reached the provider").isEmpty();
    }

    private static String existingId(org.pragmatica.lang.Cause cause) {
        return cause instanceof EnvironmentError.AlreadyExists existing
               ? existing.existing().id().value()
               : "not AlreadyExists: " + cause;
    }

    private static HetznerEnvironmentConfig config() {
        return HetznerEnvironmentConfig.hetznerEnvironmentConfig(hetznerConfig("test-token"), "cx22", "ubuntu-24.04", "fsn1", List.of(1L, 2L),
                                                                 List.of(10L), List.of(5L), "#!/bin/bash\necho hello").unwrap();
    }

    private static final class Project implements InvocationHandler {
        final List<CreateServerRequest> createRequests = new ArrayList<>();
        private final Map<String, Server> byName = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong(100);

        HetznerClient client() {
            return (HetznerClient) Proxy.newProxyInstance(HetznerClient.class.getClassLoader(), new Class<?>[]{HetznerClient.class}, this);
        }

        synchronized List<Server> servers() {
            return List.copyOf(byName.values());
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "createServer" -> create((CreateServerRequest) args[0]);
                case "listServers" -> Promise.success(servers());
                case "getServer" -> Promise.success(servers().stream().filter(s -> s.id() == (Long) args[0]).findFirst().orElseThrow());
                case "listSshKeys", "listFirewalls" -> Promise.success(List.of());
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        private synchronized Promise<Server> create(CreateServerRequest request) {
            createRequests.add(request);

            if (byName.containsKey(request.name())) {
                return new HetznerError.ApiError(409, "uniqueness_error", "server name is already used").promise();
            }

            var created = new Server(ids.incrementAndGet(), request.name(), "running", null, null, null, List.of(), Map.copyOf(request.labels()), new Server.Location("fsn1"));

            byName.put(request.name(), created);

            return Promise.success(created);
        }
    }
}
