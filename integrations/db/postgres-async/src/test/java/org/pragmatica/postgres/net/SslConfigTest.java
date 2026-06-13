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

package org.pragmatica.postgres.net;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SslConfigTest {

    @Test
    public void disabledFactoryProducesDisableMode() {
        var config = SslConfig.disabled();
        assertEquals(SslConfig.SslMode.DISABLE, config.mode());
        assertEquals(SslConfig.ChannelBinding.DISABLE, config.channelBinding());
        assertTrue(config.rootCertPem().isEmpty());
        assertTrue(config.clientCertificate().isEmpty());
        assertFalse(config.allowAllCertificates());
    }

    @Test
    public void preferFactoryProducesPreferMode() {
        var config = SslConfig.prefer();
        assertEquals(SslConfig.SslMode.PREFER, config.mode());
        assertEquals(SslConfig.ChannelBinding.PREFER, config.channelBinding());
    }

    @Test
    public void requireFactoryProducesRequireMode() {
        assertEquals(SslConfig.SslMode.REQUIRE, SslConfig.require().mode());
    }

    @Test
    public void verifyCaRequiresRootCert() {
        var rootCert = Path.of("/tmp/root.pem");
        var config = SslConfig.verifyCa(rootCert);
        assertEquals(SslConfig.SslMode.VERIFY_CA, config.mode());
        assertEquals(rootCert, config.rootCertPem().or(() -> { throw new AssertionError(); }));
    }

    @Test
    public void verifyFullRequiresRootCert() {
        var rootCert = Path.of("/tmp/root.pem");
        var config = SslConfig.verifyFull(rootCert);
        assertEquals(SslConfig.SslMode.VERIFY_FULL, config.mode());
        assertEquals(rootCert, config.rootCertPem().or(() -> { throw new AssertionError(); }));
    }

    @Test
    public void verifyCaWithoutRootCertAndCustomizerThrows() {
        assertThrows(IllegalArgumentException.class, () -> new SslConfig(
            SslConfig.SslMode.VERIFY_CA,
            org.pragmatica.lang.Option.none(),
            org.pragmatica.lang.Option.none(),
            SslConfig.ChannelBinding.PREFER,
            org.pragmatica.lang.Option.none(),
            false));
    }

    @Test
    public void allowAllCertificatesValidOnlyInRequireMode() {
        // OK in REQUIRE
        var ok = SslConfig.require().withAllowAllCertificates();
        assertTrue(ok.allowAllCertificates());

        // Rejected in VERIFY_CA
        assertThrows(IllegalArgumentException.class,
                     () -> SslConfig.verifyCa(Path.of("/tmp/root.pem")).withAllowAllCertificates());

        // Rejected in VERIFY_FULL
        assertThrows(IllegalArgumentException.class,
                     () -> SslConfig.verifyFull(Path.of("/tmp/root.pem")).withAllowAllCertificates());
    }

    @Test
    public void withChannelBindingOverridesDefault() {
        var config = SslConfig.require().withChannelBinding(SslConfig.ChannelBinding.REQUIRE);
        assertEquals(SslConfig.ChannelBinding.REQUIRE, config.channelBinding());
    }

    @Test
    public void withClientCertificateAddsMtlsCredentials() {
        var cert = Path.of("/tmp/client.crt");
        var key = Path.of("/tmp/client.key");
        var config = SslConfig.verifyFull(Path.of("/tmp/root.pem"))
                              .withClientCertificate(cert, key, org.pragmatica.lang.Option.some("secret"));
        var mtls = config.clientCertificate().or(() -> { throw new AssertionError(); });
        assertEquals(cert, mtls.cert());
        assertEquals(key, mtls.key());
        assertEquals("secret", mtls.password().or(() -> { throw new AssertionError(); }));
    }
}
