package org.pragmatica.aether.infra;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.infra.VersionedInstance.versionedInstance;

class VersionedInstanceTest {

    @Test
    void factory_method_creates_instance() {
        var instance = versionedInstance("1.2.3", "test");

        assertThat(instance.version()).isEqualTo("1.2.3");
        assertThat(instance.instance()).isEqualTo("test");
    }

    @Test
    void isCompatibleWith_exact_version_match() {
        var instance = versionedInstance("1.2.3", "test");

        assertThat(instance.isCompatibleWith("1.2.3")).isTrue();
    }

    @Test
    void isCompatibleWith_ignores_qualifiers() {
        var instance = versionedInstance("1.2.3-SNAPSHOT", "test");

        assertThat(instance.isCompatibleWith("1.2.3")).isTrue();
        assertThat(instance.isCompatibleWith("1.2.3-RC1")).isTrue();
    }

    @Test
    void isCompatibleWith_same_major_higher_minor() {
        var instance = versionedInstance("1.5.0", "test");

        assertThat(instance.isCompatibleWith("1.2.0")).isTrue();
    }

    @Test
    void isCompatibleWith_same_major_same_minor_higher_patch() {
        var instance = versionedInstance("1.2.5", "test");

        assertThat(instance.isCompatibleWith("1.2.3")).isTrue();
    }

    @Test
    void isCompatibleWith_rejects_different_major() {
        var instance = versionedInstance("2.0.0", "test");

        assertThat(instance.isCompatibleWith("1.0.0")).isFalse();
    }

    @Test
    void isCompatibleWith_rejects_lower_minor() {
        var instance = versionedInstance("1.1.0", "test");

        assertThat(instance.isCompatibleWith("1.2.0")).isFalse();
    }

    @Test
    void isCompatibleWith_rejects_lower_patch_when_minor_matches() {
        var instance = versionedInstance("1.2.2", "test");

        assertThat(instance.isCompatibleWith("1.2.3")).isFalse();
    }

    @Test
    void isExactMatch_same_version() {
        var instance = versionedInstance("1.2.3", "test");

        assertThat(instance.isExactMatch("1.2.3")).isTrue();
    }

    @Test
    void isExactMatch_ignores_qualifiers() {
        var instance = versionedInstance("1.2.3-SNAPSHOT", "test");

        assertThat(instance.isExactMatch("1.2.3")).isTrue();
        assertThat(instance.isExactMatch("1.2.3-RC1")).isTrue();
    }

    @Test
    void isExactMatch_rejects_different_version() {
        var instance = versionedInstance("1.2.3", "test");

        assertThat(instance.isExactMatch("1.2.4")).isFalse();
        assertThat(instance.isExactMatch("1.3.3")).isFalse();
        assertThat(instance.isExactMatch("2.2.3")).isFalse();
    }

    @Test
    void findCompatible_prefers_exact_match() {
        var instances = List.of(versionedInstance("1.5.0", "higher"),
                                versionedInstance("1.2.3", "exact"),
                                versionedInstance("1.3.0", "compatible"));

        var result = VersionedInstance.findCompatible(instances, "1.2.3");

        result.onEmpty(Assertions::fail)
              .onPresent(value -> assertThat(value).isEqualTo("exact"));
    }

    @Test
    void findCompatible_returns_compatible_when_no_exact() {
        var instances = List.of(versionedInstance("1.5.0", "higher"),
                                versionedInstance("1.3.0", "compatible"));

        var result = VersionedInstance.findCompatible(instances, "1.2.3");

        result.onEmpty(Assertions::fail)
              .onPresent(value -> assertThat(value).isEqualTo("higher"));
    }

    @Test
    void findCompatible_returns_none_when_no_compatible() {
        var instances = List.of(versionedInstance("1.1.0", "lower"),
                                versionedInstance("2.0.0", "different_major"));

        var result = VersionedInstance.findCompatible(instances, "1.2.3");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void findCompatible_returns_none_for_empty_list() {
        List<VersionedInstance<String>> instances = List.of();

        var result = VersionedInstance.findCompatible(instances, "1.2.3");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void findExact_returns_matching_version() {
        var instances = List.of(versionedInstance("1.0.0", "v1"),
                                versionedInstance("1.2.3", "v123"),
                                versionedInstance("2.0.0", "v2"));

        var result = VersionedInstance.findExact(instances, "1.2.3");

        result.onEmpty(Assertions::fail)
              .onPresent(value -> assertThat(value).isEqualTo("v123"));
    }

    @Test
    void findExact_returns_none_when_not_found() {
        var instances = List.of(versionedInstance("1.0.0", "v1"),
                                versionedInstance("2.0.0", "v2"));

        var result = VersionedInstance.findExact(instances, "1.2.3");

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void isCompatibleWith_handles_malformed_version() {
        var instance = versionedInstance("1.2.3", "test");

        // Malformed versions fall back to string equality
        assertThat(instance.isCompatibleWith("invalid")).isFalse();
        assertThat(instance.isCompatibleWith("1.2")).isFalse();
    }

    @Test
    void isCompatibleWith_handles_zero_versions() {
        var instance = versionedInstance("0.1.0", "test");

        assertThat(instance.isCompatibleWith("0.1.0")).isTrue();
        assertThat(instance.isCompatibleWith("0.0.1")).isTrue();
    }
}
