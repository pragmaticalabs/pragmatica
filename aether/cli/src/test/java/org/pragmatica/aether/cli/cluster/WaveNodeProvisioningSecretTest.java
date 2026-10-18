// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;

import static org.assertj.core.api.Assertions.assertThat;

/// #828 — an operator who adopted `AETHER_CLUSTER_SECRET_FILE` can still join nodes: the CLI's own secret lookup goes through the
/// shared reader. Red if it reverts to a plain `AETHER_CLUSTER_SECRET` read.
class WaveNodeProvisioningSecretTest {
    @Test
    void ambientSecret_withOnlyTheFileVariable_resolvesIt() throws Exception {
        var file = Files.createTempFile("wave-secret-", ".txt");

        try {
            Files.writeString(file, "sentinel-secret-7f3a9c\n");

            assertThat(WaveNodeProvisioning.ambientSecret(Map.of("AETHER_CLUSTER_SECRET_FILE", file.toString())::get).unwrap()).isEqualTo("sentinel-secret-7f3a9c");
            assertThat(WaveNodeProvisioning.ambientSecret(Map.of("AETHER_CLUSTER_SECRET", "plain-sentinel")::get).unwrap()).isEqualTo("plain-sentinel");
            assertThat(WaveNodeProvisioning.ambientSecret(Map.<String, String> of()::get).isEmpty()).isTrue();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /// The ledger is the cluster's own record of its secret; the ambient env secret is the fallback for a
    /// machine without one. A ledger that is present but unreadable must REFUSE: read as "no ledger" it falls
    /// through to an env secret that may differ from the ledger's, and the nodes rendered with it never join.
    /// Runs against the real `~/.aether/clusters/<name>` path with a per-run random name, as the other
    /// ledger tests do.
    @Nested
    class UnreadableLedger {
        private final ClusterName clusterName = uniqueClusterName();
        private final AtomicInteger clusterRequests = new AtomicInteger();
        private HttpServer fakeCluster;
        private String originalEndpoint;

        private static ClusterName uniqueClusterName() {
            var suffix = new byte[6];

            new SecureRandom().nextBytes(suffix);

            return ClusterName.clusterName("join-secret-" + HexFormat.of().formatHex(suffix)).unwrap();
        }

        @BeforeEach
        @SuppressWarnings("JBCT-EX-01")
        void setUp() throws Exception {
            fakeCluster = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fakeCluster.createContext("/", exchange -> {
                clusterRequests.incrementAndGet();
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            });
            fakeCluster.start();
            originalEndpoint = ClusterHttpClient.ENDPOINT_OVERRIDE.get();
            ClusterHttpClient.ENDPOINT_OVERRIDE.set("http://127.0.0.1:" + fakeCluster.getAddress().getPort());
        }

        @AfterEach
        @SuppressWarnings("JBCT-EX-01")
        void tearDown() throws Exception {
            ClusterHttpClient.ENDPOINT_OVERRIDE.set(originalEndpoint);
            fakeCluster.stop(0);

            var dir = BootstrapStatePersistence.AETHER_DIR.resolve(clusterName.value());

            Files.deleteIfExists(dir.resolve(BootstrapStatePersistence.STATE_FILE_NAME));
            Files.deleteIfExists(dir);
        }

        @SuppressWarnings("JBCT-EX-01")
        private void writeLedger(String content) throws Exception {
            var path = BootstrapStatePersistence.statePath(clusterName);

            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        }

        private String ledger(String entry, String secret) {
            return """
                {"clusterName": "%s", "configHash": "h", "startedAt": "2026-10-18T00:00:00Z", "phases": {},
                 "createdResources": [%s], "provisionedNodeIds": [], "collectedAddresses": [], "clusterSecret": "%s"}
                """.formatted(clusterName.value(), entry, secret);
        }

        @Test
        @SuppressWarnings("JBCT-EX-01")
        void resolveJoinInputs_ledgerWithUnknownEntryAndDifferingEnvSecret_refusesAndTouchesNothing() throws Exception {
            var text = ledger("{\"type\": \"FutureThing\", \"resourceId\": \"x-9\"}", "ledger-secret");

            writeLedger(text);

            WaveNodeProvisioning.resolveJoinInputs(clusterName, Map.of("AETHER_CLUSTER_SECRET", "different-env-secret")::get)
                                .onSuccess(inputs -> org.junit.jupiter.api.Assertions.fail("must not fall back to the env secret: " + inputs))
                                .onFailure(cause -> assertThat(cause.message()).contains("unreadable").contains("FutureThing"));

            assertThat(clusterRequests.get()).as("nothing is resolved or rendered past the refusal").isZero();
            assertThat(Files.readString(BootstrapStatePersistence.statePath(clusterName))).isEqualTo(text);
        }

        @Test
        void resolveJoinInputs_tornLedgerAndEnvSecret_refuses() throws Exception {
            writeLedger("{ torn");

            WaveNodeProvisioning.resolveJoinInputs(clusterName, Map.of("AETHER_CLUSTER_SECRET", "env-secret")::get)
                                .onSuccess(inputs -> org.junit.jupiter.api.Assertions.fail("a torn ledger must not fall through to the env secret"))
                                .onFailure(cause -> assertThat(cause.message()).contains("unreadable"));

            assertThat(clusterRequests.get()).isZero();
        }

        /// Controls: with a readable ledger the ledger's secret wins over a differing env secret, and with no
        /// ledger the env secret is used; both proceed to the peers lookup (the fake cluster counts it).
        @Test
        void resolveJoinInputs_readableLedger_proceedsPastSecretResolution() throws Exception {
            writeLedger(ledger("", "ledger-secret"));

            WaveNodeProvisioning.resolveJoinInputs(clusterName, Map.of("AETHER_CLUSTER_SECRET", "different-env-secret")::get);

            assertThat(clusterRequests.get()).as("control: secret resolution passed and peers were requested").isPositive();
        }

        @Test
        void resolveJoinInputs_noLedger_usesTheEnvSecret_andProceeds() {
            WaveNodeProvisioning.resolveJoinInputs(clusterName, Map.of("AETHER_CLUSTER_SECRET", "env-secret")::get);

            assertThat(clusterRequests.get()).as("control: the env secret is accepted and peers were requested").isPositive();
        }

        @Test
        void resolveJoinInputs_noLedgerNoEnv_refusesWithoutRequests() {
            WaveNodeProvisioning.resolveJoinInputs(clusterName, Map.<String, String> of()::get)
                                .onSuccess(inputs -> org.junit.jupiter.api.Assertions.fail("no secret anywhere"))
                                .onFailure(cause -> assertThat(cause.message()).contains("no cluster secret"));

            assertThat(clusterRequests.get()).isZero();
        }
    }
}
