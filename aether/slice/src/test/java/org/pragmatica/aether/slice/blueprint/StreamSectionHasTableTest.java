// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.config.toml.TomlParser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3495 items 2, 6, 7: `hasTable` is ONE rule for both section views, by keys. A sub-table is declared by the keys it
/// carries; an empty `[streams.orders.<name>]` header declares nothing and reads as absent in the TOML view and the
/// provider view alike.
class StreamSectionHasTableTest {
    private static final String SECTION = "streams.orders";

    private static StreamSection tomlView(String toml) {
        return StreamSection.tomlSection(TomlParser.parse(toml).unwrap(), SECTION, "orders");
    }

    private static StreamSection providerView(String toml) {
        var provider = ConfigurationProvider.builder()
                                            .withSource(TomlConfigSource.tomlConfigSource(toml).unwrap())
                                            .build();

        return StreamSection.providerSection(provider, SECTION);
    }

    /// Item 6 (mutant section-hasTable-false): a provider-backed section declaring a `retention.*` key reports the table.
    @Test
    void providerView_reportsTheTable_whenItCarriesAKey() {
        var view = providerView("[streams.orders]\nversion = \"1.0.0\"\n[streams.orders.retention]\nmax_age = \"1h\"\n");

        assertThat(view.hasTable("retention")).isTrue();
        assertThat(view.hasTable("hot")).isFalse();
    }

    /// Item 7 (mutant section-doc-hasTable-no-keys): a TOML-doc section with a dotted key reports the table, with no
    /// `[streams.orders.retention]` header in the document.
    @Test
    void tomlView_reportsTheTable_forADottedKeyWithoutAHeader() {
        var view = tomlView("[streams.orders]\nversion = \"1.0.0\"\nretention.max_age = \"1h\"\n");

        assertThat(view.hasTable("retention")).isTrue();
        assertThat(view.hasTable("hot")).isFalse();
    }

    /// Item 2: an empty sub-table header reads the same in both views: absent.
    @Test
    void anEmptySubTable_readsAbsent_inBothViews() {
        var toml = "[streams.orders]\nversion = \"1.0.0\"\n[streams.orders.hot]\n";

        assertThat(tomlView(toml).hasTable("hot")).isFalse();
        assertThat(providerView(toml).hasTable("hot")).isFalse();
    }

    /// Item 2: a sub-table with a key reads present in both views.
    @Test
    void aSubTableWithAKey_readsPresent_inBothViews() {
        var toml = "[streams.orders]\nversion = \"1.0.0\"\n[streams.orders.hot]\nmax_count = 100\n";

        assertThat(tomlView(toml).hasTable("hot")).isTrue();
        assertThat(providerView(toml).hasTable("hot")).isTrue();
    }
}
