package org.pragmatica.net.tcp.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.net.tcp.security.SelfSignedCertificateProvider.selfSignedCertificateProvider;

class SelfSignedCertificateProviderTest {
    private static final byte[] CLUSTER_SECRET = "test-cluster-secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_SECRET = "other-cluster-secret".getBytes(StandardCharsets.UTF_8);
    /// The secret shipped in `build.sh` for local dev — pinned so the vectors cover a value real
    /// clusters actually use, not only a test-only string.
    private static final byte[] DEV_SECRET = "aether-local-dev-cluster-secret".getBytes(StandardCharsets.UTF_8);
    /// Fixed, so the gossip vector does not move with the wall clock.
    private static final String PINNED_GOSSIP_VERSION = "2026-01-01";

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

        @Test
        void nextGossipKey_present_and_distinct() {
            // #256: the provider pre-derives the next-day key so a node still on day N can accept
            // datagrams from a peer booted on day N+1.
            assertThat(provider.nextGossipKey().isPresent()).isTrue();
            provider.nextGossipKey().onPresent(SelfSignedCertificateProviderTest::assertValidGossipKey);
            provider.currentGossipKey()
                    .onSuccess(current -> provider.nextGossipKey()
                                                  .onPresent(next -> assertKeysDiffer(current, next)));
        }

        @Test
        void nextGossipKey_distinctFrom_previousGossipKey() {
            provider.previousGossipKey()
                    .onPresent(previous -> provider.nextGossipKey()
                                                   .onPresent(next -> assertKeysDiffer(previous, next)));
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

    /// #980 verification finding SF1 — the HISTORICAL-OUTPUT gate, and the highest-consequence test in
    /// this file.
    ///
    /// Every other test here compares the implementation **to itself within one version**:
    /// `Determinism.deterministic_sameSecret_sameCA` derives twice and checks agreement, which a
    /// self-consistent reformulation of the HKDF steps satisfies perfectly — swapped extract
    /// arguments, a changed salt, a reordered expand. Such a rewrite would stay deterministic, stay
    /// label-separated, keep every test in this file green, **and change the CA identity of every
    /// deployed cluster**, silently invalidating their mutual trust.
    ///
    /// That risk grew with #980, not shrank: the derivation moved from a private method with one
    /// consumer into a public interface in another module with three. `ClusterSecretDerivationTest`
    /// pins the admin-key label against an externally computed vector for exactly this reason; the CA
    /// and gossip labels, whose blast radius is far larger, had none.
    ///
    /// **Provenance of these values.** Captured from this implementation, which the #980 adversarial
    /// verification established is byte-identical to `8f02cd3cf` (pre-consolidation) by running ONE
    /// probe against BOTH builds — CA public key, CA subject DN, gossip key id and gossip key bytes
    /// all identical, with a discrimination control proving the probe could tell secrets apart. So
    /// current output IS historical output, and these constants are a genuine regression gate rather
    /// than a snapshot of whatever the code happens to do. Confirmed stable across separate JVM runs
    /// before being written down.
    ///
    /// The pinned quantity is the CA **public key**, not the certificate: the certificate embeds
    /// `notBefore`/`notAfter` and differs run to run. The verifier hit that as a false positive; it is
    /// recorded here so the next reader does not re-derive it.
    ///
    /// If this test ever fails, the derivation changed. That is not a test to update — it is a
    /// compatibility break, and the correct response is a NEW `info` label, never new constants here.
    @Nested
    class HistoricalVectors {
        @Test
        void caPublicKey_matchesTheHistoricalVector_forThePinnedSecret() {
            assertThat(caPublicKeyHex(CLUSTER_SECRET))
                .isEqualTo("3059301306072a8648ce3d020106082a8648ce3d030107034200040e2a1f1aa22fd397eb33245d23847f92"
                           + "2aacc80d336e990c7d65a17ee9afd18ae4a839c99453736c893cbc56799df4ba8583381e6f50c6235b69"
                           + "4a00b2848e46");
        }

        @Test
        void caPublicKey_matchesTheHistoricalVector_forTheDevSecret() {
            assertThat(caPublicKeyHex(DEV_SECRET))
                .isEqualTo("3059301306072a8648ce3d020106082a8648ce3d030107034200046d0ac4cbf87ed829dd80ad920fada9b1"
                           + "49df0c7629696f3029d724d2652736dcb4a8cce332b3fae56a7658fdc16961dd14b060202f80346d96ca"
                           + "c40e8575c640");
        }

        /// The gossip label under a FIXED version. `currentGossipKey()` is wall-clock derived
        /// (`aether-gossip-key-v<date>`) and cannot be pinned; `deriveVersionedGossipKey` can, and it
        /// runs the same HKDF path.
        @Test
        void versionedGossipKey_matchesTheHistoricalVector() {
            var key = versionedGossipKey(CLUSTER_SECRET, PINNED_GOSSIP_VERSION);

            assertThat(key.keyId()).isEqualTo(-1260787674);
            assertThat(HexFormat.of().formatHex(key.key()))
                .isEqualTo("51e142c53d35905ca025e743c05ed0fa17673f1002528873aa185fbe16b47f3a");
        }

        @Test
        void versionedGossipKey_matchesTheHistoricalVector_forTheDevSecret() {
            var key = versionedGossipKey(DEV_SECRET, PINNED_GOSSIP_VERSION);

            assertThat(key.keyId()).isEqualTo(-486143897);
            assertThat(HexFormat.of().formatHex(key.key()))
                .isEqualTo("90c5574a9f9802629aae21402f41247d4c25f05066f769fc9676c0f5ed1dda29");
        }

        /// Discrimination control. Without it, an implementation returning a constant would satisfy
        /// every vector above if the constant happened to be right for one secret; more practically,
        /// it proves the helpers below read something secret-dependent rather than a fixed field.
        @Test
        void theVectors_areSecretDependent_notConstants() {
            assertThat(caPublicKeyHex(CLUSTER_SECRET)).isNotEqualTo(caPublicKeyHex(DEV_SECRET));
            assertThat(versionedGossipKey(CLUSTER_SECRET, PINNED_GOSSIP_VERSION).keyId())
                .isNotEqualTo(versionedGossipKey(DEV_SECRET, PINNED_GOSSIP_VERSION).keyId());
        }
    }

    // ===== Assertion helpers =====

    @SuppressWarnings("JBCT-EX-01")
    private static String caPublicKeyHex(byte[] secret) {
        try {
            var pem = selfSignedCertificateProvider(secret).unwrap().caCertificate().unwrap().certificatePem();
            var certificate = CertificateFactory.getInstance("X.509")
                                                .generateCertificate(new ByteArrayInputStream(pem));

            return HexFormat.of().formatHex(certificate.getPublicKey().getEncoded());
        } catch (Exception e) {
            throw new AssertionError("unable to read the CA public key", e);
        }
    }

    private static GossipKey versionedGossipKey(byte[] secret, String version) {
        return ((SelfSignedCertificateProvider) selfSignedCertificateProvider(secret).unwrap())
            .deriveVersionedGossipKey(version)
            .unwrap();
    }


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
