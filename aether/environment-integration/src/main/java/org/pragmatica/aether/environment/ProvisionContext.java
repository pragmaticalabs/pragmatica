// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.utility.IdGenerator;


/// Typed payload for cloud-provider provisioning intent.
///
/// Replaces the historical `Map<String, String> tags` on [ProvisionSpec] which
/// required each provider to pull values out via `getOrDefault(...)` against
/// implicit, doc-only string keys (and where two flavours — `aether-*` for
/// Hetzner-spec dashes and `aether.*` for Docker-style dots — coexisted in the
/// same Map and were silently dropped or defaulted depending on the consumer).
///
/// Each provider is responsible for translating these typed fields into its
/// own native encoding (Hetzner labels / Docker labels+env / EC2 tags / etc.)
/// inside its own implementation.
///
/// Field semantics:
///  - [#clusterName] — caller is expected to pass an already-validated cluster
///    name (e.g. `ClusterIdentity.name()`). The type-side is a `String` to keep
///    the `environment-integration` module a leaf without an `aether-config`
///    dependency.
///  - [#role] — node role: `core`, `worker`, `spot`, `lb`, ...
///  - [#sourceName] — source profile name (e.g. `eu-1`, `hetzner-eu`, or
///    `default` for runtime auto-heal). Used by reapers to scope cleanup to
///    a specific source.
///  - [#nodeId] — unique node ID; Docker maps it to the container name, cloud
///    bootstrap embeds it in user-data and does not need it in the provider tag
///    payload. May be empty when the caller hasn't allocated one yet.
///  - [#peers] — comma-separated `nodeId:host:port` PEERS list (3-part). Empty
///    pre-formation (bootstrap-time provisioning); populated by CTM auto-heal
///    once the cluster has formed.
///  - [#coreMax] — desired core-count for the runtime entrypoint. Bootstrap
///    leaves the default; CTM overrides with the live snapshot.
///  - [#provisionedBy] — `bootstrap` or `ctm`. Threaded into the runtime as
///    `AETHER_PROVISIONED_BY` so the node can self-attribute its origin.
///  - [#extraTags] — escape hatch for caller-supplied native-format tags that
///    the typed fields don't yet cover. Defensively copied. Providers MAY
///    filter values that don't match their key/value regex.
public record ProvisionContext(String clusterName,
                               String role,
                               SourceName sourceName,
                               Option<String> nodeId,
                               Option<String> peers,
                               int coreMax,
                               String provisionedBy,
                               Map<String, String> extraTags) {
    public static final int DEFAULT_CORE_MAX = 3;
    /// Delegates to [SourceName#DEFAULT] — the one definition of the fallback source name. Retained as
    /// a [ProvisionContext] constant because the provisioning-side callers and error messages that name
    /// the fallback read at this layer.
    public static final SourceName DEFAULT_SOURCE_NAME = SourceName.DEFAULT;
    public static final String PROVISIONED_BY_BOOTSTRAP = "bootstrap";
    public static final String PROVISIONED_BY_CTM = "ctm";

    public ProvisionContext {
        extraTags = Map.copyOf(extraTags);
    }

    /// Canonical node-id resolution: honor a caller-supplied [#nodeId] when present
    /// (bootstrap path), otherwise self-allocate a fresh `aether-<cluster>-node-*`
    /// id. Providers call this so identity is owned provider-side and echoed back via
    /// [InstanceInfo#nodeId], rather than only tagging when the caller supplied one.
    public String resolveNodeId() {
        return nodeId.or(() -> IdGenerator.generate(coreNodeNamePrefix(clusterName())));
    }

    /// Canonical core-node name prefix for a cluster: `aether-<cluster>-node`. Compose
    /// seeds are `<prefix>-<ordinal>` and auto-heal/bootstrap ids are `<prefix>-<ulid>`,
    /// so every core container of a cluster shares the `aether-<cluster>-` substring and
    /// a replacement is shape-identical to a seed. Blank-defensive: a null/blank cluster
    /// name collapses to the canonical `aether-node` (single dash, no empty cluster
    /// segment) instead of the malformed `aether--node`.
    public static String coreNodeNamePrefix(String clusterName) {
        return Option.option(clusterName)
                     .map(String::trim)
                     .filter(name -> !name.isEmpty())
                     .map(name -> "aether-" + name + "-node")
                     .or("aether-node");
    }

    public static ProvisionContext provisionContext(String clusterName,
                                                    String role,
                                                    SourceName sourceName,
                                                    Option<String> nodeId,
                                                    Option<String> peers,
                                                    int coreMax,
                                                    String provisionedBy,
                                                    Map<String, String> extraTags) {
        return new ProvisionContext(clusterName, role, sourceName, nodeId, peers, coreMax, provisionedBy, extraTags);
    }

    public static ProvisionContext provisionContext(String clusterName,
                                                    String role,
                                                    SourceName sourceName,
                                                    String provisionedBy) {
        return new ProvisionContext(clusterName,
                                    role,
                                    sourceName,
                                    Option.empty(),
                                    Option.empty(),
                                    DEFAULT_CORE_MAX,
                                    provisionedBy,
                                    Map.of());
    }

    /// Bootstrap-time provisioning context. The cluster has not formed yet, so [#peers]
    /// is empty and [#coreMax] is the [#DEFAULT_CORE_MAX]. The caller supplies the role,
    /// source profile name and the pre-allocated node id; [#provisionedBy] is fixed to
    /// [#PROVISIONED_BY_BOOTSTRAP]. Shared with the CTM auto-heal path
    /// ([#forReplacement]) so both intents are minted through one preparation path.
    public static ProvisionContext forBootstrap(String clusterName, String role, SourceName sourceName, String nodeId) {
        return new ProvisionContext(clusterName,
                                    role,
                                    sourceName,
                                    Option.some(nodeId),
                                    Option.empty(),
                                    DEFAULT_CORE_MAX,
                                    PROVISIONED_BY_BOOTSTRAP,
                                    Map.of());
    }

    /// Auto-heal (CTM) replacement provisioning context. The cluster has formed, so the
    /// caller threads the live-member-derived [#peers] list and the snapshot-desired
    /// [#coreMax]. The caller supplies the INTENDED role and the SOURCE PROFILE NAME explicitly
    /// (Wave 2 / W4 of the cluster-topology-overhaul spec — the provisioned node's role is
    /// stamped end-to-end, never hardcoded here nor inherited from the provisioning host's
    /// environment); [#provisionedBy] is fixed to [#PROVISIONED_BY_CTM]. Shared with the
    /// bootstrap path ([#forBootstrap]) so both intents are minted through one preparation path.
    ///
    /// `sourceName` MUST be the topology entry's / cluster config's real source name: the
    /// provider stamps it as the `aether-source` label, and the CTM's worker reconcile pass
    /// lists ACTUAL inventory with a `{aether-cluster, aether-source, aether-role}` selector.
    /// A minted VM whose label does not round-trip with that selector is invisible to its own
    /// reconciler, which then reads `actual=0` forever, re-provisions every pass, and can never
    /// see a scale-down victim.
    public static ProvisionContext forReplacement(String clusterName,
                                                  String role,
                                                  SourceName sourceName,
                                                  String nodeId,
                                                  String peers,
                                                  int coreMax) {
        return new ProvisionContext(clusterName,
                                    role,
                                    sourceName,
                                    Option.some(nodeId),
                                    Option.some(peers),
                                    coreMax,
                                    PROVISIONED_BY_CTM,
                                    Map.of());
    }

    /// Source-less replacement context: [#sourceName] degrades to [#DEFAULT_SOURCE_NAME]. Retained
    /// for callers with no resolvable source profile (non-cloud providers, tests). Production
    /// callers use the overload above — see its note on the label/selector round-trip.
    public static ProvisionContext forReplacement(String clusterName,
                                                  String role,
                                                  String nodeId,
                                                  String peers,
                                                  int coreMax) {
        return forReplacement(clusterName, role, DEFAULT_SOURCE_NAME, nodeId, peers, coreMax);
    }

    public ProvisionContext withNodeId(String value) {
        return new ProvisionContext(clusterName,
                                    role,
                                    sourceName,
                                    Option.some(value),
                                    peers,
                                    coreMax,
                                    provisionedBy,
                                    extraTags);
    }

    public ProvisionContext withPeers(String value) {
        return new ProvisionContext(clusterName,
                                    role,
                                    sourceName,
                                    nodeId,
                                    Option.some(value),
                                    coreMax,
                                    provisionedBy,
                                    extraTags);
    }

    public ProvisionContext withCoreMax(int value) {
        return new ProvisionContext(clusterName, role, sourceName, nodeId, peers, value, provisionedBy, extraTags);
    }

    public ProvisionContext withExtraTags(Map<String, String> value) {
        return new ProvisionContext(clusterName, role, sourceName, nodeId, peers, coreMax, provisionedBy, value);
    }
}
