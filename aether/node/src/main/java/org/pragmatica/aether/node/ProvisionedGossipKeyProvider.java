// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.environment.GossipKeyMaterial;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// #1200 — a [CertificateProvider] whose gossip key is the one the cluster DELIVERED to this node, not the `cluster_secret`-derived one.
///
/// A node that boots after a gossip-key rotation derives a key the rotated cluster no longer accepts, so SWIM fails in both directions and no
/// quorum forms (see [GossipKeyDivergenceGuard]). The provisioning leader delivers the cluster's CURRENT key through the same channel that
/// carries `cluster_secret`; this decorator makes it the node's boot gossip key. Certificates are untouched and still come from the
/// `cluster_secret`-derived CA (one trust root).
///
/// The provisioned key is the ONLY gossip key: no previous or next key is offered, so the derived key is never accepted on this node's behalf.
/// A later rotation replaces it through the normal [GossipKeyRotationHandler] path.
public final class ProvisionedGossipKeyProvider implements CertificateProvider {
    private final CertificateProvider delegate;
    private final GossipKey gossipKey;

    private ProvisionedGossipKeyProvider(CertificateProvider delegate, GossipKey gossipKey) {
        this.delegate = delegate;
        this.gossipKey = gossipKey;
    }

    public static CertificateProvider provisionedGossipKeyProvider(CertificateProvider delegate,
                                                                   GossipKeyMaterial material) {
        return new ProvisionedGossipKeyProvider(delegate, material.asGossipKey());
    }

    /// `delegate` unchanged when no key was delivered: the cluster still runs on the derived key.
    public static CertificateProvider provisionedGossipKeyProvider(CertificateProvider delegate,
                                                                   Option<GossipKeyMaterial> material) {
        return material.map(key -> provisionedGossipKeyProvider(delegate, key))
                       .or(delegate);
    }

    @Override
    public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
        return delegate.issueCertificate(nodeId, hostname);
    }

    @Override
    public Result<CertificateBundle> caCertificate() {
        return delegate.caCertificate();
    }

    @Override
    public Result<GossipKey> currentGossipKey() {
        return success(gossipKey);
    }

    @Override
    public Option<GossipKey> previousGossipKey() {
        return none();
    }

    @Override
    public Option<GossipKey> nextGossipKey() {
        return none();
    }
}
