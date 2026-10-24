// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2378: a routed slice's manifest registers its JSON codec settings section for config-change notification beside
/// its `config-update` bindings, so a committed `json.*` change reaches the slice's generated `notifyConfigUpdate`.
class ConfigUpdateSectionsTest {
    private static Properties manifest(String text) throws IOException {
        var properties = new Properties();

        properties.load(new StringReader(text));

        return properties;
    }

    @Test
    void routedSlice_registersTheJsonSection_besideItsConfigUpdateBindings() throws IOException {
        var sections = ConfigUpdateSections.configUpdateSections(manifest("""
            reactive.count=3
            reactive.0.category=config-update
            reactive.0.config=database
            reactive.1.category=scheduled
            reactive.1.config=cleanup
            reactive.2.category=config-update
            json.settings.section=json
            """));

        assertThat(sections).containsExactly("database", "json");
    }

    @Test
    void sliceWithoutRoutes_registersOnlyItsConfigUpdateBindings() throws IOException {
        assertThat(ConfigUpdateSections.configUpdateSections(manifest("""
            reactive.count=1
            reactive.0.category=config-update
            reactive.0.config=database
            """))).containsExactly("database");
        assertThat(ConfigUpdateSections.configUpdateSections(manifest("slice.name=Plain\n"))).isEmpty();
    }
}
