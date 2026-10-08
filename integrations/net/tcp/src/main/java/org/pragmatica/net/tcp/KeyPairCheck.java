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
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Refuses an identity whose private key does not belong to its certificate.
///
/// Each half of such a pair is valid on its own, so a TLS context builds without error and the listener (or the
/// cluster transport) then completes no handshake: it is up, answers nothing, and reports nothing. The check signs a
/// challenge with the private key and verifies it with the certificate's public key.
///
/// It runs inside [TlsContextFactory] and [QuicSslContextFactory], after the identity loaded, so startup, certificate
/// rotation and the cluster renewal gate all pass through it. A key the check cannot read (an encrypted key, an
/// unsupported PEM layout) is INCONCLUSIVE, not a mismatch: the context builder accepted it, so it is let through with
/// a warning rather than refusing a configuration that works.
final class KeyPairCheck {
    private static final Logger log = LoggerFactory.getLogger(KeyPairCheck.class);
    private static final byte[] CHALLENGE = "key-pair-check".getBytes(StandardCharsets.UTF_8);

    private KeyPairCheck() {}

    static Result<Unit> check(TlsConfig.Identity identity) {
        return switch (identity) {
            case TlsConfig.Identity.SelfSigned() -> Result.success(unit());
            case TlsConfig.Identity.FromFiles(var certPath, var keyPath, _) -> Result.lift(Causes::fromThrowable,
                                                                                           () -> new byte[][]{Files.readAllBytes(certPath),
                                                                                                              Files.readAllBytes(keyPath)})
                                                                                     .flatMap(pems -> verdict(pems[0], pems[1]));
            case TlsConfig.Identity.FromProvider(var certPem, var keyPem) -> verdict(certPem, keyPem);
        };
    }

    private static Result<Unit> verdict(byte[] certificatePem, byte[] privateKeyPem) {
        try {
            return signatureVerifies(certificatePem, privateKeyPem)
                   ? Result.success(unit())
                   : TlsError.keyDoesNotMatchCertificate().result();
        } catch (InvalidKeyException incompatibleKeyType) {
            return TlsError.keyDoesNotMatchCertificate().result();
        } catch (GeneralSecurityException | IOException | RuntimeException unreadable) {
            log.warn("Could not check that the private key matches the certificate (inconclusive, not refused): {}",
                     unreadable.toString());

            return Result.success(unit());
        }
    }

    private static boolean signatureVerifies(byte[] certificatePem, byte[] privateKeyPem) throws GeneralSecurityException, IOException {
        var publicKey = CertificateFactory.getInstance("X.509")
                                          .generateCertificate(new ByteArrayInputStream(certificatePem))
                                          .getPublicKey();
        var privateKey = privateKey(privateKeyPem);
        var algorithm = signatureAlgorithm(publicKey.getAlgorithm());
        var signer = Signature.getInstance(algorithm);

        signer.initSign(privateKey);
        signer.update(CHALLENGE);
        var signature = signer.sign();
        var verifier = Signature.getInstance(algorithm);

        verifier.initVerify(publicKey);
        verifier.update(CHALLENGE);

        return verifier.verify(signature);
    }

    private static String signatureAlgorithm(String keyAlgorithm) {
        return switch (keyAlgorithm) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> keyAlgorithm;
        };
    }

    /// PKCS#8, PKCS#1 and SEC1 PEM: the cluster's own provider writes SEC1 `EC PRIVATE KEY`.
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
}
