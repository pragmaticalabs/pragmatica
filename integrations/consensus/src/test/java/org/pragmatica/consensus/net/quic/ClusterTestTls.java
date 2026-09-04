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
package org.pragmatica.consensus.net.quic;

import java.nio.charset.StandardCharsets;

import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.net.tcp.security.SelfSignedCertificateProvider;

/// Cluster-transport TLS for tests, built the way production builds it (#715).
///
/// Cluster tests used to pass `TlsConfig.selfSignedServer()` / `TlsConfig.insecureClient()`, which
/// carry no client identity and no shared CA. That worked only because the QUIC server never asked
/// for a peer certificate — the defect itself. Now that the cluster server requires client auth,
/// those configs cannot connect, and rightly so.
///
/// The alternative — a test-only certificate-less mode on `QuicTlsProvider` — was deliberately NOT
/// taken. An exemption inside the factory is precisely the door through which "insecure by default"
/// returns, and it would mean the cluster tests no longer exercise the handshake production runs.
/// Instead the tests do exactly what production and Ember do: derive a deterministic CA from a
/// shared secret and hand every peer an identity signed by it.
///
/// Nodes built from the SAME secret can connect; nodes built from a DIFFERENT secret cannot, which
/// is the property under test.
public final class ClusterTestTls {
    /// Distinct from any real Ember/Forge instance's secret — since #715, every `EmberCluster`
    /// derives a fresh `SecureRandom` secret per instance rather than sharing one literal, so this
    /// fixed string cannot collide with one — and from any deployment secret, so a stray in-JVM
    /// node from another suite cannot accidentally authenticate into a test cluster.
    private static final String DEFAULT_TEST_SECRET = "consensus-test-cluster-secret";

    private ClusterTestTls() {}

    /// Cluster TLS config for a peer with the given node id, under the default test secret.
    public static TlsConfig clusterTls(String nodeId) {
        return clusterTls(nodeId, DEFAULT_TEST_SECRET);
    }

    /// Cluster TLS config under an explicit secret — pass a different one to build a peer that
    /// MUST be rejected, which is how the admission property is asserted rather than assumed.
    public static TlsConfig clusterTls(String nodeId, String clusterSecret) {
        var provider = SelfSignedCertificateProvider.selfSignedCertificateProvider(clusterSecret.getBytes(StandardCharsets.UTF_8))
                                                    .unwrap();

        return TlsConfig.fromProvider(provider, nodeId, "localhost").unwrap();
    }
}
