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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ChannelBindingDigestTest {

    @Test
    public void rfc5929UpgradesMd5ToSha256() {
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm("MD5withRSA"));
    }

    @Test
    public void rfc5929UpgradesSha1ToSha256() {
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm("SHA1withRSA"));
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm("SHAwithRSA"));
    }

    @Test
    public void sha256RemainsSha256() {
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm("SHA256withRSA"));
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm("SHA-256withRSA"));
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm("SHA256withECDSA"));
    }

    @Test
    public void sha384MapsToSha384() {
        assertEquals("SHA-384", ChannelBindingDigest.mapToDigestAlgorithm("SHA384withRSA"));
        assertEquals("SHA-384", ChannelBindingDigest.mapToDigestAlgorithm("SHA384withECDSA"));
    }

    @Test
    public void sha512MapsToSha512() {
        assertEquals("SHA-512", ChannelBindingDigest.mapToDigestAlgorithm("SHA512withRSA"));
    }

    @Test
    public void unknownAlgorithmDefaultsToSha256() {
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm("BLAKE3withRSA"));
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm(null));
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm(""));
    }

    @Test
    public void sha224UpgradesToSha256() {
        // RFC 5929 calls out only MD5/SHA-1; SHA-224 is rare and our fallback maps to SHA-256.
        assertEquals("SHA-256", ChannelBindingDigest.mapToDigestAlgorithm("SHA224withRSA"));
    }
}
