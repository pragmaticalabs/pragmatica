/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/// Pure four-layer deep-merge for node TOML configurations.
///
/// Precedence (highest wins): CLI overlay > operator override > source-type default > global default.
///
/// Merge rules (matching template-inheritance semantics):
///
///   - Tables (TOML sections) merge recursively, key-by-key.
///   - Leaf scalar values are replaced.
///   - Arrays are replaced entirely (no append) — operators get clean override semantics.
///   - Inline tables (Map values) are replaced entirely; their interior is opaque.
///
/// Higher layers may be [Option#empty] in which case the previous result is carried forward
/// unchanged. This keeps the call site simple even when not all layers are present.
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
