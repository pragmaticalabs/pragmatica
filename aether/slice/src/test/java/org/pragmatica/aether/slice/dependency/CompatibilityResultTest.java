package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Version;

import static org.assertj.core.api.Assertions.assertThat;

class CompatibilityResultTest {

    @Test
    void check_compatible_with_exact_version() {
        var loadedVersion = Version.version("1.0.0").unwrap();
        var required = VersionPattern.parse("1.0.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isCompatible()).isTrue();
        assertThat(result.isConflict()).isFalse();
        assertThat(result).isInstanceOf(CompatibilityResult.Compatible.class);
    }

    @Test
    void check_incompatible_with_exact_version() {
        var loadedVersion = Version.version("1.0.0").unwrap();
        var required = VersionPattern.parse("2.0.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isCompatible()).isFalse();
        assertThat(result.isConflict()).isTrue();
        assertThat(result).isInstanceOf(CompatibilityResult.Conflict.class);
    }

    @Test
    void check_compatible_with_caret_pattern() {
        var loadedVersion = Version.version("1.5.0").unwrap();
        var required = VersionPattern.parse("^1.0.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isCompatible()).isTrue();
    }

    @Test
    void check_incompatible_with_caret_pattern_major_mismatch() {
        var loadedVersion = Version.version("2.0.0").unwrap();
        var required = VersionPattern.parse("^1.0.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isConflict()).isTrue();
        var conflict = (CompatibilityResult.Conflict) result;
        assertThat(conflict.loadedVersion()).isEqualTo(loadedVersion);
        assertThat(conflict.required()).isEqualTo(required);
    }

    @Test
    void check_incompatible_with_caret_pattern_version_too_low() {
        var loadedVersion = Version.version("0.9.0").unwrap();
        var required = VersionPattern.parse("^1.0.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isConflict()).isTrue();
    }

    @Test
    void check_compatible_with_tilde_pattern() {
        var loadedVersion = Version.version("1.2.5").unwrap();
        var required = VersionPattern.parse("~1.2.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isCompatible()).isTrue();
    }

    @Test
    void check_incompatible_with_tilde_pattern() {
        var loadedVersion = Version.version("1.3.0").unwrap();
        var required = VersionPattern.parse("~1.2.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isConflict()).isTrue();
    }

    @Test
    void check_compatible_with_range_pattern() {
        var loadedVersion = Version.version("1.5.0").unwrap();
        var required = VersionPattern.parse("[1.0.0,2.0.0)").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isCompatible()).isTrue();
    }

    @Test
    void check_incompatible_with_range_pattern() {
        var loadedVersion = Version.version("2.0.0").unwrap();
        var required = VersionPattern.parse("[1.0.0,2.0.0)").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isConflict()).isTrue();
    }

    @Test
    void check_compatible_with_comparison_pattern() {
        var loadedVersion = Version.version("2.0.0").unwrap();
        var required = VersionPattern.parse(">=1.5.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isCompatible()).isTrue();
    }

    @Test
    void check_incompatible_with_comparison_pattern() {
        var loadedVersion = Version.version("1.0.0").unwrap();
        var required = VersionPattern.parse(">=1.5.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result.isConflict()).isTrue();
    }

    @Test
    void compatible_record_contains_loaded_version() {
        var loadedVersion = Version.version("1.2.3").unwrap();
        var required = VersionPattern.parse("^1.0.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result).isInstanceOf(CompatibilityResult.Compatible.class);
        var compatible = (CompatibilityResult.Compatible) result;
        assertThat(compatible.loadedVersion()).isEqualTo(loadedVersion);
    }

    @Test
    void conflict_record_contains_both_versions() {
        var loadedVersion = Version.version("1.0.0").unwrap();
        var required = VersionPattern.parse("^2.0.0").unwrap();

        var result = CompatibilityResult.check(loadedVersion, required);

        assertThat(result).isInstanceOf(CompatibilityResult.Conflict.class);
        var conflict = (CompatibilityResult.Conflict) result;
        assertThat(conflict.loadedVersion()).isEqualTo(loadedVersion);
        assertThat(conflict.required()).isEqualTo(required);
    }
}
