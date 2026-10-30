// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// I-W5: a governor serves members only when it IS the governor, follows a core, and is current against it. Each test names the mutation.
class ReplicaServeGateTest {
    private final AtomicBoolean governor = new AtomicBoolean(true);
    private final AtomicBoolean followsCore = new AtomicBoolean(true);
    private final AtomicBoolean current = new AtomicBoolean(true);
    private final ReplicaServeGate gate = ReplicaServeGate.replicaServeGate(governor::get, followsCore::get, current::get);

    @Test
    void mayServeMembers_whenAllThreeHold() {
        assertThat(gate.mayServeMembers()).isTrue();
    }

    /// Mutation: open at election, without the replica being current ("open at election").
    @Test
    void doesNotServe_untilTheReplicaIsCurrentAgainstItsCore() {
        current.set(false);

        assertThat(gate.mayServeMembers()).isFalse();
    }

    /// Mutation: drop the governor term (every member would serve its neighbours).
    @Test
    void doesNotServe_whenTheNodeIsNotTheGovernor() {
        governor.set(false);

        assertThat(gate.mayServeMembers()).isFalse();
    }

    /// Mutation: drop the follows-a-core term (a relay of a relay).
    @Test
    void doesNotServe_whileItsOwnUpstreamIsNotACore() {
        followsCore.set(false);

        assertThat(gate.mayServeMembers()).isFalse();
    }
}
