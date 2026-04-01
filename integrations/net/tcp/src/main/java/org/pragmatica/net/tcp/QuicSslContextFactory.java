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

package org.pragmatica.net.tcp;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.nio.file.Path;
import java.util.Base64;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Factory for creating QUIC-specific SSL contexts from TLS configuration.
///
/// QUIC requires TLS 1.3 and uses a separate SSL context type ([QuicSslContext])
/// distinct from the regular Netty [io.netty.handler.ssl.SslContext].
///
/// @see TlsConfig
/// @see TlsContextFactory
public final class QuicSslContextFactory {
    private static final Logger log = LoggerFactory.getLogger(QuicSslContextFactory.class);

    private QuicSslContextFactory() {}

    /// Create a QUIC server SSL context from TLS configuration.
    ///
    /// @param config TLS configuration (must be Server or Mutual mode)
    /// @return QUIC SSL context or error
    public static Result<QuicSslContext> createServer(TlsConfig config) {
        return switch (config) {
            case TlsConfig.Server(var identity, var clientAuth) ->
                buildServerContext(identity, clientAuth);
            case TlsConfig.Mutual(var identity, var trust) ->
                buildMutualServerContext(identity, trust);
            case TlsConfig.Client _ ->
                TlsError.wrongMode("Cannot create QUIC server context from Client config")
                        .result();
        };
    }

    /// Create a self-signed QUIC server SSL context for development.
    ///
    /// @return QUIC SSL context or error
    public static Result<QuicSslContext> createSelfSignedServer() {
        return buildServerContext(new TlsConfig.Identity.SelfSigned(), Option.empty());
    }

    private static Result<QuicSslContext> buildServerContext(TlsConfig.Identity identity,
                                                             Option<TlsConfig.Trust> clientAuth) {
        return loadIdentityAndBuild(identity, clientAuth);
    }

    private static Result<QuicSslContext> buildMutualServerContext(TlsConfig.Identity identity,
                                                                   TlsConfig.Trust trust) {
        return loadIdentityAndBuild(identity, Option.some(trust));
    }

