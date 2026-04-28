// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@SuppressWarnings("JBCT-UTIL-02") public final class TomlDocumentMerger {
    private TomlDocumentMerger() {}

    public static TomlDocument merge(TomlDocument base, TomlDocument overlay) {
        var mergedSections = mergeSections(base.sections(), overlay.sections());
        var mergedTableArrays = mergeTableArrays(base.tableArrays(), overlay.tableArrays());
        return new TomlDocument(mergedSections, mergedTableArrays);
    }

    private static Map<String, Map<String, Object>> mergeSections(Map<String, Map<String, Object>> base,
                                                                  Map<String, Map<String, Object>> overlay) {
        var merged = new LinkedHashMap<>(base);
        overlay.forEach((section, overlayValues) -> merged.merge(section,
                                                                 overlayValues,
                                                                 TomlDocumentMerger::mergeKeyValues));
        return merged;
    }

    private static Map<String, Object> mergeKeyValues(Map<String, Object> base, Map<String, Object> overlay) {
        var merged = new LinkedHashMap<>(base);
        merged.putAll(overlay);
        return merged;
    }

    private static Map<String, List<Map<String, Object>>> mergeTableArrays(Map<String, List<Map<String, Object>>> base,
                                                                           Map<String, List<Map<String, Object>>> overlay) {
        var merged = new LinkedHashMap<>(base);
        merged.putAll(overlay);
        return merged;
    }
}
