// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.testkit.deployedconfig.EndpointProbeFactory;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// #897: a slice declaring `@ResourceQualifier(type = ConfigurationSection.class)` can be unit-tested through the
/// kit. Config is supplied by the test as flat `section.key` entries, read through the SAME facade a deployed slice
/// gets (`ConfigFacade#configFacade`), so semantics such as `requireStringList` and the typed numeric parse match
/// production. The fixture is the one the deployed-path test (#889) drives through `SliceStore`.
class SliceTestKitConfigTest {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(10).seconds();
    private static final String SECTION = "deployed.endpoint";

    @Test
    void withConfig_deliversTheParsedRecord_throughTheRealGeneratedFactory() {
        var values = Map.of(SECTION + ".host", "kit.internal",
                            SECTION + ".port", "8443",
                            SECTION + ".secure", "true",
                            SECTION + ".tags", "[alpha, beta]",
                            SECTION + ".weight", "7");

        try (var sut = SliceTestKit.forSlice(EndpointProbeFactory::endpointProbe)
                                   .withConfig(values)
                                   .build()) {
            assertThat(sut.client().describe("probe").await(TIMEOUT).unwrap()).isEqualTo("probe=kit.internal|8443|true|alpha+beta|7");
        }
    }

    @Test
    void withConfig_leavesAnOptionalKeyAbsent_asNone() {
        var values = Map.of(SECTION + ".host", "kit.internal",
                            SECTION + ".port", "80",
                            SECTION + ".secure", "false",
                            SECTION + ".tags", "a, b");

        try (var sut = SliceTestKit.forSlice(EndpointProbeFactory::endpointProbe)
                                   .withConfig(values)
                                   .build()) {
            assertThat(sut.client().describe("probe").await(TIMEOUT).unwrap()).isEqualTo("probe=kit.internal|80|false|a+b|none");
        }
    }

    @Test
    void withConfig_refusesAMalformedValue_byNamingTheKey() {
        var values = Map.of(SECTION + ".host", "kit.internal",
                            SECTION + ".port", "80x",
                            SECTION + ".secure", "true",
                            SECTION + ".tags", "a");

        assertThatThrownBy(() -> SliceTestKit.forSlice(EndpointProbeFactory::endpointProbe)
                                             .withConfig(values)
                                             .build()).isInstanceOf(AssertionError.class)
                                                      .hasMessageContaining(SECTION + ".port")
                                                      .hasMessageContaining("slice-under-test");
    }

    /// The provider overload, directly: a TOML document layered over defaults. The document wins for the keys it
    /// declares, a key only the defaults declare still resolves, and a native TOML array is read as its elements.
    @Test
    void withConfig_provider_readsATomlDocumentLayeredOverDefaults(@TempDir Path dir) throws IOException {
        var toml = dir.resolve("slice.toml");

        Files.writeString(toml, """
                          [deployed.endpoint]
                          host = "toml.internal"
                          port = 8443
                          tags = ["alpha", "beta"]
                          """);

        var provider = ConfigurationProvider.builder()
                                            .withDefaults(Map.of(SECTION + ".port", "1", SECTION + ".secure", "true", SECTION + ".weight", "9"))
                                            .withTomlFile(toml)
                                            .build();

        try (var sut = SliceTestKit.forSlice(EndpointProbeFactory::endpointProbe)
                                   .withConfig(provider)
                                   .build()) {
            assertThat(sut.client().describe("probe").await(TIMEOUT).unwrap()).isEqualTo("probe=toml.internal|8443|true|alpha+beta|9");
        }
    }

    /// A repeated `withConfig` replaces the earlier source; it neither merges nor keeps the first.
    @Test
    void withConfig_repeated_lastWins() {
        var first = Map.of(SECTION + ".host", "first", SECTION + ".port", "1", SECTION + ".secure", "false", SECTION + ".tags", "x");
        var second = Map.of(SECTION + ".host", "second", SECTION + ".port", "2", SECTION + ".secure", "true", SECTION + ".tags", "y");

        try (var sut = SliceTestKit.forSlice(EndpointProbeFactory::endpointProbe)
                                   .withConfig(first)
                                   .withConfig(second)
                                   .build()) {
            assertThat(sut.client().describe("probe").await(TIMEOUT).unwrap()).isEqualTo("probe=second|2|true|y|none");
        }
    }

    /// Premise control: without `withConfig` the kit still hands the slice a facade that fails every read, so the
    /// tests above pass because of `withConfig` and not because the chain stopped asking.
    @Test
    void build_fails_whenNoConfigWasSupplied() {
        assertThatThrownBy(() -> SliceTestKit.forSlice(EndpointProbeFactory::endpointProbe)
                                             .build()).isInstanceOf(AssertionError.class);
    }
}
