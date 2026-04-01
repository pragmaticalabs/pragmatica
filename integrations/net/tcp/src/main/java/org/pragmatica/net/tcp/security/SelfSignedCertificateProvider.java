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

package org.pragmatica.net.tcp.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECPrivateKeySpec;
import org.bouncycastle.jce.spec.ECPublicKeySpec;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;

import static org.pragmatica.lang.Option.some;

/// Self-signed certificate provider using BouncyCastle.
///
/// Derives a deterministic CA keypair from a shared cluster secret via HKDF,
/// ensuring all nodes in the cluster produce the same CA certificate.
/// Node certificates use random keypairs signed by the deterministic CA.
public final class SelfSignedCertificateProvider implements CertificateProvider {
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final String EC_CURVE = "P-256";
    private static final Duration NODE_CERT_VALIDITY = Duration.ofDays(7);
    private static final byte[] HKDF_SALT = "aether-ca-seed".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CA_KEY_INFO = "aether-ca-key-v1".getBytes(StandardCharsets.UTF_8);
    private static final String GOSSIP_KEY_PREFIX = "aether-gossip-key-v";
    private static final byte[] KEY_ID_LABEL = "key-id".getBytes(StandardCharsets.UTF_8);

    private final KeyPair caKeyPair;
    private final X509Certificate caCert;
    private final byte[] caCertPem;
    private final GossipKey currentKey;
    private final GossipKey previousKey;
    private final byte[] clusterSecret;

    private SelfSignedCertificateProvider(KeyPair caKeyPair,
                                          X509Certificate caCert,
                                          byte[] caCertPem,
                                          GossipKey currentKey,
                                          GossipKey previousKey,
                                          byte[] clusterSecret) {
        this.caKeyPair = caKeyPair;
        this.caCert = caCert;
        this.caCertPem = caCertPem;
        this.currentKey = currentKey;
        this.previousKey = previousKey;
        this.clusterSecret = clusterSecret;
    }

    /// Create a self-signed certificate provider from a cluster secret.
    ///
    /// The cluster secret is used to deterministically derive the CA keypair
    /// and gossip encryption key, so all nodes with the same secret produce
    /// identical CA certificates.
    ///
    /// @param clusterSecret shared secret for deterministic key derivation
    /// @return configured provider or error
    public static Result<CertificateProvider> selfSignedCertificateProvider(byte[] clusterSecret) {
        return Result.lift(CertificateProviderError.CaGenerationFailed::new, () -> createProvider(clusterSecret));
    }

    @Override
    public Result<CertificateBundle> issueCertificate(String nodeId, String hostname) {
        return Result.lift(
            e -> new CertificateProviderError.CertificateIssueFailed(nodeId, e),
            () -> issueNodeCertificate(nodeId, hostname)
        );
    }

    @Override
    public Result<CertificateBundle> caCertificate() {
        return Result.success(CertificateBundle.certificateBundle(caCertPem, new byte[0], caCertPem, extractNotAfter(caCert)));
    }

    @Override
    public Result<GossipKey> currentGossipKey() {
        return Result.success(currentKey);
    }

    @Override
    public Option<GossipKey> previousGossipKey() {
        return some(previousKey);
    }

    /// Derive a gossip key for an arbitrary version label.
    /// Used by gossip key rotation to generate new keys from the cluster secret.
    ///
    /// @param version version label appended to the HKDF info parameter
    /// @return derived gossip key or error
    public Result<GossipKey> deriveVersionedGossipKey(String version) {
        return Result.lift(CertificateProviderError.CaGenerationFailed::new,
                           () -> deriveGossipKeyWithLabel(clusterSecret, GOSSIP_KEY_PREFIX + version));
    }

    // ===== Provider Initialization =====

    private static SelfSignedCertificateProvider createProvider(byte[] clusterSecret) throws Exception {
        ensureBouncyCastle();

        var caKeyPair = deriveKeyPair(clusterSecret);
        var caCert = generateCaCertificate(caKeyPair);
        var caCertPem = toPem(caCert);
        var today = LocalDate.now().toEpochDay();
        var currentKey = deriveGossipKeyWithLabel(clusterSecret, GOSSIP_KEY_PREFIX + today);
        var previousKey = deriveGossipKeyWithLabel(clusterSecret, GOSSIP_KEY_PREFIX + (today - 1));

        return new SelfSignedCertificateProvider(caKeyPair, caCert, caCertPem, currentKey, previousKey, clusterSecret);
    }

    // ===== Certificate Generation =====

