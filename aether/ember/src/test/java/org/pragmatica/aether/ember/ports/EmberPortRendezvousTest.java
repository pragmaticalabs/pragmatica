// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


class EmberPortRendezvousTest {
    @TempDir
    Path dir;

    @Test
    void meet_partnerAbsent_failsAsTimedOut_neverPasses() {
        var rendezvous = EmberPortRendezvous.emberPortRendezvous(dir, 2, 200, "alone");
        var block = PortBlock.portBlock(24_000, 1).unwrap();

        var failure = rendezvous.meet(block).fold(cause -> cause, _ -> null);

        assertThat(failure).isInstanceOf(PortError.RendezvousTimedOut.class);
    }

    @Test
    void meet_bothPartiesArrive_isMetForBoth() throws Exception {
        var block = PortBlock.portBlock(24_000, 1).unwrap();
        var a = EmberPortRendezvous.emberPortRendezvous(dir, 2, 10_000, "a");
        var b = EmberPortRendezvous.emberPortRendezvous(dir, 2, 10_000, "b");
        var other = new Thread(() -> b.meet(block));

        other.start();
        var met = a.meet(block);
        other.join();

        assertThat(met.isSuccess()).isTrue();
    }
}
