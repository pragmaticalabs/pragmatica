// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

/// Test client side of the HTTP-listener TLS rotation pins: a real TLS handshake and request against a listener, trusting
/// every certificate (the question is WHICH certificate the listener presents and whether it speaks TLS at all, not trust).
public final class TlsProbe {
    private TlsProbe() {}


    public static CertificateBundle validBundle(String nodeId) {
        return SelfSignedCertificateProvider.selfSignedCertificateProvider("tls-rotation-test-secret".getBytes())
                                            .unwrap()
                                            .issueCertificate(nodeId, "localhost")
                                            .unwrap();
    }

    /// Each half valid, but the key belongs to another certificate: every context builds, no handshake can complete.
    public static CertificateBundle mismatchedBundle() {
        var certificateOwner = validBundle("mismatch-cert-node");
        var keyOwner = validBundle("mismatch-key-node");

        return new CertificateBundle(certificateOwner.certificatePem(),
                                     keyOwner.privateKeyPem(),
                                     certificateOwner.caCertificatePem(),
                                     certificateOwner.notAfter());
    }

    public static CertificateBundle garbageBundle() {
        return new CertificateBundle("not a certificate".getBytes(), "not a key".getBytes(), new byte[0], Instant.now());
    }

    /// A sink that records every event it is handed (asynchronously, as the node's own sink does).
    public static OperatorWarningSink recordingSink(List<OperatorWarning> events) {
        return OperatorWarningSink.handingOffTo(events::add);
    }

    public static List<OperatorWarning> newEventList() {
        return new CopyOnWriteArrayList<>();
    }

    /// Completes a TLS handshake and returns the leaf certificate the listener presented.
    public static X509Certificate presentedCertificate(int port) throws Exception {
        try (var plain = new Socket()) {
            plain.connect(new InetSocketAddress("127.0.0.1", port), 3_000);
            plain.setSoTimeout(5_000);
            try (var socket = (SSLSocket) trustAll().getSocketFactory().createSocket(plain, "127.0.0.1", port, false)) {
                socket.startHandshake();

                return (X509Certificate) socket.getSession().getPeerCertificates()[0];
            }
        }
    }

    /// A real HTTPS request; any HTTP status proves the listener terminated TLS and spoke HTTP.
    public static int httpsStatus(int port) throws Exception {
        var connection = (HttpsURLConnection) URI.create("https://127.0.0.1:" + port + "/").toURL().openConnection();

        connection.setSSLSocketFactory(trustAll().getSocketFactory());
        connection.setHostnameVerifier((_, _) -> true);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    /// Whether a PLAIN-text HTTP request is answered with HTTP (it must not be on a TLS port).
    public static boolean plainHttpAnswered(int port) {
        try (var client = HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                        HttpResponse.BodyHandlers.discarding());

            return true;
        } catch (Exception notHttp) {
            return false;
        }
    }

    public static boolean eventually(BooleanSupplier condition, long budgetMs) throws InterruptedException {
        var deadline = System.nanoTime() + budgetMs * 1_000_000L;

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(25);
        }

        return true;
    }

    public static long countOf(List<OperatorWarning> events, String code) {
        return events.stream().filter(event -> event.code().code().equals(code)).count();
    }

    public static boolean sameCertificate(X509Certificate a, X509Certificate b) throws Exception {
        return Arrays.equals(a.getEncoded(), b.getEncoded());
    }

    public static int freeTcpPort() throws IOException {
        return org.pragmatica.aether.node.ClusterTestPorts.freeTcpAndUdpPort();
    }

    private static SSLContext trustAll() throws Exception {
        var context = SSLContext.getInstance("TLS");

        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);

        return context;
    }
}
