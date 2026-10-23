// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.json;

import java.util.Map;

import org.pragmatica.aether.slice.JsonCodecSelection;


/// A bound `[json]` section: every declared codec by name and the one selected for the slice's HTTP routes.
///
/// @param section  the section it was bound from, so a live handle can bind it again
/// @param codecs   the declared codecs by name; empty when none is declared
/// @param selected the codec in use: the `default`, the only declared codec, or [JsonCodecSelection#DEFAULT]
public record JsonSectionConfig(String section, Map<String, JsonCodecSelection> codecs, JsonCodecSelection selected) {
    public JsonSectionConfig {
        codecs = Map.copyOf(codecs);
    }

    /// No `[json]` keys: the implicit generated codec under default options.
    public static JsonSectionConfig defaults(String section) {
        return new JsonSectionConfig(section, Map.of(), JsonCodecSelection.DEFAULT);
    }

    public static JsonSectionConfig jsonSectionConfig(String section,
                                                      Map<String, JsonCodecSelection> codecs,
                                                      JsonCodecSelection selected) {
        return new JsonSectionConfig(section, codecs, selected);
    }
}
