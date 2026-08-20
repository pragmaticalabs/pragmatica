// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;


/// The name of a cluster. It is stamped on every provisioned resource as the `aether-cluster` label and
/// is half of the `(cluster, source)` selector that finds a source's ingress firewall.
///
/// **The invariant already existed; it was just being discarded.** [ClusterIdentity] validates the name
/// at the bootstrap-config boundary against this exact grammar and its javadoc promises that "all
/// downstream readers can trust the invariant" — but it hands back a bare `String`, so the proof is lost
/// at the first hop and every one of the ~375 downstream sites has to take it on faith. This type carries
/// the proof instead of re-asserting it.
///
/// Same RFC-1035 label grammar as [SourceName], and for the same reason: it must be safe as a Hetzner
/// label, a GCP network tag, a DNS label, and an env-var fragment simultaneously. Deliberately a DISTINCT
/// type rather than a shared "Label" — a cluster name and a source name are not interchangeable, and the
/// firewall selector takes one of each, adjacently. That is exactly the transposition a shared type would
/// permit and this one forbids.
///
/// **Absence is [Option#empty], not a sentinel.** Providers previously encoded "no cluster resolved" as
/// the magic string `"unknown"`, which is itself a VALID name under this grammar — so a cluster genuinely
/// called `unknown` was indistinguishable from an unresolved one. That mattered: RFC-0017 C2 refuses to
/// create a VM whose cluster cannot be identified, because a server labelled `aether-cluster=unknown` is
/// invisible to every scoped cleanup sweep and leaks as a permanently billable orphan that only an
/// account-wide reap would catch — and account-wide reaps are what destroyed the standing `test-pg` VM
/// (#572). `Option<ClusterName>` makes "unresolved" unrepresentable as a name.
public record ClusterName(String value) {
    /// RFC-1035 label. Identical to [SourceName]'s grammar, and the ONE copy of it: `ClusterIdentity`
    /// used to keep a second, and now delegates here.
    public static final Pattern PATTERN = Pattern.compile("[a-z]([-a-z0-9]{0,61}[a-z0-9])?");

    public static Result<ClusterName> clusterName(String value) {
        return Verify.ensure(value, Is::notNull, CLUSTER_NAME_INVALID)
                     .flatMap(notNull -> Verify.ensure(notNull, Is::matches, PATTERN, CLUSTER_NAME_INVALID))
                     .map(ClusterName::new);
    }

    /// Partial conversion for the resolution chain: provisioning context, then `AETHER_CLUSTER_NAME`, then
    /// provider config. Each step yields [Option#empty] rather than a placeholder, so "nothing resolved"
    /// stays distinguishable from "resolved to something odd" all the way to the refusal.
    public static Option<ClusterName> maybeClusterName(String value) {
        return option(value).flatMap(raw -> clusterName(raw).option());
    }

    private static final Cause CLUSTER_NAME_INVALID = Causes.cause("Cluster name must be an RFC-1035 label — a lowercase letter, then lowercase letters, "
                                                                  + "digits or hyphens, ending in a letter or digit, at most 63 characters (e.g. "
                                                                  + "'prod-eu'). The name is used simultaneously as a Hetzner label, a GCP network tag, a "
                                                                  + "DNS label and an env-var fragment, so it is held to the strictest of those.");

    @Override
    public String toString() {
        return value;
    }
}
