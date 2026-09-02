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

import java.nio.file.Path;
import java.util.function.Consumer;

import org.pragmatica.lang.Option;

import io.netty.handler.ssl.SslContextBuilder;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Transport-layer security configuration for a Postgres connection.
///
/// The mode taxonomy mirrors libpq's `sslmode` parameter exactly so PostgreSQL
/// users see familiar semantics:
///
/// | Mode | Encrypts | CA validates | Hostname validates |
/// |------|----------|--------------|--------------------|
/// | DISABLE     | -- | -- | -- |
/// | ALLOW       | maybe | -- | -- |
/// | PREFER      | maybe | -- | -- |
/// | REQUIRE     | yes   | -- | -- |
/// | VERIFY_CA   | yes   | yes | -- |
/// | VERIFY_FULL | yes   | yes | yes |
///
/// `VERIFY_FULL` is the recommended setting for production. `PREFER` is libpq's
/// default but offers no MITM protection.
public record SslConfig(SslMode mode,
                        Option<Path> rootCertPem,
                        Option<ClientCertificate> clientCertificate,
                        ChannelBinding channelBinding,
                        Option<Consumer<SslContextBuilder>> sslContextCustomizer,
                        boolean allowAllCertificates) {
    public SslConfig {
        if ((mode == SslMode.VERIFY_CA || mode == SslMode.VERIFY_FULL) && rootCertPem.isEmpty() && sslContextCustomizer.isEmpty()) {
            throw new IllegalArgumentException(mode
                                              + " requires either a root certificate (rootCertPem) or a custom SslContext (sslContextCustomizer)");
        }

        if (allowAllCertificates && mode != SslMode.REQUIRE) {
            throw new IllegalArgumentException("allowAllCertificates() is only valid in REQUIRE mode (got " + mode
                                              + "). "
                                              + "Use VERIFY_CA / VERIFY_FULL for properly validated TLS.");
        }
    }

    /// No TLS attempted. Plain TCP.
    public static SslConfig disabled() {
        return new SslConfig(SslMode.DISABLE, none(), none(), ChannelBinding.DISABLE, none(), false);
    }

    /// Plain TCP first; if the server requires TLS, retry with TLS. Defers to the server.
    public static SslConfig allow() {
        return new SslConfig(SslMode.ALLOW, none(), none(), ChannelBinding.PREFER, none(), false);
    }

    /// TLS first; fall back to plain TCP if the server doesn't support TLS. libpq default.
    public static SslConfig prefer() {
        return new SslConfig(SslMode.PREFER, none(), none(), ChannelBinding.PREFER, none(), false);
    }

    /// TLS mandatory. Encrypts but does NOT validate the certificate chain or hostname.
    /// Vulnerable to MITM with any rogue server certificate.
    public static SslConfig require() {
        return new SslConfig(SslMode.REQUIRE, none(), none(), ChannelBinding.PREFER, none(), false);
    }

    /// TLS mandatory + CA chain validated against the supplied root certificate.
    /// Hostname is NOT validated — vulnerable to MITM by any host with a cert from
    /// the same CA. Use `verifyFull` unless you specifically need cross-host certs.
    public static SslConfig verifyCa(Path rootCertPem) {
        return new SslConfig(SslMode.VERIFY_CA, some(rootCertPem), none(), ChannelBinding.PREFER, none(), false);
    }

    /// TLS mandatory + CA chain validated + hostname matches certificate. Recommended
    /// for production.
    public static SslConfig verifyFull(Path rootCertPem) {
        return new SslConfig(SslMode.VERIFY_FULL, some(rootCertPem), none(), ChannelBinding.PREFER, none(), false);
    }

    /// Add an mTLS client certificate. The key file is a PEM-encoded PKCS#8 key;
    /// pass `Option.none()` for an unencrypted key.
    public SslConfig withClientCertificate(Path cert, Path key, Option<String> password) {
        return new SslConfig(mode,
                             rootCertPem,
                             some(new ClientCertificate(cert, key, password)),
                             channelBinding,
                             sslContextCustomizer,
                             allowAllCertificates);
    }

    /// Override the channel-binding policy. Default is `PREFER` (use channel binding
    /// when both server and TLS support it). `REQUIRE` rejects connections where
    /// channel binding cannot be established (no TLS, or server doesn't advertise
    /// SCRAM-SHA-256-PLUS).
    public SslConfig withChannelBinding(ChannelBinding channelBinding) {
        return new SslConfig(mode,
                             rootCertPem,
                             clientCertificate,
                             channelBinding,
                             sslContextCustomizer,
                             allowAllCertificates);
    }

    /// Escape hatch for full Netty `SslContextBuilder` access — apply provider, cipher
    /// suites, custom trust managers, etc. Runs after the framework's defaults so it
    /// can override anything.
    public SslConfig withSslContextCustomizer(Consumer<SslContextBuilder> customizer) {
        return new SslConfig(mode,
                             rootCertPem,
                             clientCertificate,
                             channelBinding,
                             some(customizer),
                             allowAllCertificates);
    }

    /// **DANGEROUS.** Disable certificate validation entirely. Only valid in `REQUIRE`
    /// mode. Logs a warning per connection. Intended for diagnostic / development use
    /// only — never enable in production.
    public SslConfig withAllowAllCertificates() {
        return new SslConfig(mode, rootCertPem, clientCertificate, channelBinding, sslContextCustomizer, true);
    }

    public enum SslMode {
        DISABLE,
        ALLOW,
        PREFER,
        REQUIRE,
        VERIFY_CA,
        VERIFY_FULL
    }

    public enum ChannelBinding {
        DISABLE,
        PREFER,
        REQUIRE
    }

    public record ClientCertificate(Path cert, Path key, Option<String> password) {}
}
