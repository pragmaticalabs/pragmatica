// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.TlsConfig;
import org.pragmatica.lang.Option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class MainGuardTest {

    @Nested
    class NodeIdGate {

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
