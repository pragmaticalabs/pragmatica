// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Option;


/// #1573: the pong metric keys carrying a node's cumulative slice execution outcomes —
/// `exec|<artifact>|<method>|ok` and `exec|<artifact>|<method>|defect`. One definition shared by the
/// producer ([ClusterSyncCollector]) and the leader-side all-instances-failed detector that parses them.
public sealed interface ExecutionOutcomeKeys {
    String PREFIX = "exec|";
    String SUCCESS_SUFFIX = "|ok";
    String DEFECT_SUFFIX = "|defect";

    /// One parsed execution-outcome key.
    record ParsedKey(Artifact artifact, String method, boolean defect) {}

    static String successKey(Artifact artifact, String method) {
        return PREFIX + artifact.asString() + "|" + method + SUCCESS_SUFFIX;
    }

    static String defectKey(Artifact artifact, String method) {
        return PREFIX + artifact.asString() + "|" + method + DEFECT_SUFFIX;
    }

    /// `none()` for any key that is not an execution-outcome key or whose artifact does not parse.
    static Option<ParsedKey> parse(String key) {
        if (!key.startsWith(PREFIX)) {
            return Option.none();
        }

        var parts = key.substring(PREFIX.length()).split("\\|");

        return parts.length == 3
               ? Artifact.artifact(parts[0])
                         .option()
                         .flatMap(artifact -> parsedKey(artifact, parts[1], parts[2]))
               : Option.none();
    }

    private static Option<ParsedKey> parsedKey(Artifact artifact, String method, String kind) {
        return switch (kind) {
            case "ok" -> Option.some(new ParsedKey(artifact, method, false));
            case "defect" -> Option.some(new ParsedKey(artifact, method, true));
            default -> Option.none();
        };
    }

    record unused() implements ExecutionOutcomeKeys {}
}
