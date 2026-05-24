// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.utility.IdGenerator;

import java.util.Map;


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
                               String sourceName,
                               Option<String> nodeId,
                               Option<String> peers,
                               int coreMax,
                               String provisionedBy,
                               Map<String, String> extraTags) {
    public static final int DEFAULT_CORE_MAX = 3;

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
        return nodeId.or(() -> IdGenerator.generate("aether-" + clusterName() + "-node"));
    }

    public static ProvisionContext provisionContext(String clusterName,
                                                    String role,
                                                    String sourceName,
                                                    Option<String> nodeId,
                                                    Option<String> peers,
                                                    int coreMax,
                                                    String provisionedBy,
                                                    Map<String, String> extraTags) {
        return new ProvisionContext(clusterName, role, sourceName, nodeId, peers, coreMax, provisionedBy, extraTags);
    }

    public static ProvisionContext provisionContext(String clusterName,
                                                    String role,
                                                    String sourceName,
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
