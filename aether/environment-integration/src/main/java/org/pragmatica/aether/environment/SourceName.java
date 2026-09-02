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

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// The name of a provisioning SOURCE PROFILE — the `[source.X]` section of a cluster config, e.g.
/// `hetzner-eu`. It identifies WHERE a node comes from, and is stamped on every provisioned instance as
/// the `aether-source` label/tag.
///
/// It is a type rather than a `String` because it is load-bearing in two places where a wrong-but-valid
/// string fails silently:
///
///   - the provider's inventory selector is `{aether-cluster, aether-source, aether-role}`, so a minted
///     VM whose source label does not round-trip is invisible to its own reconciler, which then reads
///     `actual=0` forever and re-provisions every pass;
///   - the ingress firewall is selected by `(cluster, source)`, so the wrong source name resolves the
///     wrong firewall — or none, which on Hetzner means a node that accepts all inbound.
///
/// Both failures are of the kind that a `String` parameter list invites: several same-typed values in a
/// row where transposing two compiles cleanly and misbehaves at runtime.
///
/// **Validation is the COMMON DENOMINATOR across every supported cloud**, so that a value which parses
/// here is guaranteed to survive every provider's labelling untouched. The binding constraint is GCP:
/// its firewall rules TARGET network tags, and a network tag is an RFC-1035 label — lowercase letter
/// first, then lowercase alphanumerics and hyphens, ending alphanumeric, at most 63 characters. Hetzner
/// labels are laxer (`[a-zA-Z0-9._-]`, either case) and AWS/Azure tag VALUES laxer still, so the GCP rule
/// is the intersection.
///
/// Parsing rather than sanitizing is the point. Providers currently coerce out-of-charset values —
/// [HetznerComputeProvider#sanitizeLabelValue] rewrites disallowed characters to `-` — which silently
/// produces a label that no longer round-trips with the selector that has to find it again. Rejecting at
/// construction converts that silent mangling into one loud error at the only moment it is cheap to fix.
/// Every source name in the repository already satisfies the rule (`hetzner-eu`, `aws-us`, `gcp-1`,
/// `docker`, `remote`, `default`), so it costs nothing today and prevents an unrepresentable one later.
///
/// Deliberately NOT borrowed from [ResourceName]: that type also forbids `--`, which is a rule from its
/// own naming spec, not a cloud constraint. Inventing restrictions the clouds do not impose would make
/// this type reject names that work.
///
/// NOT used in `aether/slice` KV value records. `AetherValue.TopologyEntry` keeps a `String` source name
/// for the same reason it keeps a `String` role — that module deliberately does not depend on the layers
/// where these types live, and the deployment layer converts at its boundary.
public record SourceName(String value) {
    /// The source name used when no source profile can be resolved — non-cloud providers, tests, and the
    /// `ClusterTopologyManagerRecord.replacementSourceName` fallback when the persisted cluster config is
    /// unparseable or names no cloud source for the role.
    public static final SourceName DEFAULT = new SourceName("default");
    /// RFC-1035 label: lowercase letter, then lowercase alphanumerics and hyphens, ending alphanumeric,
    /// 1..63 characters. This is the GCP network-tag grammar, which is the strictest of the supported
    /// clouds and therefore the one that makes a name portable across all of them.
    private static final Pattern PATTERN = Pattern.compile("[a-z]([-a-z0-9]{0,61}[a-z0-9])?");

    public static Result<SourceName> sourceName(String value) {
        return Verify.ensure(value, Is::notNull, SOURCE_NAME_INVALID)
                     .flatMap(notNull -> Verify.ensure(notNull, Is::matches, PATTERN, SOURCE_NAME_INVALID))
                     .map(SourceName::new);
    }

    private static final Cause SOURCE_NAME_INVALID = Causes.cause("Source name must be an RFC-1035 label — a lowercase letter, then lowercase letters, "
                                                                 + "digits or hyphens, ending in a letter or digit, at most 63 characters (e.g. "
                                                                 + "'hetzner-eu'). This is the common denominator across the supported clouds: GCP "
                                                                 + "firewall rules target network tags, which admit nothing wider, so a name outside it "
                                                                 + "cannot be labelled identically on every provider.");

    /// Total conversion for callers holding a value already proven valid by construction — a TOML section
    /// key the parser accepted, a `TopologyEntry` field written by that same parser, a test literal.
    /// Prefer [#sourceName(String)] at every boundary that takes untrusted input.
    ///
    /// It VALIDATES, and falls back to [#DEFAULT] on anything the grammar rejects — not merely on blank.
    /// That is what keeps the type honest: every `SourceName` in existence satisfies the pattern, so the
    /// providers that stamp it as a label and the selectors that search on it can rely on the grammar
    /// rather than re-checking. A total factory that could mint an invalid instance would make the type's
    /// central promise false, and the label-that-matches-nothing failure it exists to prevent would come
    /// back through this door.
    ///
    /// The residual, recorded deliberately: an invalid input silently becomes `default` here rather than
    /// surfacing. No production caller can reach it — `ClusterBootstrapConfigParser` validates every
    /// source name at the TOML boundary, so map keys and persisted topology entries are already proven —
    /// and where an invalid name SHOULD be reported, the caller uses [#sourceName(String)] and keeps the
    /// [Result]. Use `sourceName(raw).or(SourceName.DEFAULT)` when a call site wants the fallback to be
    /// visible in the code rather than hidden in this helper.
    public static SourceName sourceNameOrDefault(String value) {
        return option(value).flatMap(raw -> sourceName(raw).option())
                     .or(DEFAULT);
    }

    @Override
    public String toString() {
        return value;
    }
}
