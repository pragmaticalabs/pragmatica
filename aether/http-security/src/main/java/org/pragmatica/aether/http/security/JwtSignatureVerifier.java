// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.aether.config.JwtSecret;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings("JBCT-RET-03")
final class JwtSignatureVerifier {
    private JwtSignatureVerifier() {}

    private static final String HMAC_SHA256 = "HmacSHA256";

    private static final SecurityError HS_ALGORITHM_REFUSED = new SecurityError.InvalidCredentials("Token algorithm is not HS256, the only one this server's JWT mode accepts");

    private static final SecurityError HS_SECRET_MISSING = new SecurityError.KeyNotFound("No HS256 shared secret is configured");

    static Result<JwtTokenParser.ParsedJwt> verify(JwtTokenParser.ParsedJwt jwt, PublicKey key) {
        return asymmetricAlgorithm(jwt).flatMap(jdkAlg -> performVerification(jwt, key, jdkAlg));
    }

    /// JWKS mode: the JDK algorithm for a RS*/ES* token, decided BEFORE any key is looked up. HS*, `none` and any
    /// other algorithm are refused here. A header without `alg` keeps its historical reading as RS256 (#2880: JWKS
    /// mode is unchanged); HS256 mode refuses it ([#verifyHmac]).
    static Result<String> asymmetricAlgorithm(JwtTokenParser.ParsedJwt jwt) {
        return resolveAlgorithm(jwt.header().alg().isEmpty()
                                ? "RS256"
                                : jwt.header().alg());
    }

    /// HS256 mode (#2880): the token must itself name HS256 (a missing or different `alg` is refused before the
    /// secret is touched), and the MAC over the signed content is compared in constant time. No JWKS is involved.
    static Result<JwtTokenParser.ParsedJwt> verifyHmac(JwtTokenParser.ParsedJwt jwt, Option<JwtSecret> secret) {
        return requireHs256(jwt).flatMap(_ -> secret.toResult(HS_SECRET_MISSING))
                           .flatMap(key -> checkMac(jwt, key));
    }

    private static Result<JwtTokenParser.ParsedJwt> requireHs256(JwtTokenParser.ParsedJwt jwt) {
        return "HS256".equals(jwt.header().alg())
               ? success(jwt)
               : HS_ALGORITHM_REFUSED.result();
    }

    private static Result<JwtTokenParser.ParsedJwt> checkMac(JwtTokenParser.ParsedJwt jwt, JwtSecret secret) {
        return Result.lift(JwtSignatureVerifier::verificationFailed, () -> macOf(jwt, secret)).flatMap(mac -> macMatches(jwt,
                                                                                                                         mac));
    }

    private static Result<JwtTokenParser.ParsedJwt> macMatches(JwtTokenParser.ParsedJwt jwt, byte[] mac) {
        return MessageDigest.isEqual(mac, jwt.signature())
               ? success(jwt)
               : new SecurityError.SignatureInvalid("Signature verification failed").result();
    }

    @SuppressWarnings({"JBCT-EX-01"})
    private static byte[] macOf(JwtTokenParser.ParsedJwt jwt, JwtSecret secret) throws Exception {
        var mac = Mac.getInstance(HMAC_SHA256);

        mac.init(new SecretKeySpec(secret.value(), HMAC_SHA256));

        return mac.doFinal(jwt.signedContent().getBytes(StandardCharsets.US_ASCII));
    }

    private static Result<String> resolveAlgorithm(String jwtAlg) {
        return switch (jwtAlg) {
            case "RS256" -> success("SHA256withRSA");
            case "RS384" -> success("SHA384withRSA");
            case "RS512" -> success("SHA512withRSA");
            case "ES256" -> success("SHA256withECDSA");
            case "ES384" -> success("SHA384withECDSA");
            case "ES512" -> success("SHA512withECDSA");
            default -> new SecurityError.InvalidCredentials("Unsupported algorithm: " + jwtAlg).result();
        };
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<JwtTokenParser.ParsedJwt> performVerification(JwtTokenParser.ParsedJwt jwt,
                                                                        PublicKey key,
                                                                        String jdkAlg) {
        return Result.lift(JwtSignatureVerifier::verificationFailed, () -> verifySignatureRaw(jwt, key, jdkAlg));
    }

    @SuppressWarnings({"JBCT-EX-01"})
    private static JwtTokenParser.ParsedJwt verifySignatureRaw(JwtTokenParser.ParsedJwt jwt,
                                                               PublicKey key,
                                                               String jdkAlg) throws Exception {
        var sig = Signature.getInstance(jdkAlg);

        sig.initVerify(key);
        sig.update(jwt.signedContent().getBytes(StandardCharsets.US_ASCII));
        var signatureBytes = maybeConvertEcSignature(jwt.signature(), jdkAlg);

        if (!sig.verify(signatureBytes)) {
            throw new SecurityException("Signature verification failed");
        }

        return jwt;
    }

    private static SecurityError verificationFailed(Throwable t) {
        return new SecurityError.SignatureInvalid("Signature verification failed: " + t.getMessage());
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static byte[] maybeConvertEcSignature(byte[] signature, String jdkAlg) {
        if (!jdkAlg.contains("ECDSA")) {
            return signature;
        }

        return convertRawToDer(signature);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static byte[] convertRawToDer(byte[] raw) {
        var half = raw.length / 2;
        var r = trimLeadingZeros(raw, 0, half);
        var s = trimLeadingZeros(raw, half, raw.length);
        var rLen = r.length + (r[0] < 0
                               ? 1
                               : 0);
        var sLen = s.length + (s[0] < 0
                               ? 1
                               : 0);
        var totalLen = 2 + rLen + 2 + sLen;
        var der = new byte[2 + totalLen];
        var pos = 0;

        der[pos++] = 0x30;
        der[pos++] = (byte) totalLen;
        pos = writeInteger(der, pos, r, rLen);
        writeInteger(der, pos, s, sLen);

        return der;
    }

    private static int writeInteger(byte[] der, int pos, byte[] value, int fieldLen) {
        var offset = pos;

        der[offset++] = 0x02;
        der[offset++] = (byte) fieldLen;
        if (fieldLen > value.length) {
            der[offset++] = 0x00;
        }

        System.arraycopy(value, 0, der, offset, value.length);

        return offset + value.length;
    }

    private static byte[] trimLeadingZeros(byte[] data, int start, int end) {
        var idx = start;

        while (idx < end - 1 && data[idx] == 0) {
            idx++;
        }

        var result = new byte[end - idx];

        System.arraycopy(data, idx, result, 0, result.length);

        return result;
    }
}
