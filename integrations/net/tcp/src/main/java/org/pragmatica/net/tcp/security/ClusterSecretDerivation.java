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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The ONE HKDF-SHA256 implementation used to derive per-purpose material from the cluster secret.
///
/// It was previously private to [`SelfSignedCertificateProvider`], which derives two things from the
/// same secret under two `info` labels: the deterministic CA keypair (`aether-ca-key-v1`) and the
/// daily gossip keys (`aether-gossip-key-v<epoch>`). It lives here now because a THIRD consumer needs
/// the identical function — see [#bootstrapAdminKey] — and two copies of a key-derivation function
/// drift silently: a divergence produces a key that is simply wrong, with no compile error and no
/// runtime signal beyond a 401.
///
/// Everything is keyed by the `info` label; the salt and the extract/expand steps are shared. A new
/// purpose is a new label, never a new implementation.
///
/// The JCA primitives below carry checked exceptions (`Mac.getInstance` / `Mac.init`); they are lifted
/// exactly once, at [#bootstrapAdminKey], which is the only JBCT-facing entry point. The throwing
/// members exist because [SelfSignedCertificateProvider] calls them from inside its own
/// already-throwing derivation path, and converting there would be churn in an unrelated code path.
@SuppressWarnings("JBCT-EX-01")
public sealed interface ClusterSecretDerivation {
    /// HKDF-Extract salt, shared by every label. Historic value — changing it invalidates every
    /// derived artefact cluster-wide (CA identity included), so it is versioned through the `info`
    /// labels instead.
    String HKDF_SALT = "aether-ca-seed";
    /// `info` label for the cluster-formation bootstrap admin API key (#980). Distinct from
    /// `aether-ca-key-v1` and the gossip labels, so an attacker holding one derived artefact learns
    /// nothing about the others beyond what the shared secret already gives them. The `-v1` suffix
    /// follows the CA label's convention: rotating the derivation is a new label, not a new salt.
    String BOOTSTRAP_ADMIN_KEY_INFO = "aether-bootstrap-admin-key-v1";
    /// Presentation prefix of the bootstrap admin key, matching the random key it replaces so the
    /// operator-visible shape is unchanged.
    String BOOTSTRAP_ADMIN_KEY_PREFIX = "aeth_";

    int BOOTSTRAP_ADMIN_KEY_BYTES = 32;

    /// Derive the cluster-formation bootstrap admin API key (#980) from the cluster secret.
    ///
    /// Both sides of `aether cluster bootstrap` hold the secret — the node because it cannot boot
    /// without one, the CLI because it minted it in phase 1 — so both reach the same key without any
    /// of it crossing the wire. The node registers this key's SHA-256 hash in the KV store through
    /// consensus at first leadership (`BootstrapAdminKeyLeg`), which keeps it enumerable via
    /// `GET /api/v1/cluster/keys`, revocable and auditable; the CLI derives the same value locally
    /// and authenticates the quorum poll with it.
    ///
    /// @param clusterSecret the shared cluster secret, UTF-8 encoded before extraction
    /// @return the `aeth_`-prefixed key, identical for identical secrets
    static Result<String> bootstrapAdminKey(String clusterSecret) {
        return Result.lift(Causes::fromThrowable,
                           () -> deriveBootstrapAdminKey(clusterSecret.getBytes(StandardCharsets.UTF_8)));
    }

    /// HKDF-SHA256 (RFC 5869), single expand block — `length` must not exceed 32.
    static byte[] hkdfDerive(byte[] ikm, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
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

    static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
        var mac = Mac.getInstance("HmacSHA256");

        mac.init(new SecretKeySpec(key, "HmacSHA256"));

        return mac.doFinal(data);
    }

    static byte[] hkdfSalt() {
        return HKDF_SALT.getBytes(StandardCharsets.UTF_8);
    }

    private static String deriveBootstrapAdminKey(byte[] clusterSecret) throws GeneralSecurityException {
        var material = hkdfDerive(clusterSecret,
                                  hkdfSalt(),
                                  BOOTSTRAP_ADMIN_KEY_INFO.getBytes(StandardCharsets.UTF_8),
                                  BOOTSTRAP_ADMIN_KEY_BYTES);

        return BOOTSTRAP_ADMIN_KEY_PREFIX + Base64.getUrlEncoder()
                                                  .withoutPadding()
                                                  .encodeToString(material);
    }

    record unused() implements ClusterSecretDerivation {}
}
