// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// The cross-process disjointness reader (#3021 probe 7, M12). Two [ClaimChild] JVMs claim at the same time with a
/// deterministic base chooser (first free base) and meet at the probe-to-bind rendezvous; THIS test is the reader: after both
/// exit it reads each child's claim record from the rendezvous directory and checks the port sets. Every arm asserts both
/// children met the rendezvous (exit 0; exit 3 is the inconclusive timeout and fails the arm).
///
/// Arms: claims ON -> disjoint; claims OFF (registry and file locks both off) -> the same block, i.e. the red arm that
/// shows the reader CAN see an overlap; a missing partner -> inconclusive, never green.
class CrossProcessDisjointnessReaderTest {
    private static final int FIRST = 28_000;
    private static final int LAST = FIRST + 4 * PortBlock.STRIDE - 1;

    @TempDir
    Path lockDir;
    @TempDir
    Path meetDir;

    @Test
    @Timeout(180)
    void claimsOn_twoProcesses_getDisjointBlocks_andMeetTheRendezvous() throws Exception {
        var exits = runChildren("ON", 2, 2);

        assertThat(exits).as("both children met the rendezvous").containsExactly(0, 0);
        assertThat(overlap(claimedPorts("a"), claimedPorts("b"))).isEmpty();
    }

    @Test
    @Timeout(180)
    void claimsOff_twoProcesses_getTheSameBlock_soTheReaderSeesTheOverlap() throws Exception {
        var exits = runChildren("OFF", 2, 2);

        assertThat(exits).as("both children met the rendezvous").containsExactly(0, 0);
        assertThat(overlap(claimedPorts("a"), claimedPorts("b"))).hasSize(PortBlock.STRIDE);
    }

    @Test
    @Timeout(180)
    void missingPartner_rendezvousTimesOut_asInconclusive_notAPass() throws Exception {
        var exits = runChildren("ON", 2, 3);

        assertThat(exits).as("a rendezvous that is not met is inconclusive").containsExactly(3, 3);
    }

    private List<Integer> runChildren(String mode, int children, int parties) throws Exception {
        var processes = new ArrayList<Process>();

        for (int i = 0; i < children; i++) {
            processes.add(spawn(mode, parties, i == 0 ? "a" : "b"));
            if (mode.equals("OFF")) {
                awaitFirstProbe();
            }
        }

        var exits = new ArrayList<Integer>();

        for (var process : processes) {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
            exits.add(process.exitValue());
        }
        return exits;
    }

    /// With claims OFF the bind probe is the only guard, and two probes running at the same moment can fail each other's
    /// transient bind, sending one child to another block. The second child therefore starts after the first has passed its
    /// probe (marker written), so the OFF arm deterministically shows the overlap the locks would have prevented.
    private void awaitFirstProbe() throws Exception {
        var deadline = System.nanoTime() + 30_000_000_000L;

        while (probedMarkers() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(probedMarkers()).as("the first child passed its probe").isPositive();
    }

    private long probedMarkers() throws IOException {
        try (var files = Files.list(meetDir)) {
            return files.filter(file -> file.getFileName().toString().startsWith(EmberPortRendezvous.MARKER_PREFIX)).count();
        }
    }

    private Process spawn(String mode, int parties, String label) throws IOException {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = List.of(java,
                              "-cp",
                              System.getProperty("java.class.path"),
                              ClaimChild.class.getName(),
                              lockDir.toString(),
                              meetDir.toString(),
                              meetDir.toString(),
                              label,
                              Integer.toString(parties),
                              mode,
                              Integer.toString(FIRST),
                              Integer.toString(LAST));

        return new ProcessBuilder(command).redirectErrorStream(true)
                                          .redirectOutput(ProcessBuilder.Redirect.to(new File(meetDir.toFile(), "child-" + label + ".log")))
                                          .start();
    }

    private List<Integer> claimedPorts(String label) throws IOException {
        var base = Integer.parseInt(Files.readString(meetDir.resolve("claimed-" + label)));

        return IntStream.range(base, base + PortBlock.STRIDE).boxed().toList();
    }

    private static List<Integer> overlap(List<Integer> left, List<Integer> right) {
        return left.stream().filter(right::contains).toList();
    }
}
