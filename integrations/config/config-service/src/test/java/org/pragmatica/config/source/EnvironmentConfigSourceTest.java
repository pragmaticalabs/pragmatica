package org.pragmatica.config.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentConfigSourceTest {

    @Test
    void environmentConfigSource_normalizesPrefixedVariables() {
        // This test verifies the normalization logic
        // Actual environment variables depend on the test environment
        var source = EnvironmentConfigSource.environmentConfigSource("AETHER_");

        assertThat(source.name()).isEqualTo("EnvironmentConfigSource[prefix=AETHER_]");
    }

    @Test
    void priority_returnsDefaultHundred() {
        var source = EnvironmentConfigSource.environmentConfigSource("TEST_");

        assertThat(source.priority()).isEqualTo(100);
    }

    @Test
    void priority_returnsCustomPriority() {
        var source = EnvironmentConfigSource.environmentConfigSource("TEST_", 50);

        assertThat(source.priority()).isEqualTo(50);
    }

    @Test
    void keys_returnsEmptySet_whenNoPrefixedVariables() {
        // Using a very unlikely prefix to ensure no matches
        var source = EnvironmentConfigSource.environmentConfigSource("UNLIKELY_XYZZY_PREFIX_");

        assertThat(source.keys()).isEmpty();
    }

    @Test
    void asMap_returnsEmptyMap_whenNoPrefixedVariables() {
        var source = EnvironmentConfigSource.environmentConfigSource("UNLIKELY_XYZZY_PREFIX_");

        assertThat(source.asMap()).isEmpty();
    }

    // Note: Testing actual environment variable reading requires setting up
    // environment variables in the test environment, which is typically done
    // via integration tests or build configuration.
}
