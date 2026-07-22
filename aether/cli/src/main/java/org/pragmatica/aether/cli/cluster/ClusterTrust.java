// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;


/// #209 — `cluster_secret`-derived CA trust for the bootstrap / management HTTPS client. The cluster's
/// auto-generated TLS leaf certs are signed by a CA deterministically derived from `cluster_secret`
/// via HKDF (see [`SelfSignedCertificateProvider`], the same algorithm the runtime uses). The operator
/// already holds `cluster_secret`, so the CLI can reproduce the CA and trust ONLY that chain — a real
/// MITM defense, replacing the previous trust-all (`enableTlsSkipVerify`) shortcut that accepted any
/// certificate during formation (when the formation POST carries the new operator API key).
public sealed interface ClusterTrust {
    /// Build an [`SSLContext`] whose trust store contains ONLY the CA derived from `clusterSecret`. Any
    /// server certificate not signed by that CA (e.g. an attacker's, or a different cluster's secret)
    /// is rejected at the TLS handshake.
    static Result<SSLContext> sslContextFor(String clusterSecret) {
        return SelfSignedCertificateProvider.selfSignedCertificateProvider(clusterSecret.getBytes(StandardCharsets.UTF_8))
                                            .flatMap(ClusterTrust::caPem)
                                            .flatMap(ClusterTrust::sslContextFromCaPem);
    }

    private static Result<byte[]> caPem(CertificateProvider provider) {
        return provider.caCertificate()
                       .map(bundle -> bundle.caCertificatePem());
    }

    private static Result<SSLContext> sslContextFromCaPem(byte[] caPem) {
        return Result.lift(Causes::fromThrowable, () -> buildSslContext(caPem));
    }

    // JBCT-RET-08: KeyStore.load(null,null) and SSLContext.init(null,…) — nulls are the JDK contract
    @SuppressWarnings({"JBCT-EX-01", "JBCT-RET-08"})
    private static SSLContext buildSslContext(byte[] caPem) throws Exception {
        var factory = CertificateFactory.getInstance("X.509");
        var caCert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(caPem));
        var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());

        keyStore.load(null, null);
        keyStore.setCertificateEntry("aether-cluster-ca", caCert);
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());

        tmf.init(keyStore);
        var sslContext = SSLContext.getInstance("TLS");

        sslContext.init(null, trustManagers(tmf), new SecureRandom());

        return sslContext;
    }

    private static TrustManager[] trustManagers(TrustManagerFactory tmf) {
        return tmf.getTrustManagers();
    }

    record unused() implements ClusterTrust {}
}
