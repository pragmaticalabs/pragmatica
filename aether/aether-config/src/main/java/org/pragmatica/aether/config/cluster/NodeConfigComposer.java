// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public interface NodeConfigComposer {
    static TomlDocument compose(TomlDocument globalDefault,
                                TomlDocument sourceTypeDefault,
                                Option<TomlDocument> operatorOverride,
                                TomlDocument cliOverlay) {
        var stage1 = mergeDocuments(globalDefault, sourceTypeDefault);
        var stage2 = operatorOverride.map(override -> mergeDocuments(stage1, override)).or(stage1);
        return mergeDocuments(stage2, cliOverlay);
    }

    static TomlDocument mergeDocuments(TomlDocument lower, TomlDocument higher) {
        var sections = mergeSections(lower.sections(), higher.sections());
        var tableArrays = mergeTableArrays(lower.tableArrays(), higher.tableArrays());
        return new TomlDocument(sections, tableArrays);
    }

    private static Map<String, Map<String, Object>> mergeSections(Map<String, Map<String, Object>> lower,
                                                                  Map<String, Map<String, Object>> higher) {
        var merged = new LinkedHashMap<String, Map<String, Object>>(lower);
        higher.forEach((section, higherKeys) -> merged.merge(section, higherKeys, NodeConfigComposer::mergeSection));
        return Map.copyOf(merged);
    }

    private static Map<String, Object> mergeSection(Map<String, Object> lower, Map<String, Object> higher) {
        var merged = new LinkedHashMap<String, Object>(lower);
        merged.putAll(higher);
        return Map.copyOf(merged);
    }

    private static Map<String, List<Map<String, Object>>> mergeTableArrays(Map<String, List<Map<String, Object>>> lower,
                                                                           Map<String, List<Map<String, Object>>> higher) {
        var merged = new LinkedHashMap<String, List<Map<String, Object>>>(lower);
        merged.putAll(higher);
        return Map.copyOf(merged);
    }
}
