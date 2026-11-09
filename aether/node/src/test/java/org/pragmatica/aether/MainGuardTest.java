// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.TlsConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MainGuardTest {
    private static final String MAIN_SOURCE = "aether/node/src/main/java/org/pragmatica/aether/Main.java";

    @Nested
    class NodeIdGate {

        @Test
        void publishNodeId_setsTheNodeIdProperty_forDatasourceDefaults() {
            var saved = System.getProperty("aether.node.id");

            try {
                Main.publishNodeId(NodeId.nodeId("node-pub").unwrap());

                assertEquals("node-pub", System.getProperty("aether.node.id"));
            } finally {
                restoreProperty("aether.node.id", saved);
            }
        }

        // The call site is not reachable from a unit test (Main.run needs a whole boot configuration), so it is pinned from
        // the source, as MainFatalEscapeInstallTest pins the relay install: run() publishes the node id it just resolved,
        // before it does anything that provisions a datasource. Mutation: delete the call (RED).
        @Test
        void run_publishesTheNodeId_rightAfterResolvingIt() throws Exception {
            var code = runBody(Files.readString(repositoryRoot().resolve(MAIN_SOURCE)));
            var resolved = code.indexOf("var nodeId = parseNodeId(aetherConfig);");
            var published = code.indexOf("publishNodeId(nodeId);");
            var nextStep = code.indexOf("var port = parsePort(aetherConfig);");

            assertThat(resolved).as("control: run() resolves the node id").isNotNegative();
            assertThat(published).as("run() publishes the resolved node id").isGreaterThan(resolved);
            assertThat(published).as("published before the next boot step").isLessThan(nextStep);
        }

        private static String runBody(String source) {
            var start = source.indexOf("private void run() {");

            assertThat(start).as("control: Main has a run()").isNotNegative();

            return source.substring(start)
                         .lines()
                         .map(line -> line.replaceFirst("//.*$", ""))
                         .collect(Collectors.joining("\n"));
        }

        private static Path repositoryRoot() throws Exception {
            var current = Path.of(MainGuardTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            while (current != null && !Files.isRegularFile(current.resolve(MAIN_SOURCE))) {
                current = current.getParent();
            }

            return current == null
                   ? fail("repository root not found above the test's code source")
                   : current;
        }

        private static void restoreProperty(String key, String saved) {
            if (saved == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, saved);
            }
        }

        @Test
        void resolveNodeId_succeeds_fromArg() {
            Main.resolveNodeId(Option.some("node-arg"), "", "")
                .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                .onSuccess(id -> assertEquals("node-arg", id.id()));
        }

        @Test
        void resolveNodeId_succeeds_fromAetherNodeIdEnv() {
            Main.resolveNodeId(Option.none(), "node-aether", "")
                .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                .onSuccess(id -> assertEquals("node-aether", id.id()));
        }

        @Test
        void resolveNodeId_succeeds_fromNodeIdEnv() {
            Main.resolveNodeId(Option.none(), "", "node-env")
                .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                .onSuccess(id -> assertEquals("node-env", id.id()));
        }

        @Test
        void resolveNodeId_prefersArg_overEnv() {
            Main.resolveNodeId(Option.some("node-arg"), "node-aether", "node-env")
                .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                .onSuccess(id -> assertEquals("node-arg", id.id()));
        }

        @Test
        void resolveNodeId_prefersAetherEnv_overNodeIdEnv() {
            Main.resolveNodeId(Option.none(), "node-aether", "node-env")
                .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                .onSuccess(id -> assertEquals("node-aether", id.id()));
        }

        @Test
        void resolveNodeId_fails_whenNoneProvided() {
            Main.resolveNodeId(Option.none(), "", "")
                .onSuccess(_ -> fail("Expected failure when no explicit node id is provided — no random fallback"));
        }
    }

    @Nested
    class ClusterNameGate {

        @Test
        void verifyClusterNamePresent_succeeds_forValidName() {
            Main.verifyClusterNamePresent("prod-eu")
                .onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void verifyClusterNamePresent_succeeds_forSingleCharNameA() {
            // Single-char lowercase DNS labels are valid (integration cluster-A uses "a").
            Main.verifyClusterNamePresent("a")
                .onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void verifyClusterNamePresent_succeeds_forSingleCharNameB() {
            // Integration cluster-B uses "b".
            Main.verifyClusterNamePresent("b")
                .onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void verifyClusterNamePresent_fails_whenEmpty() {
            Main.verifyClusterNamePresent("").onSuccess(_ -> fail("Expected failure for empty cluster name"));
        }

        @Test
        void verifyClusterNamePresent_fails_whenTrailingHyphen() {
            // Trailing hyphen is not a valid DNS label.
            Main.verifyClusterNamePresent("x-").onSuccess(_ -> fail("Expected failure for trailing-hyphen name"));
        }

        @Test
        void verifyClusterNamePresent_fails_whenNull() {
            Main.verifyClusterNamePresent(null).onSuccess(_ -> fail("Expected failure for null cluster name"));
        }

        @Test
        void verifyClusterNamePresent_fails_whenBlank() {
            Main.verifyClusterNamePresent("   ").onSuccess(_ -> fail("Expected failure for blank cluster name"));
        }

        @Test
        void verifyClusterNamePresent_fails_whenMalformedUppercase() {
            // Uppercase is rejected — the pattern is a lowercase DNS label.
            Main.verifyClusterNamePresent("Prod").onSuccess(_ -> fail("Expected failure for malformed cluster name"));
        }

        @Test
        void verifyClusterNamePresent_fails_whenMalformedLeadingDigit() {
            Main.verifyClusterNamePresent("1prod").onSuccess(_ -> fail("Expected failure for leading-digit name"));
        }
    }

    @Nested
    class DevModeGate {

        @Test
        void verifyDevModeCompatibility_fails_whenDevModeOnAndRealCerts() {
            var realCerts = TlsConfig.tlsConfig("/etc/tls/cert.pem", "/etc/tls/key.pem", "/etc/tls/ca.pem");

            Main.verifyDevModeCompatibility(true, realCerts)
                .onSuccess(_ -> fail("Expected failure: dev-mode with operator certificates"));
        }

        @Test
        void verifyDevModeCompatibility_succeeds_whenDevModeOnAndAutoGenerate() {
            // Hetzner-shaped: auto_generate=true, no cert/key paths => predicate false => allowed.
            var autoGen = TlsConfig.tlsConfig();

            Main.verifyDevModeCompatibility(true, autoGen)
                .onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }

        @Test
        void verifyDevModeCompatibility_succeeds_whenDevModeOffEvenWithRealCerts() {
            var realCerts = TlsConfig.tlsConfig("/etc/tls/cert.pem", "/etc/tls/key.pem", "/etc/tls/ca.pem");

            Main.verifyDevModeCompatibility(false, realCerts)
                .onFailure(cause -> fail("Expected success but got: " + cause.message()));
        }
    }
}
