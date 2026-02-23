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
        assertThat(value.minInstances()).isEqualTo(3);
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
        assertThat(updated.minInstances()).isEqualTo(3);
        assertThat(updated.owningBlueprint()).isEqualTo(value.owningBlueprint());
        assertThat(updated.updatedAt()).isGreaterThanOrEqualTo(value.updatedAt());
    }

    @Test
    void sliceTargetValue_effectiveMinInstances_defaultsToOne() {
        var version = Version.version("1.0.0").unwrap();
        var value = new SliceTargetValue(version, 3, 0, Option.none(), System.currentTimeMillis());

        assertThat(value.effectiveMinInstances()).isEqualTo(1);
    }

    @Test
    void sliceTargetValue_effectiveMinInstances_returnsMinInstances() {
        var version = Version.version("1.0.0").unwrap();
        var value = SliceTargetValue.sliceTargetValue(version, 5);

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
    void sliceTargetValue_equality_works() {
        var version = Version.version("1.0.0").unwrap();
        // Create two values at the same time to have matching updatedAt
        var now = System.currentTimeMillis();
        var value1 = new SliceTargetValue(version, 3, 3, Option.none(), now);
        var value2 = new SliceTargetValue(version, 3, 3, Option.none(), now);

        assertThat(value1).isEqualTo(value2);
        assertThat(value1.hashCode()).isEqualTo(value2.hashCode());
    }

    @Test
    void sliceTargetValue_inequality_with_different_version() {
        var version1 = Version.version("1.0.0").unwrap();
        var version2 = Version.version("2.0.0").unwrap();
        var now = System.currentTimeMillis();
        var value1 = new SliceTargetValue(version1, 3, 3, Option.none(), now);
        var value2 = new SliceTargetValue(version2, 3, 3, Option.none(), now);

        assertThat(value1).isNotEqualTo(value2);
    }

    @Test
    void sliceTargetValue_inequality_with_different_instances() {
        var version = Version.version("1.0.0").unwrap();
        var now = System.currentTimeMillis();
        var value1 = new SliceTargetValue(version, 3, 3, Option.none(), now);
        var value2 = new SliceTargetValue(version, 5, 5, Option.none(), now);

        assertThat(value1).isNotEqualTo(value2);
    }
}