    private CertificateBundle issueNodeCertificate(String nodeId, String hostname) throws Exception {
        var nodeKeyPair = generateRandomKeyPair();
        var now = Instant.now();
        var notAfter = now.plus(NODE_CERT_VALIDITY);

        var issuer = new X500Name("CN=Aether Cluster CA");
        var subject = new X500Name("CN=" + nodeId);
        var serial = new BigInteger(128, new SecureRandom());

        var certBuilder = new JcaX509v3CertificateBuilder(
            issuer, serial, Date.from(now), Date.from(notAfter), subject, nodeKeyPair.getPublic()
        );

        addSubjectAlternativeNames(certBuilder, hostname);

        var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setProvider("BC")
            .build(caKeyPair.getPrivate());

        var certHolder = certBuilder.build(signer);
        var nodeCert = toX509Certificate(certHolder);

        return CertificateBundle.certificateBundle(toPem(nodeCert), toPem(nodeKeyPair.getPrivate()), caCertPem, notAfter);
    }

    private static X509Certificate generateCaCertificate(KeyPair caKeyPair) throws Exception {
        var now = Instant.now();
        var notAfter = now.plus(Duration.ofDays(365));
        var subject = new X500Name("CN=Aether Cluster CA");
        var serial = BigInteger.ONE;

        var certBuilder = new JcaX509v3CertificateBuilder(
            subject, serial, Date.from(now), Date.from(notAfter), subject, caKeyPair.getPublic()
        );

        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        var signer = new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setProvider("BC")
            .build(caKeyPair.getPrivate());

        return toX509Certificate(certBuilder.build(signer));
    }

    private static void addSubjectAlternativeNames(JcaX509v3CertificateBuilder builder,
                                                    String hostname) throws Exception {
        var sanType = isIpAddress(hostname) ? GeneralName.iPAddress : GeneralName.dNSName;
        var san = new GeneralNames(new GeneralName(sanType, hostname));
        builder.addExtension(Extension.subjectAlternativeName, false, san);
    }

    // ===== HKDF Key Derivation =====

    private static KeyPair deriveKeyPair(byte[] clusterSecret) throws Exception {
        var seed = hkdfDerive(clusterSecret, HKDF_SALT, CA_KEY_INFO, 32);
        return keyPairFromSeed(seed);
    }

    private static byte[] hkdfDerive(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        // HKDF-Extract
        var prk = hmacSha256(salt, ikm);
        // HKDF-Expand (single block, length <= 32)
        var expandInput = new byte[info.length + 1];
        System.arraycopy(info, 0, expandInput, 0, info.length);
        expandInput[info.length] = 0x01;

        var okm = hmacSha256(prk, expandInput);
        var result = new byte[length];
        System.arraycopy(okm, 0, result, 0, length);
        return result;
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static KeyPair keyPairFromSeed(byte[] seed) throws Exception {
        var ecSpec = ECNamedCurveTable.getParameterSpec(EC_CURVE);
        var privateKeyScalar = new BigInteger(1, seed).mod(ecSpec.getN().subtract(BigInteger.ONE)).add(BigInteger.ONE);
        var publicKeyPoint = ecSpec.getG().multiply(privateKeyScalar).normalize();

        var keyFactory = KeyFactory.getInstance("EC", "BC");

        var privateKey = keyFactory.generatePrivate(new ECPrivateKeySpec(privateKeyScalar, ecSpec));
        var publicKey = keyFactory.generatePublic(new ECPublicKeySpec(publicKeyPoint, ecSpec));

        return new KeyPair(publicKey, privateKey);
    }

    // ===== Gossip Key Derivation =====

    private static GossipKey deriveGossipKeyWithLabel(byte[] clusterSecret, String label) throws Exception {
        var info = label.getBytes(StandardCharsets.UTF_8);
        var key = hkdfDerive(clusterSecret, HKDF_SALT, info, 32);
        var keyIdBytes = hmacSha256(key, KEY_ID_LABEL);
        var keyId = ((keyIdBytes[0] & 0xFF) << 24)
                    | ((keyIdBytes[1] & 0xFF) << 16)
                    | ((keyIdBytes[2] & 0xFF) << 8)
                    | (keyIdBytes[3] & 0xFF);

        return GossipKey.gossipKey(key, keyId, Instant.now());
    }

    // ===== Utility Methods =====

    private static KeyPair generateRandomKeyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(ECNamedCurveTable.getParameterSpec(EC_CURVE), new SecureRandom());
        return generator.generateKeyPair();
    }

    private static X509Certificate toX509Certificate(X509CertificateHolder holder) throws Exception {
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static byte[] toPem(Object obj) throws Exception {
        var writer = new StringWriter();

        try (var pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(obj);
        }

        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Instant extractNotAfter(X509Certificate cert) {
        return cert.getNotAfter().toInstant();
    }

    private static boolean isIpAddress(String hostname) {
        return hostname.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || hostname.contains(":");
    }

    private static void ensureBouncyCastle() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
}
