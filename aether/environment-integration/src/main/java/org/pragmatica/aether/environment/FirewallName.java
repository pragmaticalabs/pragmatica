// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.regex.Pattern;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.Verify.Is;
import org.pragmatica.lang.utils.Causes;


/// The name Aether gives the ingress resource it creates for one `(cluster, source)` pair.
///
/// Derived, not chosen: [#forSource] builds `aether-<cluster>-<source>` from two values that are already
/// RFC-1035 labels, so the result is one too — same grammar as [ClusterName] and [SourceName], since the
/// name has to be legal as a Hetzner firewall name, an AWS security-group name and a GCP firewall-rule
/// name simultaneously.
///
/// ## ⚠ The 63-character bound is load-bearing for GCP, and truncation can COLLIDE
///
/// Both inputs may themselves be 63 characters, so the concatenation can reach ~134 and must be cut to
/// fit. Truncation keeps the PREFIX, which means two different `(cluster, source)` pairs sharing a long
/// enough prefix produce the SAME name.
///
/// Today that is harmless, because the one provider that manages ingress selects its firewall by LABEL
/// (`aether-cluster` + `aether-source`, see `HetznerComputeProvider.firewallSelector`) and treats this
/// name as a display string only.
///
/// **It stops being harmless on GCP.** GCP firewall rules do not support labels at all, so selection
/// there must key on the rule NAME — at which point a truncation collision means one source resolves
/// another source's firewall, and `closeIngress` withdraws rules from the wrong resource. Whoever
/// implements the GCP provider (#463) must either bound the inputs so the sum fits, or make truncation
/// collision-resistant (e.g. keep a prefix plus a short digest of the full name). Do not simply reuse
/// [#forSource] and assume the name is unique.
///
/// This type deliberately does NOT fix that today: it preserves the existing truncation behaviour exactly
/// so that typing changes nothing, and records the hazard where the person who will hit it is looking.
public record FirewallName(String value) {
    public static final int MAX_LENGTH = 63;
    /// Same RFC-1035 label grammar as [ClusterName] / [SourceName].
    public static final Pattern PATTERN = Pattern.compile("[a-z]([-a-z0-9]{0,61}[a-z0-9])?");

    public static Result<FirewallName> firewallName(String value) {
        return Verify.ensure(value, Is::notNull, FIREWALL_NAME_INVALID)
                     .flatMap(notNull -> Verify.ensure(notNull, Is::matches, PATTERN, FIREWALL_NAME_INVALID))
                     .map(FirewallName::new);
    }

    /// The canonical derivation. Total by construction: both inputs are RFC-1035 labels, `aether-` is a
    /// legal prefix, and [#trimToLabel] restores the end-alphanumeric rule the cut can break — so the
    /// result always satisfies [#PATTERN] and never needs the fallible factory. `FirewallNameTest`
    /// re-parses the derived name for the maximal and hyphen-run pairs, so the totality claim is pinned
    /// rather than asserted.
    public static FirewallName forSource(ClusterName cluster, SourceName source) {
        return new FirewallName(trimToLabel("aether-" + cluster.value() + "-" + source.value()));
    }

    /// Truncate to [#MAX_LENGTH], then drop EVERY trailing hyphen the cut may have exposed — a label may
    /// not end in one.
    ///
    /// All of them, not one: both inputs are labels, and the grammar admits `--` inside a label
    /// (`a--b` is legal), so a cut can expose a run. Dropping a single hyphen would leave a value that
    /// ends in one, i.e. an instance of this type that does not satisfy [#PATTERN] — which would make
    /// [#forSource]'s totality claim false. It also keeps the result byte-identical to what
    /// `HetznerComputeProvider.sanitizeLabelValue` produced for this value before it was typed (its
    /// edge-trim is `[._-]+$`), so replacing that call with this derivation changes nothing.
    private static String trimToLabel(String raw) {
        var capped = raw.length() > MAX_LENGTH
                     ? raw.substring(0, MAX_LENGTH)
                     : raw;

        return TRAILING_HYPHENS.matcher(capped).replaceAll("");
    }

    private static final Pattern TRAILING_HYPHENS = Pattern.compile("-+$");

    private static final Cause FIREWALL_NAME_INVALID = Causes.cause("Firewall name must be an RFC-1035 label of at most 63 characters — it has to be legal "
                                                                   + "as a Hetzner firewall name, an AWS security-group name and a GCP firewall-rule name "
                                                                   + "at the same time.");

    @Override
    public String toString() {
        return value;
    }
}
