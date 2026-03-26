package org.pragmatica.net.tcp.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.net.tcp.security.SelfSignedCertificateProvider.selfSignedCertificateProvider;

class SelfSignedCertificateProviderTest {
    private static final byte[] CLUSTER_SECRET = "test-cluster-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET = "other-cluster-secret".getBytes(StandardCharsets.UTF_8);

    private static CertificateProvider provider;

    @BeforeAll
    static void setUp() {
        var result = selfSignedCertificateProvider(CLUSTER_SECRET);
        result.onFailure(cause -> assertThat(cause).as("Provider creation should succeed").isNull());
        provider = result.or((CertificateProvider) null);
    }

    @Nested
    class ProviderCreation {
        @Test
        void selfSignedCertificateProvider_succeeds_withValidSecret() {
            selfSignedCertificateProvider(CLUSTER_SECRET)
                .onFailure(cause -> assertThat(cause).as("Expected success but got: " + cause.message()).isNull())
                .onSuccess(p -> assertThat(p).isNotNull());
        }
    }

    @Nested
    class CertificateIssuance {
        @Test
        void issueCertificate_produces_validBundle() {
            provider.issueCertificate("node-1", "localhost")
                .onFailure(cause -> assertThat(cause).as("Expected success but got: " + cause.message()).isNull())
                .onSuccess(SelfSignedCertificateProviderTest::assertValidBundle);
        }

        @Test
        void issueCertificate_differentNodeIds_differentCerts() {
            var cert1 = provider.issueCertificate("node-1", "host-1");
            var cert2 = provider.issueCertificate("node-2", "host-2");

            cert1.onFailure(cause -> assertThat(cause).as("cert1 should succeed").isNull());
            cert2.onFailure(cause -> assertThat(cause).as("cert2 should succeed").isNull());

            var pem1 = cert1.map(CertificateBundle::certificatePem);
            var pem2 = cert2.map(CertificateBundle::certificatePem);

            pem1.onSuccess(p1 -> pem2.onSuccess(p2 -> assertThat(p1).isNotEqualTo(p2)));
        }
    }

    @Nested
    class CaCertificate {
        @Test
        void caCertificate_returns_validBundle() {
            provider.caCertificate()
                .onFailure(cause -> assertThat(cause).as("Expected success but got: " + cause.message()).isNull())
                .onSuccess(SelfSignedCertificateProviderTest::assertValidCaBundle);
        }
    }

    @Nested
    class GossipKeys {
        @Test
        void currentGossipKey_returns_validKey() {
            provider.currentGossipKey()
                .onFailure(cause -> assertThat(cause).as("Expected success but got: " + cause.message()).isNull())
                .onSuccess(SelfSignedCertificateProviderTest::assertValidGossipKey);
        }

        @Test
        void previousGossipKey_returns_validKey() {
            assertThat(provider.previousGossipKey().isPresent()).isTrue();
            provider.previousGossipKey()
                    .onPresent(SelfSignedCertificateProviderTest::assertValidGossipKey);
        }

        @Test
        void previousGossipKey_differentFrom_currentGossipKey() {
            provider.currentGossipKey()
                    .onSuccess(current -> provider.previousGossipKey()
                                                  .onPresent(previous -> assertKeysDiffer(current, previous)));
        }
    }

    @Nested
    class VersionedKeyDerivation {
        @Test
        void deriveVersionedGossipKey_succeeds_withValidVersion() {
            var concreteProvider = (SelfSignedCertificateProvider) provider;
            concreteProvider.deriveVersionedGossipKey("42")
                .onFailure(cause -> assertThat(cause).as("Expected success but got: " + cause.message()).isNull())
                .onSuccess(SelfSignedCertificateProviderTest::assertValidGossipKey);
        }

        @Test
        void deriveVersionedGossipKey_sameVersion_sameKey() {
            var concreteProvider = (SelfSignedCertificateProvider) provider;
            var key1 = concreteProvider.deriveVersionedGossipKey("42").or((GossipKey) null);
            var key2 = concreteProvider.deriveVersionedGossipKey("42").or((GossipKey) null);

            assertThat(key1).isNotNull();
            assertThat(key1.key()).isEqualTo(key2.key());
            assertThat(key1.keyId()).isEqualTo(key2.keyId());
        }

        @Test
        void deriveVersionedGossipKey_differentVersions_differentKeys() {
            var concreteProvider = (SelfSignedCertificateProvider) provider;
            var key1 = concreteProvider.deriveVersionedGossipKey("1").or((GossipKey) null);
            var key2 = concreteProvider.deriveVersionedGossipKey("2").or((GossipKey) null);

            assertThat(key1).isNotNull();
            assertThat(key2).isNotNull();
            assertThat(key1.key()).isNotEqualTo(key2.key());
        }
    }

    @Nested
    class Determinism {
        @Test
        void deterministic_sameSecret_sameCA() {
            var key1 = extractGossipKey(CLUSTER_SECRET);
            var key2 = extractGossipKey(CLUSTER_SECRET);

            assertThat(key1).isNotNull();
            assertThat(key1.key()).isEqualTo(key2.key());
            assertThat(key1.keyId()).isEqualTo(key2.keyId());
        }

        @Test
        void deterministic_differentSecret_differentCA() {
            var key1 = extractGossipKey(CLUSTER_SECRET);
            var key2 = extractGossipKey(OTHER_SECRET);

            assertThat(key1).isNotNull();
            assertThat(key2).isNotNull();
            assertThat(key1.key()).isNotEqualTo(key2.key());
        }
    }

    // ===== Assertion helpers =====

    private static void assertKeysDiffer(GossipKey current, GossipKey previous) {
        assertThat(previous.keyId()).isNotEqualTo(current.keyId());
        assertThat(previous.key()).isNotEqualTo(current.key());
    }

    private static void assertValidBundle(CertificateBundle bundle) {
        assertThat(bundle.certificatePem()).isNotEmpty();
        assertThat(bundle.privateKeyPem()).isNotEmpty();
        assertThat(bundle.caCertificatePem()).isNotEmpty();
        assertThat(bundle.notAfter()).isAfter(Instant.now());
    }

    private static void assertValidCaBundle(CertificateBundle bundle) {
        assertThat(bundle.certificatePem()).isNotEmpty();
        assertThat(bundle.caCertificatePem()).isNotEmpty();
        assertThat(bundle.notAfter()).isAfter(Instant.now());
    }

    private static void assertValidGossipKey(GossipKey gossipKey) {
        assertThat(gossipKey.key()).hasSize(32);
        assertThat(gossipKey.keyId()).isNotZero();
        assertThat(gossipKey.createdAt()).isNotNull();
    }

    private static GossipKey extractGossipKey(byte[] secret) {
        return selfSignedCertificateProvider(secret)
            .flatMap(CertificateProvider::currentGossipKey)
            .or((GossipKey) null);
    }
}
