package org.pragmatica.aether.http.security;

import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;

import static org.pragmatica.lang.Result.success;

/// Verifies JWT signatures using JDK crypto APIs.
///
/// Supports RS256 (RSA with SHA-256) and ES256 (ECDSA with SHA-256).
@SuppressWarnings("JBCT-RET-03")
final class JwtSignatureVerifier {
    private JwtSignatureVerifier() {}

    /// Verify the JWT signature against the provided public key.
    static Result<JwtTokenParser.ParsedJwt> verify(JwtTokenParser.ParsedJwt jwt, PublicKey key) {
        return resolveAlgorithm(jwt.header().alg())
            .flatMap(jdkAlg -> performVerification(jwt, key, jdkAlg));
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
        return Result.lift(JwtSignatureVerifier::verificationFailed,
                           () -> verifySignatureRaw(jwt, key, jdkAlg));
    }

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

    /// Convert raw R||S EC signature to DER format expected by JDK.
    /// JWTs use raw R||S concatenation, but Java's Signature API expects DER-encoded.
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
        // Add leading zero if high bit is set (to prevent negative interpretation)
        var rLen = r.length + (r[0] < 0 ? 1 : 0);
        var sLen = s.length + (s[0] < 0 ? 1 : 0);
        var totalLen = 2 + rLen + 2 + sLen;
        var der = new byte[2 + totalLen];
        var pos = 0;
        der[pos++] = 0x30; // SEQUENCE tag
        der[pos++] = (byte) totalLen;
        pos = writeInteger(der, pos, r, rLen);
        writeInteger(der, pos, s, sLen);
        return der;
    }

    private static int writeInteger(byte[] der, int pos, byte[] value, int fieldLen) {
        der[pos++] = 0x02; // INTEGER tag
        der[pos++] = (byte) fieldLen;
        if (fieldLen > value.length) {
            der[pos++] = 0x00; // padding byte
        }
        System.arraycopy(value, 0, der, pos, value.length);
        return pos + value.length;
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
