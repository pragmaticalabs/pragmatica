// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// #1543 part C — the committed TOML's `[cluster] version` is the version replacements provision
/// (`NodeUserDataRenderer` reads image tag and jar URL from it), so an upgrade must change THAT, not only the
/// `ClusterConfigValue.version` copy beside it. Two operations: rewrite the one line, and name the runtime
/// profiles whose pinned `image`/`jar_url` would make the rewrite a no-op for replacements.
public sealed interface ClusterUpgradeToml {
    record unused() implements ClusterUpgradeToml {}

    Pattern SAFE_VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]*$");
    Pattern SECTION_HEADER = Pattern.compile("^\\s*\\[([^\\[\\]]*)\\]\\s*(#.*)?$");
    Pattern ANY_TABLE_HEADER = Pattern.compile("^\\s*\\[.*$");
    Pattern VERSION_LINE = Pattern.compile("^(\\s*version\\s*=\\s*)\"[^\"]*\"(.*)$");
    String CLUSTER_SECTION = "cluster";

    /// Rewrites the `version` line of the `[cluster]` table. The result is re-parsed and must read back
    /// exactly `targetVersion`: a TOML whose version is not a plain `version = "…"` line in `[cluster]`
    /// (inherited from a template, single-quoted, …) is refused rather than half-rewritten.
    static Result<String> withVersion(String toml, String targetVersion) {
        if (!SAFE_VERSION.matcher(targetVersion).matches()) {
            return new ClusterConfigError.ParseFailed("Target version '" + targetVersion
                                                     + "' is not a valid version string").result();
        }

        return rewriteVersionLine(toml, targetVersion).flatMap(rewritten -> confirmVersion(rewritten, targetVersion));
    }

    /// Names (sorted, distinct) of the runtime profiles referenced by a source role whose launch artifact is
    /// pinned: `image` for container runtimes, `jar_url` for JVM runtimes — exactly the field
    /// [NodeUserDataRenderer] prefers over the version-derived default.
    static List<String> pinnedRuntimeProfiles(ClusterBootstrapConfig config) {
        return referencedRuntimeRefs(config).stream()
                                    .filter(ref -> isPinned(config, ref))
                                    .sorted()
                                    .toList();
    }

    private static Set<String> referencedRuntimeRefs(ClusterBootstrapConfig config) {
        return config.sources()
                     .values()
                     .stream()
                     .flatMap(source -> source.roles()
                                              .values()
                                              .stream())
                     .map(RoleSubTable::runtimeRef)
                     .collect(Collectors.toSet());
    }

    private static boolean isPinned(ClusterBootstrapConfig config, String runtimeRef) {
        return Option.option(config.runtimes().get(runtimeRef))
                     .filter(RuntimeProfile::pinsArtifact)
                     .isPresent();
    }

    private static Result<String> rewriteVersionLine(String toml, String targetVersion) {
        var lines = toml.split("\n", -1);
        var out = new ArrayList<String>(lines.length);
        var inCluster = false;
        var replaced = false;

        for (var line : lines) {
            var header = SECTION_HEADER.matcher(line);

            if (header.matches()) {
                inCluster = CLUSTER_SECTION.equals(header.group(1).strip());
            } else if (ANY_TABLE_HEADER.matcher(line).matches()) {
                inCluster = false;
            }

            var versionLine = VERSION_LINE.matcher(line);

            if (inCluster && !replaced && versionLine.matches()) {
                out.add(versionLine.group(1) + "\"" + targetVersion + "\"" + versionLine.group(2));
                replaced = true;
            } else {
                out.add(line);
            }
        }

        return replaced
               ? success(String.join("\n", out))
               : new ClusterConfigError.ParseFailed("Committed config has no `version = \"…\"` line under [cluster] to rewrite").result();
    }

    private static Result<String> confirmVersion(String rewritten, String targetVersion) {
        return ClusterBootstrapConfigParser.parse(rewritten)
                                           .map(config -> config.cluster()
                                                                .version())
                                           .filter(new ClusterConfigError.ParseFailed("Rewritten config does not read back cluster.version '" + targetVersion
                                                                                     + "'"),
                                                   targetVersion::equals)
                                           .map(_ -> rewritten);
    }
}
