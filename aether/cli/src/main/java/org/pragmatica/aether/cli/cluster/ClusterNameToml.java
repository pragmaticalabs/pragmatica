// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Result.success;


/// Writes a `--cluster` override into the operator TOML's `[cluster] name`, so the TOML the CLI sends to the
/// cluster carries the override: the config bootstrap posts to the KV-Store at formation
/// (`ClusterConfigValue.tomlContent`), and the desired config `aether cluster apply` sends or diffs.
///
/// WHY (#1487): the override used to reach only the parsed config. The persisted TOML kept the file's
/// `[cluster] name`, and CTM resolves the cluster name from it — so every replacement was labelled
/// `aether-cluster=<TOML name>` while the seeds carried `<override>`, and CTM's inventory filters, scoped
/// reaping and teardown all missed the replacements. `apply` needs the same rewrite because `cluster.name`
/// is immutable: once the override is persisted, the unmodified file's name would be refused as a change.
///
/// The verbatim operator TOML is preserved except for the one `name` line (string edit, not a re-serialize),
/// the same discipline as [SshAuthorizedKeysToml]; a trailing comment on that line is dropped. The override is
/// validated through [ClusterName#clusterName] first (lowercase alphanumerics and `-`), so it needs no TOML
/// escaping. The rewritten TOML is then re-parsed and its `[cluster] name` must equal the override: a shape
/// the line edit misreads (a multi-line string in `[cluster]` holding a line that starts `name =`) fails
/// loudly instead of persisting a name the operator did not choose. Spellings the line edit cannot locate at
/// all (a root dotted `cluster.name = ...`, a quoted `"name" = ...`) fail loudly too.
sealed interface ClusterNameToml {
    record unused() implements ClusterNameToml {}

    String CLUSTER_SECTION = "cluster";
    String NAME_KEY = "name";
    Pattern CLUSTER_HEADER = Pattern.compile("(?m)^[ \\t]*\\[cluster\\][ \\t]*$");
    Pattern NEXT_HEADER = Pattern.compile("(?m)^[ \\t]*\\[");
    Pattern NAME_LINE = Pattern.compile("(?m)^([ \\t]*)name[ \\t]*=.*$");

    Cause NAME_NOT_FOUND = Causes.cause("cannot apply --cluster: no `name = ...` line found in the TOML's [cluster] section");

    /// The rewritten TOML's `[cluster] name` still differs from the override; `readName` is what it reads.
    record NameNotRewritten(String readName, String message) implements Cause {
        static final Fn1<NameNotRewritten, String> FACTORY = Causes.forOneValue("cannot apply --cluster: after rewriting the TOML, its [cluster] name reads '%s' — the `name` key is spelled in a form the rewrite cannot edit safely",
                                                                                NameNotRewritten::new);
    }

    static Result<String> withClusterName(String rawToml, String clusterName) {
        return ClusterName.clusterName(clusterName)
                          .flatMap(name -> rewriteNameLine(rawToml,
                                                           name.value()))
                          .flatMap(rewritten -> verifyRewrite(rewritten, clusterName));
    }

    private static Result<String> rewriteNameLine(String rawToml, String clusterName) {
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

    private static Result<String> verifyRewrite(String rewritten, String clusterName) {
        return TomlParser.parse(rewritten)
                         .map(doc -> doc.getString(CLUSTER_SECTION, NAME_KEY)
                                        .or(""))
                         .filter(NameNotRewritten.FACTORY, clusterName::equals)
                         .map(_ -> rewritten);
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
