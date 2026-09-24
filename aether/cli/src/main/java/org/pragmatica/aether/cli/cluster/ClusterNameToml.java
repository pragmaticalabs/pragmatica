// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;


/// Writes a `--cluster` override into the operator TOML's `[cluster] name`, so the TOML bootstrap posts to
/// the KV-Store at formation (`ClusterConfigValue.tomlContent`) carries the same name as the bootstrap VMs.
///
/// WHY (#1487): the override used to reach only the parsed config. The persisted TOML kept the file's
/// `[cluster] name`, and CTM resolves the cluster name from it — so every replacement was labelled
/// `aether-cluster=<TOML name>` while the seeds carried `<override>`, and CTM's inventory filters, scoped
/// reaping and teardown all missed the replacements.
///
/// The verbatim operator TOML is preserved except for the one `name` line (string edit, not a re-serialize),
/// the same discipline as [SshAuthorizedKeysToml]. The override has already passed `ClusterName.PATTERN`
/// (lowercase alphanumerics and `-`), so it needs no TOML escaping. The parser requires `[cluster] name`, so
/// a TOML that parsed always carries the line; a spelling this edit cannot locate fails loudly rather than
/// persisting the un-overridden name.
sealed interface ClusterNameToml {
    record unused() implements ClusterNameToml {}

    Pattern CLUSTER_HEADER = Pattern.compile("(?m)^[ \\t]*\\[cluster\\][ \\t]*(#.*)?$");
    Pattern NEXT_HEADER = Pattern.compile("(?m)^[ \\t]*\\[");
    Pattern NAME_LINE = Pattern.compile("(?m)^([ \\t]*)name[ \\t]*=.*$");

    Cause NAME_NOT_FOUND = Causes.cause("cannot apply --cluster: no `name = ...` line found in the TOML's [cluster] section");

    static Result<String> withClusterName(String rawToml, String clusterName) {
        var header = CLUSTER_HEADER.matcher(rawToml);

        if (!header.find()) {
            return NAME_NOT_FOUND.result();
        }

        var sectionStart = header.end();
        var sectionEnd = sectionEnd(rawToml, sectionStart);
        var nameLine = NAME_LINE.matcher(rawToml).region(sectionStart, sectionEnd);

        return nameLine.find()
               ? success(replaceNameLine(rawToml, nameLine, clusterName))
               : NAME_NOT_FOUND.result();
    }

    private static int sectionEnd(String rawToml, int sectionStart) {
        var next = NEXT_HEADER.matcher(rawToml);

        return next.find(sectionStart)
               ? next.start()
               : rawToml.length();
    }

    private static String replaceNameLine(String rawToml, Matcher nameLine, String clusterName) {
        return rawToml.substring(0, nameLine.start()) + nameLine.group(1)
             + "name = \"" + clusterName
             + "\"" + rawToml.substring(nameLine.end());
    }
}
