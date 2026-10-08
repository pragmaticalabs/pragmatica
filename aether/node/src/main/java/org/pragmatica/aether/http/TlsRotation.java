// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.ClientAuthPolicy;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.TlsContextFactory;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Certificate rotation for one HTTP listener: the new TLS material is built BEFORE the running listener is touched.
/// A bundle that does not build is refused with a typed cause, the listener keeps serving its current certificate,
/// and the refusal is an operator event raised once on the transition (not per repeated attempt) with a recovery
/// event when a later rotation applies. Rotation never falls back to plain HTTP. A bundle is refused when any context it
/// needs does not build or its key does not match its certificate; nothing else about a bundle is checked, so a
/// bundle that passes can still be rejected by peers (untrusted CA, wrong name).
///
/// Not covered: a bind failure AFTER the old listener is stopped (the port taken in the gap) still leaves no listener.
public final class TlsRotation {
    private static final Logger log = LoggerFactory.getLogger(TlsRotation.class);
    private static final byte[] KEY_CHECK_CHALLENGE = "tls-rotation-key-check".getBytes(StandardCharsets.UTF_8);

    private final String serverName;

    private final AtomicReference<OperatorWarningSink> sink = new AtomicReference<>(OperatorWarningSink.logOnly());

    private final AtomicBoolean refused = new AtomicBoolean();

    private TlsRotation(String serverName) {
        this.serverName = serverName;
    }

    public static TlsRotation tlsRotation(String serverName) {
        return new TlsRotation(serverName);
    }

    @Contract
    public void useSink(OperatorWarningSink operatorWarningSink) {
        sink.set(operatorWarningSink);
    }

    /// Builds every context the rotation will need and checks that the private key belongs to the certificate. Failure
    /// refuses the rotation before anything is stopped. A certificate/key pair whose halves are each valid but do not
    /// match builds a context without error, and would complete no handshake; the explicit check catches it.
    public Result<Unit> validate(CertificateBundle bundle, boolean includesH1, boolean includesH3) {
        var h1 = includesH1
                 ? TlsContextFactory.createServer(serverConfig(bundle)).mapToUnit()
                 : Result.<Unit> success(unit());

        return h1.flatMap(_ -> includesH3
                               ? QuicSslContextFactory.createServerFromBundle(bundle, ClientAuthPolicy.NOT_REQUESTED).mapToUnit()
                               : Result.<Unit> success(unit()))
                 .flatMap(_ -> keyMatchesCertificate(bundle));
    }

    private static Result<Unit> keyMatchesCertificate(CertificateBundle bundle) {
        return Result.lift(Causes::fromThrowable, () -> signatureVerifies(bundle)).flatMap(matches -> matches
                                                                                                      ? Result.<Unit> success(unit())
                                                                                                      : Causes.cause("the private key does not match the certificate's public key").<Unit> result());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static boolean signatureVerifies(CertificateBundle bundle) throws GeneralSecurityException, IOException {
        var certificate = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(bundle.certificatePem()));
        var publicKey = certificate.getPublicKey();
        var privateKey = privateKey(bundle.privateKeyPem());
        var algorithm = signatureAlgorithm(publicKey.getAlgorithm());
        var signer = Signature.getInstance(algorithm);

        signer.initSign(privateKey);
        signer.update(KEY_CHECK_CHALLENGE);
        var signature = signer.sign();
        var verifier = Signature.getInstance(algorithm);

        verifier.initVerify(publicKey);
        verifier.update(KEY_CHECK_CHALLENGE);

        return verifier.verify(signature);
    }

    private static String signatureAlgorithm(String keyAlgorithm) {
        return switch (keyAlgorithm) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> keyAlgorithm;
        };
    }

    /// Reads PKCS#8, PKCS#1 and SEC1 PEM keys: the cluster's own provider writes SEC1 `EC PRIVATE KEY`.
    @SuppressWarnings("JBCT-EX-01")
    private static PrivateKey privateKey(byte[] pem) throws IOException {
        var converter = new JcaPEMKeyConverter();

        try (var parser = new PEMParser(new StringReader(new String(pem, StandardCharsets.US_ASCII)))) {
            return switch (parser.readObject()) {
                case PEMKeyPair pair -> converter.getKeyPair(pair).getPrivate();
                case PrivateKeyInfo info -> converter.getPrivateKey(info);
                case null, default -> throw new IOException("no unencrypted private key found in the PEM");
            };
        }
    }

    /// Same identity the restart builds: server authentication only (#967).
    public static TlsConfig serverConfig(CertificateBundle bundle) {
        return new TlsConfig.Server(new TlsConfig.Identity.FromProvider(bundle.certificatePem(), bundle.privateKeyPem()),
                                    Option.<TlsConfig.Trust> none());
    }

    public <T> Promise<T> refuse(Cause cause) {
        var typed = new HttpServerError.TlsRotationRefused(serverName, cause);

        if (refused.compareAndSet(false, true)) {
            OperatorWarnings.raise(log,
                                   sink.get(),
                                   OperatorWarningCode.HTTP_TLS_ROTATION_REFUSED,
                                   serverName,
                                   "{}",
                                   typed.message());
        } else {
            log.error("{}", typed.message());
        }

        return typed.promise();
    }

    @Contract
    public void applied() {
        if (refused.compareAndSet(true, false)) {
            OperatorWarnings.raise(log,
                                   sink.get(),
                                   OperatorWarningCode.HTTP_TLS_ROTATION_RESTORED,
                                   serverName,
                                   "TLS certificate rotation of HTTP server '{}' applied again; the listener serves the rotated certificate.",
                                   serverName);
        }
    }
}
