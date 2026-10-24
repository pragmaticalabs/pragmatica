// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.pragmatica.lang.Option;


/// The config sections a slice manifest registers for config-change notification: every `config-update` reactive
/// binding's section, and the JSON codec settings section of a slice with HTTP routes (`json.settings.section`,
/// #2378), whose generated `notifyConfigUpdate` refreshes the slice's live JSON codec settings.
sealed interface ConfigUpdateSections {
    String JSON_SETTINGS_SECTION_KEY = "json.settings.section";

    static List<String> configUpdateSections(Properties manifest) {
        var sections = new ArrayList<String>();
        var count = Integer.parseInt(manifest.getProperty("reactive.count", "0"));

        for (int i = 0; i < count; i++) {
            var prefix = "reactive." + i + ".";

            if ("config-update".equals(manifest.getProperty(prefix + "category", ""))) {
                Option.option(manifest.getProperty(prefix + "config")).onPresent(sections::add);
            }
        }

        Option.option(manifest.getProperty(JSON_SETTINGS_SECTION_KEY)).onPresent(sections::add);

        return List.copyOf(sections);
    }

    record unused() implements ConfigUpdateSections {}
}
