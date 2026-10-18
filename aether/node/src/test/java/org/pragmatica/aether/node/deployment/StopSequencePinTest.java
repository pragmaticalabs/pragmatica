// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// B13 / W-16 (a), MT-15, at the one place no booted-node test can see: `AetherNode.stop()`. The router is quiesced only after the node's `SUSPEND`s
/// have run, and that order lives in [DeploymentWiring#leaveQuorum], which the unit tests drive. What they cannot see is whether `stop()` goes
/// through it, so this is a text-level pin on the node's source: the router's quiesce appears exactly once in `AetherNode`, as the argument of
/// `deploymentWiring.leaveQuorum(`, and nowhere as a call of its own. It reports the length of the source it scanned, and a planted bare
/// `router.quiesce()` is its positive control.
class StopSequencePinTest {
    private static final Path NODE_SOURCE = Path.of("src", "main", "java", "org", "pragmatica", "aether", "node", "AetherNode.java");
    private static final Pattern QUIESCE = Pattern.compile("(?:::|\\.)quiesce\\b");
    private static final Pattern THROUGH_THE_WIRING = Pattern.compile("deploymentWiring\\s*\\.leaveQuorum\\(\\s*\\(\\)\\s*->\\s*router\\.route\\(ClusterStateNotification\\.passive\\(\\)\\)\\s*,\\s*router::quiesce\\s*\\)");

    /// The occurrences of `quiesce` in `source` and of the sanctioned form of the stop.
    private record Scan(long length, long quiesces, long sanctioned) {}

    private static Scan scan(String source) {
        return new Scan(source.length(), QUIESCE.matcher(source).results().count(), THROUGH_THE_WIRING.matcher(source).results().count());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theRouterIsQuiescedOnlyThroughTheWiringsLeaveQuorum() {
        var scan = scan(read(NODE_SOURCE));

        assertThat(scan.length()).as("the scan read the node's source").isGreaterThan(100_000L);
        assertThat(scan.sanctioned()).as("stop() hands PASSIVE and the quiesce to DeploymentWiring.leaveQuorum").isEqualTo(1L);
        assertThat(scan.quiesces()).as("and the quiesce is not called anywhere else").isEqualTo(scan.sanctioned());
    }

    @Test
    void aBareQuiesce_plantedInTheStop_isFound() {
        var planted = "router.route(ClusterStateNotification.passive());\n router.quiesce();\n";
        var scan = scan(planted);

        assertThat(scan.sanctioned()).isZero();
        assertThat(scan.quiesces()).isNotEqualTo(scan.sanctioned());
    }

    @Test
    void theSanctionedForm_isRecognised() {
        var scan = scan("deploymentWiring.leaveQuorum(() -> router.route(ClusterStateNotification.passive()),\n router::quiesce);");

        assertThat(scan.sanctioned()).isEqualTo(1L);
        assertThat(scan.quiesces()).isEqualTo(1L);
    }
}
