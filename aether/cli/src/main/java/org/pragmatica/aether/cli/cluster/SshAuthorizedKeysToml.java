// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.regex.Pattern;


/// Persists the resolved operator SSH public keys into the cluster config TOML that bootstrap posts
/// to the KV-Store at formation, as `[infrastructure.ssh] authorized_keys = [...]`.
///
/// WHY: a CTM auto-heal replacement boots on a leader VM that cannot read the operator-local
/// `public_key_file` paths. The only operator-key material reachable on the leader is what is
/// persisted in the cluster config TOML (`ClusterConfigValue.tomlContent`, which is the operator's
/// raw input TOML posted verbatim). Baking the resolved key CONTENTS into that TOML lets the CTM
/// replacement path inject them into its cloud-init `authorized_keys` exactly as a bootstrap-minted
/// node does — closing the "replacement VM rejects the operator SSH key" gap.
///
/// The verbatim operator TOML is preserved (string-append, not a re-serialize) so resolved
/// `${env:...}` references, comments and ordering are not disturbed. Idempotent: when the TOML
/// already declares `[infrastructure.ssh].authorized_keys` the operator's value wins and the input is
/// returned unchanged; an existing `[infrastructure.ssh]` section without the key gets the key
/// appended INTO it (TOML forbids duplicate section headers); otherwise a fresh section is appended.
/// RET-06: `rawToml` is raw operator TOML content; its null/empty coalesce is parse-boundary handling.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02", "JBCT-RET-06"})
sealed interface SshAuthorizedKeysToml {
    record unused() implements SshAuthorizedKeysToml {}

    String SECTION_HEADER = "[infrastructure.ssh]";
    Pattern AUTHORIZED_KEYS_KEY = Pattern.compile("(?m)^\\s*authorized_keys\\s*=");
    Pattern INFRA_SSH_HEADER = Pattern.compile("(?m)^\\s*\\[infrastructure\\.ssh\\]\\s*$");

    static String withAuthorizedKeys(String rawToml, List<String> authorizedKeys) {
        if (rawToml == null || authorizedKeys.isEmpty()) {
            return rawToml == null
                   ? ""
                   : rawToml;
        }

        if (AUTHORIZED_KEYS_KEY.matcher(rawToml).find()) {
            return rawToml;
        }

        return INFRA_SSH_HEADER.matcher(rawToml).find()
               ? injectIntoExistingSection(rawToml, authorizedKeys)
               : appendNewSection(rawToml, authorizedKeys);
    }

    private static String injectIntoExistingSection(String rawToml, List<String> authorizedKeys) {
        var matcher = INFRA_SSH_HEADER.matcher(rawToml);
        var _ = matcher.find();
        var insertAt = matcher.end();

        return rawToml.substring(0, insertAt)
             + "\n" + renderAuthorizedKeysLine(authorizedKeys) + rawToml.substring(insertAt);
    }

    private static String appendNewSection(String rawToml, List<String> authorizedKeys) {
        var separator = rawToml.endsWith("\n")
                        ? "\n"
                        : "\n\n";

        return rawToml + separator + SECTION_HEADER + "\n" + renderAuthorizedKeysLine(authorizedKeys) + "\n";
    }

    private static String renderAuthorizedKeysLine(List<String> authorizedKeys) {
        return "authorized_keys = [" + String.join(", ", quoteAll(authorizedKeys)) + "]";
    }

    private static List<String> quoteAll(List<String> authorizedKeys) {
        return authorizedKeys.stream()
                             .map(SshAuthorizedKeysToml::quote)
                             .toList();
    }

    private static String quote(String key) {
        return "\"" + key.replace("\\", "\\\\")
                         .replace("\"", "\\\"") + "\"";
    }
}