    private static Result<QuicSslContext> loadIdentityAndBuild(TlsConfig.Identity identity,
                                                               Option<TlsConfig.Trust> trust) {
        return loadKeyMaterial(identity)
            .flatMap(keyMaterial -> buildContext(keyMaterial, trust));
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static Result<QuicSslContext> buildContext(KeyMaterial keyMaterial,
                                                       Option<TlsConfig.Trust> trust) {
        try {
            var builder = configureIdentity(keyMaterial);
            trust.onPresent(t -> configureTrust(builder, t));
            return Result.success(builder.build());
        } catch (Exception e) {
            return new TlsError.ContextBuildFailed(e).result();
        }
    }

    @SuppressWarnings("JBCT-NULL-01") // Netty API requires nullable password parameter
    private static QuicSslContextBuilder configureIdentity(KeyMaterial keyMaterial) {
        return switch (keyMaterial) {
            case KeyMaterial.FromFile(var certFile, var keyFile, var password) ->
                QuicSslContextBuilder.forServer(keyFile, password.or((String) null), certFile);
            case KeyMaterial.FromCerts(var key, var password, var chain) ->
                QuicSslContextBuilder.forServer(key, password.or((String) null), chain);
        };
    }

    @SuppressWarnings("JBCT-PAT-01") // Switch over sealed trust variants
    private static void configureTrust(QuicSslContextBuilder builder, TlsConfig.Trust trust) {
        switch (trust) {
            case TlsConfig.Trust.SystemDefault() -> {}
            case TlsConfig.Trust.FromCaFile(var caPath) -> builder.trustManager(caPath.toFile());
            case TlsConfig.Trust.InsecureTrustAll() -> {
                log.warn("Using InsecureTrustAll for QUIC - FOR DEVELOPMENT ONLY!");
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
            }
            case TlsConfig.Trust.FromCaBytes(var caPem) -> configureTrustFromBytes(builder, caPem);
        }
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static void configureTrustFromBytes(QuicSslContextBuilder builder, byte[] caPem) {
        try {
            var factory = CertificateFactory.getInstance("X.509");
            var cert = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(caPem));
            builder.trustManager(cert);
        } catch (Exception e) {
            log.error("Failed to parse CA certificate from PEM bytes: {}", e.getMessage());
        }
    }

    /// Create a QUIC server SSL context from a [CertificateBundle].
    /// Builds mutual TLS with the bundle's cert, key, and CA.
    ///
    /// @param bundle certificate bundle from a [CertificateProvider]
    /// @return QUIC SSL context or error
    public static Result<QuicSslContext> createServerFromBundle(org.pragmatica.net.tcp.security.CertificateBundle bundle) {
        var identity = new TlsConfig.Identity.FromProvider(bundle.certificatePem(), bundle.privateKeyPem());
        var trust = new TlsConfig.Trust.FromCaBytes(bundle.caCertificatePem());
        return loadIdentityAndBuild(identity, Option.some(trust));
    }

    /// Create a QUIC client SSL context from a [CertificateBundle].
    /// Trusts the CA from the bundle.
    ///
    /// @param bundle certificate bundle from a [CertificateProvider]
    /// @return QUIC SSL context or error
    public static Result<QuicSslContext> createClientFromBundle(org.pragmatica.net.tcp.security.CertificateBundle bundle) {
        return buildClientContext(new TlsConfig.Trust.FromCaBytes(bundle.caCertificatePem()));
    }

    // ===== Client Context =====

    /// Create a QUIC client SSL context from TLS configuration.
    ///
    /// @param config TLS configuration (must be Client or Mutual mode)
    /// @return QUIC SSL context or error
    @SuppressWarnings("JBCT-PAT-01") // Switch over sealed TLS config variants
    public static Result<QuicSslContext> createClient(TlsConfig config) {
        return switch (config) {
            case TlsConfig.Client(var trust, _) -> buildClientContext(trust);
            case TlsConfig.Mutual(_, var trust) -> buildClientContext(trust);
            case TlsConfig.Server _ ->
                TlsError.wrongMode("Cannot create QUIC client context from Server config")
                        .result();
        };
    }

    /// Create an insecure QUIC client SSL context for development (trusts all certificates).
    ///
    /// @return QUIC SSL context or error
    @SuppressWarnings("JBCT-UTIL-01")
    public static Result<QuicSslContext> createInsecureClient() {
        try {
            log.warn("Creating insecure QUIC client context - FOR DEVELOPMENT ONLY!");
            var context = QuicSslContextBuilder.forClient()
                                               .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                               .build();
            return Result.success(context);
        } catch (Exception e) {
            return new TlsError.ContextBuildFailed(e).result();
        }
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static Result<QuicSslContext> buildClientContext(TlsConfig.Trust trust) {
        try {
            var builder = QuicSslContextBuilder.forClient();
            configureTrust(builder, trust);
            return Result.success(builder.build());
        } catch (Exception e) {
            return new TlsError.ContextBuildFailed(e).result();
        }
    }

    // ===== Key Material =====
    private sealed interface KeyMaterial {
        record FromFile(java.io.File certFile, java.io.File keyFile, Option<String> password) implements KeyMaterial {}
        record FromCerts(java.security.PrivateKey key,
                         Option<String> password,
                         X509Certificate[] chain) implements KeyMaterial {}
    }

    private static Result<KeyMaterial> loadKeyMaterial(TlsConfig.Identity identity) {
        return switch (identity) {
            case TlsConfig.Identity.SelfSigned() -> generateSelfSigned();
            case TlsConfig.Identity.FromFiles(var certPath, var keyPath, var password) ->
                loadFromFiles(certPath, keyPath, password);
            case TlsConfig.Identity.FromProvider(var certPem, var keyPem) ->
                loadFromPemBytes(certPem, keyPem);
        };
    }

    @SuppressWarnings({"deprecation", "JBCT-UTIL-01"}) // SelfSignedCertificate is for dev/testing only
    private static Result<KeyMaterial> generateSelfSigned() {
        try {
            var ssc = new SelfSignedCertificate("localhost", "RSA", 2048);
            return Result.success(new KeyMaterial.FromFile(ssc.certificate(), ssc.privateKey(), Option.empty()));
        } catch (Exception e) {
            return new TlsError.SelfSignedGenerationFailed(e).result();
        }
    }

    private static Result<KeyMaterial> loadFromFiles(Path certPath,
                                                     Path keyPath,
                                                     Option<String> password) {
        var certFile = certPath.toFile();
        var keyFile = keyPath.toFile();
        if (!certFile.exists() || !certFile.canRead()) {
            return new TlsError.CertificateLoadFailed(certPath,
                new java.io.FileNotFoundException("Certificate file not found or not readable: " + certPath)).result();
        }
        if (!keyFile.exists() || !keyFile.canRead()) {
            return new TlsError.PrivateKeyLoadFailed(keyPath,
                new java.io.FileNotFoundException("Private key file not found or not readable: " + keyPath)).result();
        }
        return Result.success(new KeyMaterial.FromFile(certFile, keyFile, password));
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static Result<KeyMaterial> loadFromPemBytes(byte[] certPem, byte[] keyPem) {
        try {
            var certFactory = CertificateFactory.getInstance("X.509");
            var cert = (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(certPem));
            var privateKey = loadPrivateKeyFromPem(keyPem);
            return Result.success(new KeyMaterial.FromCerts(privateKey, Option.empty(), new X509Certificate[]{cert}));
        } catch (Exception e) {
            return new TlsError.ContextBuildFailed(e).result();
        }
    }

    /// Read PEM private key using BouncyCastle PEMParser to preserve named EC curve encoding.
    @SuppressWarnings("JBCT-UTIL-01")
    private static PrivateKey loadPrivateKeyFromPem(byte[] keyPem) throws Exception {
        try (var reader = new StringReader(new String(keyPem, StandardCharsets.UTF_8));
             var parser = new PEMParser(reader)) {
            var obj = parser.readObject();
            var converter = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof PEMKeyPair pemKeyPair) {
                return converter.getKeyPair(pemKeyPair).getPrivate();
            }
            if (obj instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new GeneralSecurityException("Unsupported PEM object: " + obj.getClass().getName());
        }
    }
}
