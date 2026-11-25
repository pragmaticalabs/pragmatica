/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.http.server;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCounted;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.ClientAuthPolicy;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.TlsContextFactory;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateProvider;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;

/// #2032 test fixtures: distinct certificates and the server and client TLS contexts the [LiveTls] tests build from them.
final class TlsCertificates {
    private static final CertificateProvider PROVIDER = SelfSignedCertificateProvider.selfSignedCertificateProvider("rotation-secret".getBytes())
                                                                                     .unwrap();

    private TlsCertificates() {}

    /// A certificate of its own for `nodeId`, signed by the fixture CA.
    static CertificateBundle bundle(String nodeId) {
        return PROVIDER.issueCertificate(nodeId, "localhost").unwrap();
    }

    static TlsConfig http1Config(CertificateBundle bundle) {
        return new TlsConfig.Server(new TlsConfig.Identity.FromProvider(bundle.certificatePem(), bundle.privateKeyPem()),
                                    Option.none());
    }

    static SslContext http1Server(CertificateBundle bundle) {
        return TlsContextFactory.createServer(http1Config(bundle)).unwrap();
    }

    static QuicSslContext http3Server(CertificateBundle bundle) {
        return QuicSslContextFactory.createServerFromBundle(bundle,
                                                            ClientAuthPolicy.NOT_REQUESTED,
                                                            Http3.supportedApplicationProtocols())
                                    .unwrap();
    }

    /// A server QUIC context built without any ALPN protocol: it is a server context, yet completes no HTTP/3 handshake.
    static QuicSslContext http3ServerWithoutH3(CertificateBundle bundle) {
        return QuicSslContextFactory.createServerFromBundle(bundle, ClientAuthPolicy.NOT_REQUESTED, "not-h3").unwrap();
    }

    static SslContext http1Client() throws Exception {
        return SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
    }

    static QuicSslContext http3Client() {
        return QuicSslContextBuilder.forClient()
                                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                    .applicationProtocols(Http3.supportedApplicationProtocols())
                                    .build();
    }

    /// The leaf certificate of `bundle`, to compare with the one a client was offered.
    static X509Certificate certificate(CertificateBundle bundle) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                                                   .generateCertificate(new ByteArrayInputStream(bundle.certificatePem()));
    }

    /// The reference count of the native BoringSSL context behind a QUIC context (Netty 4.2.18
    /// `QuicheQuicSslContext.nativeSslContext`): 1 for the context itself, plus 1 for each connection created from it
    /// and not yet freed (`QuicheQuicSslContext.createConnection` retains, `QuicheQuicConnection.free` releases).
    static int nativeRefCnt(QuicSslContext context) {
        try {
            var field = context.getClass().getDeclaredField("nativeSslContext");

            field.setAccessible(true);

            return ((ReferenceCounted) field.get(context)).refCnt();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Netty's QUIC context no longer has the field this test reads", e);
        }
    }
}
