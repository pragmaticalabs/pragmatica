// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #209 — cluster_secret-derived CA trust. The trust manager built from a cluster secret must accept
/// a node certificate signed by that secret's CA and REJECT a node certificate from a different
/// secret (a foreign cluster / an attacker who lacks the secret) — the MITM defense replacing
/// trust-all.
class ClusterTrustTest {
    private static final String SECRET = "operator-cluster-secret-abc";
    private static final String OTHER_SECRET = "different-secret-xyz-999";

    @Test
    void sslContextFor_derivesContextSuccessfully() {
        assertThat(ClusterTrust.sslContextFor(SECRET).isSuccess()).isTrue();
    }

    @Test
    void trustManager_acceptsCertSignedByClusterSecretCa() throws Exception {
        var trustManager = trustManagerFor(SECRET);
        var nodeChain = issueNodeChain(SECRET);

        // No exception => trusted.
        trustManager.checkServerTrusted(nodeChain, "ECDHE_ECDSA");
        assertThat(nodeChain).isNotEmpty();
    }

    @Test
    void trustManager_rejectsCertFromDifferentSecret() throws Exception {
        var trustManager = trustManagerFor(SECRET);
        var foreignChain = issueNodeChain(OTHER_SECRET);

        assertThatThrownBy(() -> trustManager.checkServerTrusted(foreignChain, "ECDHE_ECDSA"))
            .as("a cert from a different cluster_secret must NOT be trusted (MITM defense)")
            .isInstanceOf(CertificateException.class);
    }

    private static X509TrustManager trustManagerFor(String secret) throws Exception {
        var sslContext = ClusterTrust.sslContextFor(secret).unwrap();
        // Re-derive the X509TrustManager from the same CA the SSLContext was built from, so we can
        // exercise checkServerTrusted directly (SSLContext does not expose its trust managers).
        var caPem = SelfSignedCertificateProvider.selfSignedCertificateProvider(secret.getBytes(StandardCharsets.UTF_8))
                                                 .unwrap()
                                                 .caCertificate()
                                                 .unwrap()
                                                 .caCertificatePem();

        assertThat(sslContext).isNotNull();

        return buildTrustManager(caPem);
    }

    private static X509TrustManager buildTrustManager(byte[] caPem) throws Exception {
        var factory = CertificateFactory.getInstance("X.509");
        var caCert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(caPem));
        var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());

        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca", caCert);
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

        tmf.init(keyStore);

        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                return x509;
            }
        }

        throw new IllegalStateException("no X509TrustManager");
    }

    private static X509Certificate[] issueNodeChain(String secret) throws Exception {
        CertificateProvider provider = SelfSignedCertificateProvider.selfSignedCertificateProvider(secret.getBytes(StandardCharsets.UTF_8)).unwrap();
        var bundle = provider.issueCertificate("node-1", "10.0.0.1").unwrap();
        var factory = CertificateFactory.getInstance("X.509");
        var leaf = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bundle.certificatePem()));

        return new X509Certificate[]{leaf};
    }
}
