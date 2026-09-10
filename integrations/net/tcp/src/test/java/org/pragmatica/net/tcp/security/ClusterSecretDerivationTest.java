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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #980 — the two properties `aether cluster bootstrap` rests on: the node and the CLI, deriving
/// independently from the same secret, MUST reach the same key; two clusters MUST NOT.
///
/// Also pins the derivation against a hard-coded expected value. That vector is what makes this a
/// compatibility gate rather than a tautology: a reformulation of the HKDF steps that stays
/// self-consistent would pass determinism and separation while silently invalidating every already
/// bootstrapped cluster's registered key hash.
class ClusterSecretDerivationTest {
    private static final String SECRET = "test-cluster-secret";

    @Test
    void bootstrapAdminKey_sameSecret_derivesIdenticalKey() {
        var first = ClusterSecretDerivation.bootstrapAdminKey(SECRET).unwrap();
        var second = ClusterSecretDerivation.bootstrapAdminKey(SECRET).unwrap();

        assertThat(first).as("node and CLI derive independently and must agree").isEqualTo(second);
    }

    @Test
    void bootstrapAdminKey_differentSecrets_deriveDifferentKeys() {
        var first = ClusterSecretDerivation.bootstrapAdminKey(SECRET).unwrap();
        var second = ClusterSecretDerivation.bootstrapAdminKey(SECRET + "-other").unwrap();

        assertThat(first).as("one cluster's admin key must not authenticate against another")
                  .isNotEqualTo(second);
    }

    /// A one-character difference must not leave a shared prefix — HKDF's extract step is what makes
    /// the whole output change, and a truncation or a plain-hash substitution would break this.
    @Test
    void bootstrapAdminKey_neighbouringSecrets_shareNoMaterialBeyondThePrefix() {
        var first = ClusterSecretDerivation.bootstrapAdminKey("secret-a").unwrap();
        var second = ClusterSecretDerivation.bootstrapAdminKey("secret-b").unwrap();

        assertThat(material(first)).as("neighbouring secrets must not produce neighbouring keys")
                  .doesNotStartWith(material(second).substring(0, 8));
    }

    @Test
    void bootstrapAdminKey_carriesThePrefixAndFullKeyMaterial() {
        var key = ClusterSecretDerivation.bootstrapAdminKey(SECRET).unwrap();

        assertThat(key).startsWith(ClusterSecretDerivation.BOOTSTRAP_ADMIN_KEY_PREFIX);
        assertThat(Base64.getUrlDecoder().decode(material(key)))
            .as("32 bytes of derived material, matching the random key this replaced")
            .hasSize(ClusterSecretDerivation.BOOTSTRAP_ADMIN_KEY_BYTES);
    }

    /// Compatibility vector, computed OUTSIDE this codebase (Python `hmac`/`hashlib`, RFC 5869
    /// extract-then-expand, salt `aether-ca-seed`, info `aether-bootstrap-admin-key-v1`, 32 bytes,
    /// base64url unpadded). Independently computed is the point: a vector captured from this
    /// implementation's own output would agree with it for every input, including a wrong one.
    ///
    /// If this ever changes, every cluster already holding a registered hash for the old value stops
    /// authenticating — rotating the derivation is a new `info` label, never an edit here.
    @Test
    void bootstrapAdminKey_matchesThePinnedVector_soAlreadyBootstrappedClustersKeepWorking() {
        assertThat(ClusterSecretDerivation.bootstrapAdminKey("aether-local-dev-cluster-secret").unwrap())
            .isEqualTo("aeth_x57DqGbKggSk_s31QfIMCoaf-vhOf2tvHfFD3qGnvB0");
        assertThat(ClusterSecretDerivation.bootstrapAdminKey(SECRET).unwrap())
            .isEqualTo("aeth_vCooZeMvmwXYzJYOBRhh-jQ68goynjpOV7eMfs0HkTM");
    }

    /// The admin label must not collide with the CA label: same secret, same salt, different `info`
    /// ⟹ independent material. A copy-paste of the CA label into the admin derivation is exactly the
    /// mistake this catches, and it would leave every other test in this file green.
    @Test
    void bootstrapAdminKey_isIndependentOfTheCaSeed_derivedFromTheSameSecret() throws Exception {
        var caSeed = ClusterSecretDerivation.hkdfDerive(SECRET.getBytes(StandardCharsets.UTF_8),
                                                        ClusterSecretDerivation.hkdfSalt(),
                                                        "aether-ca-key-v1".getBytes(StandardCharsets.UTF_8),
                                                        32);
        var adminMaterial = Base64.getUrlDecoder()
                                  .decode(material(ClusterSecretDerivation.bootstrapAdminKey(SECRET).unwrap()));

        assertThat(adminMaterial).as("the admin key must not be the CA seed under a different encoding")
                  .isNotEqualTo(caSeed);
    }

    @Test
    void hkdfDerive_isDeterministicAndLabelSeparated() throws Exception {
        var ikm = SECRET.getBytes(StandardCharsets.UTF_8);
        var salt = ClusterSecretDerivation.hkdfSalt();
        var first = ClusterSecretDerivation.hkdfDerive(ikm, salt, "label-one".getBytes(StandardCharsets.UTF_8), 32);
        var again = ClusterSecretDerivation.hkdfDerive(ikm, salt, "label-one".getBytes(StandardCharsets.UTF_8), 32);
        var other = ClusterSecretDerivation.hkdfDerive(ikm, salt, "label-two".getBytes(StandardCharsets.UTF_8), 32);

        assertThat(first).isEqualTo(again);
        assertThat(first).isNotEqualTo(other);
    }

    private static String material(String key) {
        return key.substring(ClusterSecretDerivation.BOOTSTRAP_ADMIN_KEY_PREFIX.length());
    }
}
