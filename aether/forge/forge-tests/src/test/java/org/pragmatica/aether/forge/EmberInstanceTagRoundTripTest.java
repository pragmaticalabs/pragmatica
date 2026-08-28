// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.MarketOptions;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// The #694 guard: an instance provisioned through `EmberComputeProvider.createFrom` must
/// round-trip the CTM's worker-reconcile selector (`aether-cluster`/`aether-source`/`aether-role`),
/// because that selector is how ACTUAL inventory is counted — before the fix every in-JVM instance
/// carried an empty tag map, matched no non-empty selector, and worker reconcile read `actual = 0`
/// forever. The negative guards are the load-bearing half, in both directions: a selector differing
/// in any ONE field must NOT find the instance (a match-everything stamp would hide scale-down
/// victims in other sources' counts), and an UNTAGGED instance must keep not-matching non-empty
/// selectors (the pre-#694 shape for nodes created outside the provider, preserved deliberately).
///
/// Runs against a real 3-node Ember cluster because the provider provisions REAL in-JVM nodes —
/// `createFrom` boots one — and the selector behavior under test composes the stamped map with the
/// `ComputeProvider.listInstances(tagFilter)` default the CTM actually calls.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmberInstanceTagRoundTripTest {
    private static final int BASE_PORT = 22350;
    private static final int BASE_MGMT_PORT = 22450;
    private static final int BASE_APP_HTTP_PORT = 22550;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private static final String CLUSTER = "tag-cluster";
    private static final String SOURCE = "src-1";
    private static final String ROLE = "worker";

    private EmberCluster cluster;
    private ComputeProvider provider;
    private String workerInstanceId;
    private String defaultRoleInstanceId;

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "tag");
        // Identity decorator that leaks the BASE provider reference — the sanctioned seam for
        // reaching the provider the CTM would drive (the #509 probe's recorder pattern, minus the
        // recording).
        var captured = new AtomicReference<ComputeProvider>();

        cluster.withComputeProviderDecorator(base -> {
            captured.set(base);

            return base;
        });
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());
        provider = captured.get();
        assertThat(provider).as("decorator must have captured the base provider during start").isNotNull();
        workerInstanceId = provision(ROLE);
        defaultRoleInstanceId = provision("");
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    /// The positive half of the round-trip: the selector the CTM builds from the SAME context finds
    /// the instance, and the stamped map is exactly the context's identity plus the provider-agnostic
    /// node-id tag — nothing extra to over-match on, nothing missing to under-match on.
    @Test
    void provisionedInstance_isFoundByTheSelectorBuiltFromItsOwnContext() {
        var found = listBy(ctmSelector(CLUSTER, SOURCE, ROLE));

        assertThat(found).extracting(info -> info.id().value())
                         .contains(workerInstanceId);
        assertThat(tagsOf(workerInstanceId)).isEqualTo(Map.of("aether-cluster", CLUSTER,
                                                              "aether-role", ROLE,
                                                              "aether-source", SOURCE,
                                                              "aether.node-id", workerInstanceId));
    }

    /// The one-field-off guard, all three fields: a selector wrong in ANY single field must not find
    /// the instance. This is what makes the positive test above non-vacuous — a stamp that matched
    /// everything would pass it while corrupting every other source's count.
    @Test
    void provisionedInstance_isNotFoundBySelectorsDifferingInAnyOneField() {
        assertThat(listBy(ctmSelector("other-cluster", SOURCE, ROLE)))
            .extracting(info -> info.id().value())
            .doesNotContain(workerInstanceId);
        assertThat(listBy(ctmSelector(CLUSTER, "other-source", ROLE)))
            .extracting(info -> info.id().value())
            .doesNotContain(workerInstanceId);
        assertThat(listBy(ctmSelector(CLUSTER, SOURCE, "core")))
            .extracting(info -> info.id().value())
            .doesNotContain(workerInstanceId);
    }

    /// A blank context role stamps `aether-role=core`, mirroring `HetznerComputeProvider.labelsFor` —
    /// the default must be the production default, or an in-JVM core-counting selector diverges from
    /// the cloud one.
    @Test
    void blankRole_stampsTheProductionCoreDefault() {
        assertThat(tagsOf(defaultRoleInstanceId)).containsEntry("aether-role", "core");
        assertThat(listBy(ctmSelector(CLUSTER, SOURCE, "core")))
            .extracting(info -> info.id().value())
            .contains(defaultRoleInstanceId);
    }

    /// The untagged-instance guard: nodes created OUTSIDE the provider (the initial cluster) keep
    /// the pre-#694 shape — an empty tag map that matches no non-empty selector — while still being
    /// visible to the unfiltered listing. Armed by the worker instance appearing under the same
    /// selector in the positive test: emptiness here is the absence of tags, not a broken listing.
    @Test
    void initialClusterNode_staysUntagged_andMatchesNoNonEmptySelector() {
        var initialId = "tag-1";
        var unfiltered = provider.listInstances()
                                 .await()
                                 .fold(cause -> {
                                           throw new AssertionError("listInstances failed: " + cause.message());
                                       },
                                       instances -> instances);

        assertThat(unfiltered).extracting(info -> info.id().value())
                              .contains(initialId);
        assertThat(tagsOf(initialId)).isEmpty();
        assertThat(listBy(ctmSelector(CLUSTER, SOURCE, ROLE)))
            .extracting(info -> info.id().value())
            .doesNotContain(initialId);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String provision(String role) {
        var context = new ProvisionContext(Option.some(new ClusterName(CLUSTER)),
                                           role,
                                           new SourceName(SOURCE),
                                           Option.none(),
                                           Option.none(),
                                           3,
                                           ProvisionContext.PROVISIONED_BY_CTM,
                                           Map.of());
        var request = new ProvisionRequest(InstanceType.ON_DEMAND,
                                           "default",
                                           "",
                                           "",
                                           Option.none(),
                                           MarketOptions.ON_DEMAND,
                                           context);

        return provider.createFrom(request)
                       .await()
                       .fold(cause -> {
                                 throw new AssertionError("provision failed: " + cause.message());
                             },
                             info -> info.id().value());
    }

    private static Map<String, String> ctmSelector(String clusterName, String source, String role) {
        return Map.of("aether-cluster", clusterName, "aether-source", source, "aether-role", role);
    }

    private List<InstanceInfo> listBy(Map<String, String> selector) {
        return provider.listInstances(selector)
                       .await()
                       .fold(cause -> {
                                 throw new AssertionError("listInstances(filter) failed: " + cause.message());
                             },
                             instances -> instances);
    }

    private Map<String, String> tagsOf(String instanceId) {
        return provider.listInstances()
                       .await()
                       .fold(cause -> {
                                 throw new AssertionError("listInstances failed: " + cause.message());
                             },
                             instances -> instances.stream()
                                                   .filter(info -> info.id().value().equals(instanceId))
                                                   .findFirst()
                                                   .map(InstanceInfo::tags)
                                                   .orElseThrow(() -> new AssertionError("instance not listed: " + instanceId)));
    }
}
