// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.CloudCertificateProvider;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.GossipKey;

import static org.pragmatica.aether.environment.aws.AwsSecretsProvider.awsSecretsProvider;


public final class AwsCertificateProvider implements CertificateProvider {
    private final CertificateProvider delegate;

    private AwsCertificateProvider(CertificateProvider delegate) {
        this.delegate = delegate;
    }

    public static Result<AwsCertificateProvider> awsCertificateProvider(AwsClient client, String secretPrefix) {
        return awsSecretsProvider(client).flatMap(secrets -> buildFromSecrets(secrets, secretPrefix));
    }

    private static Result<AwsCertificateProvider> buildFromSecrets(AwsSecretsProvider secrets, String secretPrefix) {
        return CloudCertificateProvider.cloudCertificateProvider(secrets, secretPrefix).map(AwsCertificateProvider::new);
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
        return delegate.currentGossipKey();
    }

    @Override
    public Option<GossipKey> previousGossipKey() {
        return delegate.previousGossipKey();
    }
}
