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

import java.nio.file.Files;
import java.nio.file.Path;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;

import static org.assertj.core.api.Assertions.assertThat;

/// A private key that does not belong to its certificate builds a TLS context without error and then completes no
/// handshake. The check runs inside both context factories, so every caller (startup, rotation, the cluster renewal
/// gate) refuses such an identity. Each path is pinned on its own.
class KeyPairCheckTest {
    private static CertificateBundle bundle(String nodeId) {
        return SelfSignedCertificateProvider.selfSignedCertificateProvider("key-pair-check-secret".getBytes())
                                            .unwrap()
                                            .issueCertificate(nodeId, "localhost")
                                            .unwrap();
    }

    private static CertificateBundle mismatched() {
        var certificateOwner = bundle("cert-node");
        var keyOwner = bundle("key-node");

        return new CertificateBundle(certificateOwner.certificatePem(),
                                     keyOwner.privateKeyPem(),
                                     certificateOwner.caCertificatePem(),
                                     certificateOwner.notAfter());
    }

    private static TlsConfig.Identity identity(CertificateBundle bundle) {
        return new TlsConfig.Identity.FromProvider(bundle.certificatePem(), bundle.privateKeyPem());
    }

    @Test
    void check_matchingPair_passes_mismatchedPair_isRefused() {
        assertThat(KeyPairCheck.check(identity(bundle("ok-node"))).isSuccess()).as("control: a matching pair").isTrue();

        var refused = KeyPairCheck.check(identity(mismatched()));

        assertThat(refused.isFailure()).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(TlsError.KeyDoesNotMatchCertificate.class));
    }

    @Test
    void check_keyOfAnotherAlgorithm_isRefused() throws Exception {
        var rsa = new SelfSignedCertificate("localhost", "RSA", 2048);
        var ecKey = bundle("ec-node").privateKeyPem();
        var rsaCertificate = Files.readAllBytes(rsa.certificate().toPath());

        assertThat(KeyPairCheck.check(new TlsConfig.Identity.FromProvider(rsaCertificate, ecKey)).isFailure())
            .as("an EC key under an RSA certificate").isTrue();
        rsa.delete();
    }

    @Test
    void check_filesIdentity_refusesMismatchAndPassesMatch(@TempDir Path dir) throws Exception {
        var good = bundle("file-node");
        var bad = mismatched();

        Files.write(dir.resolve("good.crt"), good.certificatePem());
        Files.write(dir.resolve("good.key"), good.privateKeyPem());
        Files.write(dir.resolve("bad.crt"), bad.certificatePem());
        Files.write(dir.resolve("bad.key"), bad.privateKeyPem());

        assertThat(KeyPairCheck.check(new TlsConfig.Identity.FromFiles(dir.resolve("good.crt"), dir.resolve("good.key"), org.pragmatica.lang.Option.none())).isSuccess()).isTrue();
        assertThat(KeyPairCheck.check(new TlsConfig.Identity.FromFiles(dir.resolve("bad.crt"), dir.resolve("bad.key"), org.pragmatica.lang.Option.none())).isFailure()).isTrue();
    }

    @Test
    void tlsContextFactory_refusesMismatchedIdentity_forServerAndClientAndBundle() {
        var bad = mismatched();
        var serverConfig = new TlsConfig.Server(identity(bad), org.pragmatica.lang.Option.none());

        assertThat(TlsContextFactory.createServer(serverConfig).isFailure()).as("server context").isTrue();
        assertThat(TlsContextFactory.createServerFromBundle(bad).isFailure()).as("server context from bundle").isTrue();
        assertThat(TlsContextFactory.createServerFromBundle(bundle("ok-node")).isSuccess()).as("control").isTrue();
    }

    @Test
    void quicSslContextFactory_refusesMismatchedBundle_forServerAndClient() {
        var bad = mismatched();

        assertThat(QuicSslContextFactory.createServerFromBundle(bad, ClientAuthPolicy.NOT_REQUESTED).isFailure()).as("QUIC server").isTrue();
        assertThat(QuicSslContextFactory.createClientFromBundle(bad).isFailure()).as("QUIC client").isTrue();
        assertThat(QuicSslContextFactory.createServerFromBundle(bundle("ok-node"), ClientAuthPolicy.NOT_REQUESTED).isSuccess()).as("control: QUIC server").isTrue();
        assertThat(QuicSslContextFactory.createClientFromBundle(bundle("ok-node")).isSuccess()).as("control: QUIC client").isTrue();
    }
}
