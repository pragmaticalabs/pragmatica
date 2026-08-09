// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// Scale target resolution and quorum arithmetic for `POST /api/cluster/scale` (RFC-0017 C1).
///
/// The route used to take a bare cluster-wide `coreCount`, which could not say which source
/// absorbed the change and answered the ambiguity by overwriting one number.
class ClusterConfigRoutesScaleTest {
    private static final int CORE_MIN = 3;
    private static final int CORE_MAX = 9;

    private static ClusterConfigValue topology(TopologyEntry... entries) {
        return ClusterConfigValue.clusterConfigValue("toml",
                                                     "prod",
                                                     "1.0.0",
                                                     List.of(entries),
                                                     CORE_MIN,
                                                     CORE_MAX,
                                                     "hetzner",
                                                     1);
    }

    /// Absent and blank both mean "the operator did not say". Jackson produces `null` for an omitted
    /// field and `""` for a field the CLI sent empty, so both must collapse to the same signal.
    @Test
    void nonBlank_treatsNullEmptyAndWhitespace_asAbsent() {
        assertThat(ClusterConfigRoutes.nonBlank(null).isEmpty()).isTrue();
        assertThat(ClusterConfigRoutes.nonBlank("").isEmpty()).isTrue();
        assertThat(ClusterConfigRoutes.nonBlank("   ").isEmpty()).isTrue();
        assertThat(ClusterConfigRoutes.nonBlank("eu").isPresent()).isTrue();
    }

    @Test
    void effectiveRole_defaultsToCore_whenBlankOrAbsent() {
        assertThat(ClusterConfigRoutes.effectiveRole(ClusterConfigRoutes.nonBlank(null))).isEqualTo("core");
        assertThat(ClusterConfigRoutes.effectiveRole(ClusterConfigRoutes.nonBlank(""))).isEqualTo("core");
        assertThat(ClusterConfigRoutes.effectiveRole(ClusterConfigRoutes.nonBlank("  "))).isEqualTo("core");
        assertThat(ClusterConfigRoutes.effectiveRole(ClusterConfigRoutes.nonBlank("worker"))).isEqualTo("worker");
    }

    @Test
    void resolveScaleTarget_infersSource_whenExactlyOneDeclaresTheRole() {
        var stored = topology(new TopologyEntry("eu", "core", 3), new TopologyEntry("eu", "worker", 7));

        assertThat(ClusterConfigRoutes.resolveScaleTarget(stored, Option.empty(), "core").unwrap()).isEqualTo("eu");
    }

    /// The refusal that the typed model exists to produce. Two core-bearing sources make
    /// "scale cores to 5" genuinely ambiguous, and the operator gets told which sources to choose from.
    @Test
    void resolveScaleTarget_refusesAndNamesCandidates_whenSeveralSourcesDeclareTheRole() {
        var stored = topology(new TopologyEntry("eu", "core", 3), new TopologyEntry("us", "core", 2));

        var resolved = ClusterConfigRoutes.resolveScaleTarget(stored, Option.empty(), "core");

        assertThat(resolved.isFailure()).isTrue();
        resolved.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.AmbiguousScaleSource.class);
            assertThat(cause.message()).contains("eu", "us", "--source");
        });
    }

    /// Without this guard a mistyped `--source` would append a new topology entry, turning a typo
    /// into a real provisioning target.
    @Test
    void resolveScaleTarget_refusesUndeclaredSource_ratherThanCreatingIt() {
        var stored = topology(new TopologyEntry("eu-central", "core", 3));

        var resolved = ClusterConfigRoutes.resolveScaleTarget(stored, Option.some("eu-centrl"), "core");

        assertThat(resolved.isFailure()).isTrue();
        resolved.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.UnknownScaleTarget.class);
            assertThat(cause.message()).contains("eu-centrl", "eu-central/core");
        });
    }

    @Test
    void resolveScaleTarget_refusesRoleNoSourceDeclares() {
        var stored = topology(new TopologyEntry("eu", "core", 3));

        assertThat(ClusterConfigRoutes.resolveScaleTarget(stored, Option.empty(), "spot").isFailure()).isTrue();
    }

    /// Per-source counts are not cluster totals. Scaling `eu` cores to 1 leaves 1 + 2 = 3 cluster-wide,
    /// which is a legal quorum — validating the per-source number alone would wrongly reject it.
    @Test
    void validateScale_checksClusterWideCoreTotal_notThePerSourceCount() {
        var stored = topology(new TopologyEntry("eu", "core", 3), new TopologyEntry("us", "core", 2));

        assertThat(ClusterConfigRoutes.validateScale(stored, "eu", "core", 1).isSuccess()).isTrue();
    }

    @Test
    void validateScale_rejectsScaleDroppingClusterBelowQuorum() {
        var stored = topology(new TopologyEntry("eu", "core", 5));

        var result = ClusterConfigRoutes.validateScale(stored, "eu", "core", 1);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.QuorumSafetyViolation.class));
    }

    @Test
    void validateScale_rejectsEvenClusterWideCoreTotal() {
        var stored = topology(new TopologyEntry("eu", "core", 3));

        var result = ClusterConfigRoutes.validateScale(stored, "eu", "core", 4);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.InvalidCoreCount.class));
    }

    @Test
    void validateScale_rejectsCoreTotalAboveCoreMax() {
        var stored = topology(new TopologyEntry("eu", "core", 3));

        var result = ClusterConfigRoutes.validateScale(stored, "eu", "core", 11);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ClusterConfigError.InvalidCoreMax.class));
    }

    /// Quorum arithmetic is a core-node property. Applying it to workers would refuse an even count
    /// and refuse anything above `coreMax`, neither of which means anything for workers.
    @Test
    void validateScale_leavesNonCoreRolesUnconstrainedByQuorumArithmetic() {
        var stored = topology(new TopologyEntry("eu", "core", 3), new TopologyEntry("eu", "worker", 7));

        assertThat(ClusterConfigRoutes.validateScale(stored, "eu", "worker", 4).isSuccess()).isTrue();
        assertThat(ClusterConfigRoutes.validateScale(stored, "eu", "worker", 40).isSuccess()).isTrue();
        assertThat(ClusterConfigRoutes.validateScale(stored, "eu", "worker", 0).isSuccess()).as("scale-to-zero is a legitimate worker operation — drain-all and the RFC-0017 teardown path use it").isTrue();
        assertThat(ClusterConfigRoutes.validateScale(stored, "eu", "worker", -1).isFailure()).isTrue();
    }
}
