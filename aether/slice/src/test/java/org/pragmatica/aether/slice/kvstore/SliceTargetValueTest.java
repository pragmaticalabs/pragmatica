// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class SliceTargetValueTest {
    @Test
    void sliceTargetValue_creates_standalone_target() {
        var version = Version.version("1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version, 3);

        assertThat(value.currentVersion()).isEqualTo(version);
        assertThat(value.targetInstances()).isEqualTo(3);
        // #1497: this asserted 3, i.e. the floor equal to the count — the defect, not the requirement.
        assertThat(value.minInstances()).isEqualTo(2);
        assertThat(value.owningBlueprint()).isEqualTo(Option.none());
        assertThat(value.updatedAt()).isGreaterThan(0);
    }

    @Test
    void sliceTargetValue_creates_target_with_owner() {
        var version = Version.version("2.0.0").unwrap();
        var blueprintId = BlueprintId.blueprintId("org.example:app:1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version, 5, Option.some(blueprintId));

        assertThat(value.currentVersion()).isEqualTo(version);
        assertThat(value.targetInstances()).isEqualTo(5);
        assertThat(value.owningBlueprint()).isEqualTo(Option.some(blueprintId));
    }

    @Test
    void sliceTargetValue_withInstances_updates_count() {
        var version = Version.version("1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version, 3);

        var updated = value.withInstances(10);

        assertThat(updated.currentVersion()).isEqualTo(version);
        assertThat(updated.targetInstances()).isEqualTo(10);
        assertThat(updated.minInstances()).as("withInstances carries the floor untouched").isEqualTo(value.minInstances());
        assertThat(updated.owningBlueprint()).isEqualTo(value.owningBlueprint());
        assertThat(updated.updatedAt()).isGreaterThanOrEqualTo(value.updatedAt());
    }

    @Test
    void sliceTargetValue_effectiveMinInstances_defaultsToOne() {
        var version = Version.version("1.0.0").unwrap();
        var value = new SliceTargetValue(version, 3, 0, Option.none(), "CORE_ONLY", System.currentTimeMillis());

        assertThat(value.effectiveMinInstances()).isEqualTo(1);
    }

    @Test
    void sliceTargetValue_effectiveMinInstances_returnsMinInstances() {
        var version = Version.version("1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version, 5, 5);

        assertThat(value.effectiveMinInstances()).isEqualTo(5);
    }

    @Test
    void sliceTargetValue_withVersion_updates_version() {
        var version1 = Version.version("1.0.0").unwrap();
        var version2 = Version.version("2.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version1, 3);

        var updated = value.withVersion(version2);

        assertThat(updated.currentVersion()).isEqualTo(version2);
        assertThat(updated.targetInstances()).isEqualTo(3);
        assertThat(updated.owningBlueprint()).isEqualTo(value.owningBlueprint());
        assertThat(updated.updatedAt()).isGreaterThanOrEqualTo(value.updatedAt());
    }

    @Test
    void sliceTargetValue_withInstances_preserves_owning_blueprint() {
        var version = Version.version("1.0.0").unwrap();
        var blueprintId = BlueprintId.blueprintId("org.example:app:1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version, 3, Option.some(blueprintId));

        var updated = value.withInstances(7);

        assertThat(updated.owningBlueprint()).isEqualTo(Option.some(blueprintId));
    }

    @Test
    void sliceTargetValue_withVersion_preserves_owning_blueprint() {
        var version1 = Version.version("1.0.0").unwrap();
        var version2 = Version.version("2.0.0").unwrap();
        var blueprintId = BlueprintId.blueprintId("org.example:app:1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version1, 3, Option.some(blueprintId));

        var updated = value.withVersion(version2);

        assertThat(updated.owningBlueprint()).isEqualTo(Option.some(blueprintId));
    }

    @Test
    void sliceTargetValue_withVersion_preserves_autoscaler_overrides() {
        var version1 = Version.version("1.0.0").unwrap();
        var version2 = Version.version("2.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version1,
                                                      5,
                                                      2,
                                                      Option.none(),
                                                      Option.some(5),
                                                      Option.some(0.8),
                                                      Option.some(0.2));

        var updated = value.withVersion(version2);

        assertThat(updated.currentVersion()).isEqualTo(version2);
        assertThat(updated.maxInstances()).isEqualTo(Option.some(5));
        assertThat(updated.scaleUpThreshold()).isEqualTo(Option.some(0.8));
        assertThat(updated.scaleDownThreshold()).isEqualTo(Option.some(0.2));
    }

    @Test
    void sliceTargetValue_withInstances_preserves_autoscaler_overrides() {
        var version = Version.version("1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version,
                                                      5,
                                                      2,
                                                      Option.none(),
                                                      Option.some(5),
                                                      Option.some(0.8),
                                                      Option.some(0.2));

        var updated = value.withInstances(3);

        assertThat(updated.targetInstances()).isEqualTo(3);
        assertThat(updated.maxInstances()).isEqualTo(Option.some(5));
        assertThat(updated.scaleUpThreshold()).isEqualTo(Option.some(0.8));
        assertThat(updated.scaleDownThreshold()).isEqualTo(Option.some(0.2));
    }

    @Test
    void sliceTargetValue_withPlacement_preserves_autoscaler_overrides() {
        var version = Version.version("1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version,
                                                      5,
                                                      2,
                                                      Option.none(),
                                                      Option.some(5),
                                                      Option.some(0.8),
                                                      Option.some(0.2));

        var updated = value.withPlacement("CORE_AND_WORKERS");

        assertThat(updated.effectivePlacement()).isEqualTo("CORE_AND_WORKERS");
        assertThat(updated.maxInstances()).isEqualTo(Option.some(5));
        assertThat(updated.scaleUpThreshold()).isEqualTo(Option.some(0.8));
        assertThat(updated.scaleDownThreshold()).isEqualTo(Option.some(0.2));
    }

    @Test
    void sliceTargetValue_equality_works() {
        var version = Version.version("1.0.0").unwrap();
        // Create two values at the same time to have matching updatedAt
        var now = System.currentTimeMillis();
        var value1 = new SliceTargetValue(version, 3, 3, Option.none(), "CORE_ONLY", now);
        var value2 = new SliceTargetValue(version, 3, 3, Option.none(), "CORE_ONLY", now);

        assertThat(value1).isEqualTo(value2);
        assertThat(value1.hashCode()).isEqualTo(value2.hashCode());
    }

    @Test
    void sliceTargetValue_inequality_with_different_version() {
        var version1 = Version.version("1.0.0").unwrap();
        var version2 = Version.version("2.0.0").unwrap();
        var now = System.currentTimeMillis();
        var value1 = new SliceTargetValue(version1, 3, 3, Option.none(), "CORE_ONLY", now);
        var value2 = new SliceTargetValue(version2, 3, 3, Option.none(), "CORE_ONLY", now);

        assertThat(value1).isNotEqualTo(value2);
    }

    @Test
    void sliceTargetValue_inequality_with_different_instances() {
        var version = Version.version("1.0.0").unwrap();
        var now = System.currentTimeMillis();
        var value1 = new SliceTargetValue(version, 3, 3, Option.none(), "CORE_ONLY", now);
        var value2 = new SliceTargetValue(version, 5, 5, Option.none(), "CORE_ONLY", now);

        assertThat(value1).isNotEqualTo(value2);
    }

    /// #1497 — the one availability floor: `ceil(n/2)`, the same number a blueprint slice gets when it
    /// omits `minAvailable`. The blueprint parser keeps its own `Math.ceilDiv` (it cannot import this
    /// package without a cycle), so the parity is pinned here rather than shared.
    /// Parity is checked from the #1495 floor up: a blueprint below 3 instances is refused, so it has no default to
    /// compare with.
    @Test
    void defaultMinInstances_isCeilHalf_andMatchesTheBlueprintDefault() {
        java.util.stream.IntStream.rangeClosed(1, 9)
                                  .forEach(n -> assertThat(SliceTargetValue.defaultMinInstances(n)).as("n=" + n)
                                                                                                  .isEqualTo((n + 1) / 2));
        java.util.stream.IntStream.rangeClosed(org.pragmatica.aether.slice.blueprint.SliceSpec.MIN_INSTANCES, 9)
                                  .forEach(n -> assertThat(SliceTargetValue.defaultMinInstances(n)).as("blueprint parity, n=" + n)
                                                                                                  .isEqualTo(blueprintMinAvailable(n)));
    }

    /// #1497 — both factories that take no explicit floor use the default, so a writer that reaches for them
    /// cannot write `min == instances` by accident.
    @Test
    void sliceTargetValue_withoutAnExplicitFloor_takesTheDefault() {
        var version = Version.version("1.0.0").unwrap();
        var blueprintId = BlueprintId.blueprintId("org.example:app:1.0.0").unwrap();

        assertThat(SliceTargetValue.sliceTargetValue(version, 4).minInstances()).isEqualTo(2);
        assertThat(SliceTargetValue.sliceTargetValue(version, 5, Option.some(blueprintId)).minInstances()).isEqualTo(3);
        assertThat(SliceTargetValue.sliceTargetValue(version, 4, 4).minInstances()).as("an explicit floor is honoured")
                                                                                .isEqualTo(4);
    }

    private static int blueprintMinAvailable(int instances) {
        var dsl = """
                  id = "org.example:app:1.0.0"

                  [[slices]]
                  artifact = "org.example:svc:1.0.0"
                  instances = %d
                  """.formatted(instances);

        return org.pragmatica.aether.slice.blueprint.BlueprintParser.parse(dsl)
                                                                    .map(blueprint -> blueprint.slices()
                                                                                               .getFirst()
                                                                                               .minAvailable())
                                                                    .unwrap();
    }
}
