/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pragmatica.postgres.sasl;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;


/// RFC 5929 §4.1 `tls-server-end-point` channel binding: digest of the server
/// certificate using a hash derived from the cert's `signatureAlgorithm`.
///
/// Mapping (per RFC 5929):
/// - MD5  → SHA-256
/// - SHA-1 → SHA-256
/// - other → use the same hash as the cert's signature algorithm
public final class ChannelBindingDigest {
    private ChannelBindingDigest() {}

    public static byte[] tlsServerEndPoint(X509Certificate serverCert) {
        try {
            var digestAlgorithm = mapToDigestAlgorithm(serverCert.getSigAlgName());
            var digest = MessageDigest.getInstance(digestAlgorithm);

            return digest.digest(serverCert.getEncoded());
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("Failed to compute tls-server-end-point channel binding", e);
        }
    }

    /// Extract the digest portion of a JCA signature algorithm name (e.g. `SHA256withRSA` → `SHA-256`)
    /// and apply the RFC 5929 weak-hash upgrade rule.
    static String mapToDigestAlgorithm(String sigAlgName) {
        if (sigAlgName == null) {
            return "SHA-256";
        }

        var upper = sigAlgName.toUpperCase();
        // JCA names look like "SHA256withRSA", "SHA384withECDSA", "MD5withRSA", etc.
        // Some forms use hyphens: "SHA-256/withRSA". Normalize.
        var withIndex = upper.indexOf("WITH");
        var hashPart = withIndex > 0
                       ? upper.substring(0, withIndex)
                       : upper;

        hashPart = hashPart.replace("-", "").trim();

        return switch (hashPart) {
            case "MD5", "SHA1", "SHA" -> "SHA-256";
            case "SHA224", "SHA256" -> "SHA-256";
            case "SHA384" -> "SHA-384";
            case "SHA512" -> "SHA-512";
            default -> "SHA-256";
        };
    }
}
