// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// The apply validates the label values it builds provider selectors from BEFORE any provider list or terminate call: an
/// empty or unsafe value is refused typed and never reaches a provider.
class ApplyLabelsTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("labels-test").unwrap();
    private static final List<String> UNSAFE = List.of("", " ", "x,aether-cluster!=zzz", "x,!aether-apply", "x in (a,b)", "a=b", "has space", "a".repeat(64), "semi;colon", "slash/slash");

    private final ApplyChaosHarness harness = new ApplyChaosHarness();
    private final HookedProvider provider = new HookedProvider(harness);
    private final AtomicInteger providerLookups = new AtomicInteger();
    private final Function<String, Result<ComputeProvider>> computes = _ -> {
        providerLookups.incrementAndGet();

        return Result.success(provider);
    };

    private static ApplyRecorder recorderWith(ClusterName cluster, String applyId, ApplyResource... rows) {
        var base = ApplyStateFixtures.state(cluster);
        var state = ApplyState.applyState(applyId, cluster, base.configHash(), base.storedVersion(), base.startedAt(), ApplyPhase.RUNNING, List.of(), List.of(rows), List.of(), List.of(), List.of());

        return ApplyRecorder.applyRecorder(state);
    }

    private void assertProviderUntouched() {
        assertThat(providerLookups.get()).as("no provider was even resolved").isZero();
        assertThat(provider.filteredListings).isZero();
        assertThat(provider.unfilteredListings).isZero();
        assertThat(provider.terminated).isEmpty();
    }

    /// `aether-<cluster>-node-<ulid>` is 39 characters plus the cluster name: 24 fit the 63-character label limit, 25 do not.
    private static ClusterBootstrapConfig config(String cluster, String sourceType) {
        return ClusterBootstrapConfigParser.parse("""
                                                  config_version = "1.0.0"

                                                  [cluster]
                                                  name = "%s"
                                                  version = "1.0.0"

                                                  [source.s1]
                                                  type = "%s"
                                                  %s

                                                  [source.s1.core]
                                                  count = 3
                                                  """.formatted(cluster, sourceType, sourceType.equals("cloud") ? "provider = \"aws\"\nregion = \"eu-west-1\"\ncredentials = \"aws-key\"" : ""))
                                           .unwrap();
    }

    @Test
    void requireMintable_acceptsTheLongestClusterNameThatFits_andRefusesOneCharacterMore() {
        var fits = config("a".repeat(24), "docker");
        var tooLong = config("a".repeat(25), "docker");

        assertThat(ApplyLabels.requireMintable(fits, ApplyStateFixtures.APPLY_ID).isSuccess()).isTrue();
        ApplyLabels.requireMintable(tooLong, ApplyStateFixtures.APPLY_ID)
                   .onSuccess(_ -> fail("25 characters mint 64-character node ids"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(ApplyLabels.NodeIdTooLong.class)
                                                         .extracting(Object::toString)
                                                         .isNotNull());
        ApplyLabels.requireMintable(tooLong, ApplyStateFixtures.APPLY_ID)
                   .onFailure(cause -> assertThat(cause.message()).contains("63-character").contains("at most 24 characters").contains("Nothing was created"));
    }

    /// v-g1 round 4: a cloud apply mints `aether-node-<ulid>` (38 characters, no cluster), so a long cluster name must not be refused for a
    /// Docker-shaped id the apply will not mint. Hetzner's own limit on the server name is PF-36's.
    @Test
    void requireMintable_cloudOnlyApply_isNotRefusedForTheDockerShape() {
        var cloud = config("a".repeat(30), "cloud");

        assertThat(ApplyLabels.requireMintable(cloud, ApplyStateFixtures.APPLY_ID).isSuccess()).isTrue();
        assertThat(ApplyLabels.requireMintable(config("a".repeat(30), "docker"), ApplyStateFixtures.APPLY_ID).isFailure()).as("the same name IS refused when Docker mints").isTrue();
    }

    @Test
    void safeValues_pass() {
        assertThat(ApplyLabels.requireSafe(CLUSTER, ApplyStateFixtures.APPLY_ID).isSuccess()).isTrue();
        assertThat(ApplyLabels.requireSafe("node id", "aether-prod-node-01m62sqvhk33xhv64r6fad2kbx").isSuccess()).isTrue();
        assertThat(ApplyLabels.requireSafe("apply id", "a".repeat(63)).isSuccess()).isTrue();
    }

    @Test
    void everyUnsafeValue_isRefusedTyped() {
        for (var value : UNSAFE) {
            ApplyLabels.requireSafe("apply id", value).onSuccess(_ -> fail("must refuse '" + value + "'"))
                       .onFailure(cause -> assertThat(cause).isInstanceOf(ApplyLabels.UnsafeLabelValue.class));
            ApplyLabels.requireSafe(CLUSTER, value).onSuccess(_ -> fail("must refuse apply id '" + value + "'"));
            ApplyLabels.requireSafe(new ClusterName(value), ApplyStateFixtures.APPLY_ID).onSuccess(_ -> fail("must refuse cluster '" + value + "'"));
        }
    }

    @Test
    void audit_withAnUnsafeApplyIdOrCluster_neverReachesAProvider() {
        for (var value : UNSAFE) {
            ApplyReconciler.audit(recorderWith(CLUSTER, value), CLUSTER, computes).onSuccess(_ -> fail("audit must refuse apply id '" + value + "'"))
                           .onFailure(cause -> assertThat(cause.message()).contains("Nothing was listed, created or terminated"));
            ApplyReconciler.audit(recorderWith(new ClusterName(value), ApplyStateFixtures.APPLY_ID), new ClusterName(value), computes)
                           .onSuccess(_ -> fail("audit must refuse cluster '" + value + "'"));
        }

        assertProviderUntouched();
    }

    @Test
    void rollback_withAnUnsafeApplyIdOrCluster_neverListsOrTerminates() {
        var row = ApplyResource.applyResource(ApplyScenario.ACTION, "n-1", "hetzner", Option.some("srv-1"), ApplyResource.State.CREATED);

        for (var value : UNSAFE) {
            ApplyRollback.rollback(recorderWith(CLUSTER, value, row), CLUSTER, computes, _ -> Result.unitResult(), _ -> {})
                         .onSuccess(_ -> fail("rollback must refuse apply id '" + value + "'"));
            ApplyRollback.rollback(recorderWith(new ClusterName(value), ApplyStateFixtures.APPLY_ID, row), new ClusterName(value), computes, _ -> Result.unitResult(), _ -> {})
                         .onSuccess(_ -> fail("rollback must refuse cluster '" + value + "'"));
        }

        assertProviderUntouched();
    }

    @Test
    void resumeLookup_withAnUnsafeApplyIdOrNodeId_neverReachesAProvider() {
        for (var value : UNSAFE) {
            var intended = ApplyResource.applyResource(ApplyScenario.ACTION, "n-1", "hetzner", Option.none(), ApplyResource.State.INTENDED);
            var unsafeNode = ApplyResource.applyResource(ApplyScenario.ACTION, value, "hetzner", Option.none(), ApplyResource.State.INTENDED);

            ApplyReconciler.reconcileIntents(recorderWith(CLUSTER, value, intended), computes).onSuccess(_ -> fail("lookup must refuse apply id '" + value + "'"));
            ApplyReconciler.reconcileIntents(recorderWith(CLUSTER, ApplyStateFixtures.APPLY_ID, unsafeNode), computes).onSuccess(_ -> fail("lookup must refuse node id '" + value + "'"));
        }

        assertProviderUntouched();
    }
}
