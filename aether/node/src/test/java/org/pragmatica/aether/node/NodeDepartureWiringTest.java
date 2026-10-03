// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1835 wiring pins for the confirmed-death edge in `AetherNode`'s assembly, which no unit test constructs.
/// Following `OperatorWarningWiringTest`, these read the assembly source with comments stripped and whitespace
/// removed, so they pin that the wiring is PRESENT and has the intended shape.
///
/// Two properties, which the FSM-level and notifier-level tests cannot express because the lambda lives here:
/// the unconditional heal/link-drop/quorum-nudge callback stays UNGATED, and the NODE_FAILED + CRITICAL pair is
/// reached only through the reachability-split listeners, never from the unconditional callback.
class NodeDepartureWiringTest {
    @Test
    void membershipDeath_staysUnconditional_andCarriesNothingElse() {
        assertThat(assemblyCode()).contains("membershipFsm.onConfirmedDeparture(departed->{onMembershipDeath(departed,dropDeadPeerLink,quorumLossDetectorRef,leaderReconcilerRef);});");
    }

    @Test
    void failedPair_rideOnlyTheReachableDeathEdge_neverJoinedHasItsOwn() {
        var code = assemblyCode();

        assertThat(code).contains("membershipFsm.onReachableDeath(departureNotifier::onConfirmedDeparture);");
        assertThat(code).contains("membershipFsm.onNeverReachableDeath(departureNotifier::onNeverJoined);");
        assertThat(code).doesNotContain("departureNotifier.onConfirmedDeparture(departed)");
    }

    /// `AetherNode.java` with line comments removed and all whitespace stripped. An unreadable file fails loudly,
    /// because a pin that scans nothing always passes.
    static String assemblyCode() {
        var file = sourceRoot().resolve("org/pragmatica/aether/node/AetherNode.java");

        assertThat(file).exists();

        return readFile(file).lines()
                             .map(line -> line.replaceFirst("//.*$", ""))
                             .collect(Collectors.joining())
                             .replaceAll("\\s+", "");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(NodeDepartureWiringTest.class.getProtectionDomain()
                                                                    .getCodeSource()
                                                                    .getLocation()
                                                                    .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }
}
