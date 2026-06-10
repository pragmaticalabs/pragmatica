// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public final class IncludeResolver {
    private IncludeResolver() {}

    private static final int MAX_DEPTH = 16;

    public static Result<TomlDocument> resolve(TomlDocument doc, Path baseDir) {
        return resolve(doc, baseDir, new LinkedHashSet<>(), 0);
    }

    private static Result<TomlDocument> resolve(TomlDocument doc, Path baseDir, Set<Path> visited, int depth) {
        if (depth > MAX_DEPTH) {
            return new ClusterConfigError.ParseFailed("Include depth limit exceeded (max 16)").result();
        }

        return doc.getStringList("", "include")
                  .map(includes -> resolveIncludes(doc, baseDir, visited, depth, includes))
                  .or(success(doc));
    }

    private static Result<TomlDocument> resolveIncludes(TomlDocument doc,
                                                        Path baseDir,
                                                        Set<Path> visited,
                                                        int depth,
                                                        List<String> includes) {
        var accumulator = success(TomlDocument.EMPTY);

        for (var path : includes) {
            accumulator = accumulator.flatMap(acc -> resolveOneInclude(acc, baseDir, visited, depth, path));
        }

        return accumulator.map(merged -> mergeAndStripIncludes(merged, doc));
    }

    private static Result<TomlDocument> resolveOneInclude(TomlDocument accumulator,
                                                          Path baseDir,
                                                          Set<Path> visited,
                                                          int depth,
                                                          String path) {
        var resolved = baseDir.resolve(path).normalize();

        if (visited.contains(resolved)) {
            return new ClusterConfigError.ParseFailed("Circular include detected: " + resolved).result();
        }

        visited.add(resolved);

        return TomlParser.parseFile(resolved)
                         .mapError(cause -> new ClusterConfigError.ParseFailed(cause.message()))
                         .flatMap(parsed -> resolve(parsed,
                                                    resolved.getParent(),
                                                    visited,
                                                    depth + 1))
                         .map(resolvedDoc -> TomlDocumentMerger.merge(accumulator, resolvedDoc));
    }

    private static TomlDocument mergeAndStripIncludes(TomlDocument includesMerged, TomlDocument mainDoc) {
        var merged = TomlDocumentMerger.merge(includesMerged, mainDoc);

        return stripIncludeKey(merged);
    }

    private static TomlDocument stripIncludeKey(TomlDocument doc) {
        return Option.option(doc.sections().get(""))
                     .filter(root -> root.containsKey("include"))
                     .map(root -> removeIncludeFromRoot(doc, root))
                     .or(doc);
    }

    private static TomlDocument removeIncludeFromRoot(TomlDocument doc, Map<String, Object> rootSection) {
        var cleaned = new LinkedHashMap<>(rootSection);

        cleaned.remove("include");
        var sections = new LinkedHashMap<>(doc.sections());

        sections.put("", cleaned);

        return new TomlDocument(sections, doc.tableArrays());
    }
}
