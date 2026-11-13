// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The F4 port-block choice, offline (f4fixC). Each test holds a real socket, so the probe is exercised against the OS, not a stub.
class EquivalencePortsTest {
    /// Mutation: the bind probe removed (every port reported free); the held block is returned and this goes red.
    @Test
    void aHeldPort_skipsItsBlock() throws IOException {
        try (var held = new ServerSocket(0)) {
            var start = held.getLocalPort() - EquivalencePorts.OFFSETS[1];
            var base = EquivalencePorts.freeBase(start, 3);

            assertThat(base).isEqualTo(start + EquivalencePorts.STEP);
            assertThat(EquivalencePorts.canBind(held.getLocalPort())).isFalse();
        }
    }

    @Test
    void aFreeBlock_isChosenAsIs() throws IOException {
        int start;

        try (var probe = new ServerSocket(0)) {
            start = probe.getLocalPort();
        }
        assertThat(EquivalencePorts.freeBase(start, 3)).isEqualTo(start);
    }

    /// Mutation: the exhausted case returning the last block instead of throwing; goes red (an ERROR, never a PASS on a taken block).
    @Test
    void noFreeBlock_throwsNamingThePort() throws IOException {
        try (var held = new ServerSocket(0)) {
            var port = held.getLocalPort();

            assertThatThrownBy(() -> EquivalencePorts.freeBase(port, EquivalencePorts.STEP, 1, 3, EquivalencePorts::canBind))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(port));
        }
    }
}
