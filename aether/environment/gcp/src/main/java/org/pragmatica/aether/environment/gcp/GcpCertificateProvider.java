// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.CloudCertificateProvider;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;

import static org.pragmatica.aether.environment.gcp.GcpSecretsProvider.gcpSecretsProvider;


public final class GcpCertificateProvider implements CertificateProvider {
    private final CertificateProvider delegate;

    private GcpCertificateProvider(CertificateProvider delegate) {
        this.delegate = delegate;
    }

    public static Result<GcpCertificateProvider> gcpCertificateProvider(GcpClient client, String secretPrefix) {
        return buildFromSecrets(gcpSecretsProvider(client), secretPrefix);
    }

    private static Result<GcpCertificateProvider> buildFromSecrets(GcpSecretsProvider secrets, String secretPrefix) {
        return CloudCertificateProvider.cloudCertificateProvider(secrets, secretPrefix)
                                                                .map(GcpCertificateProvider::new);
    }

    @Override public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
        return delegate.issueCertificate(nodeId, hostname);
    }

    @Override public Result<CertificateBundle> caCertificate() {
        return delegate.caCertificate();
    }

    @Override public Result<GossipKey> currentGossipKey() {
        return delegate.currentGossipKey();
    }

    @Override public Option<GossipKey> previousGossipKey() {
        return delegate.previousGossipKey();
    }
}
