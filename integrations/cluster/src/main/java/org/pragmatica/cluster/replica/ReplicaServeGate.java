// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.function.BooleanSupplier;


/// Whether a governor may serve its community's members (spec 3.3 step 3, I-W5): the node IS the governor, its own upstream is a core in a live
/// session, and its replica is current against that core (readiness (a)-(c), (e) and `applied` at or past the newest core heartbeat's `nextSlot`).
/// Until all three hold members get `NOT_SERVING` and stay on a core. This is the only place that decides it; the server asks it at every
/// subscribe, snapshot request and heartbeat.
public final class ReplicaServeGate {
    private final BooleanSupplier governor;
    private final BooleanSupplier followsCore;
    private final BooleanSupplier current;

    private ReplicaServeGate(BooleanSupplier governor, BooleanSupplier followsCore, BooleanSupplier current) {
        this.governor = governor;
        this.followsCore = followsCore;
        this.current = current;
    }

    public static ReplicaServeGate replicaServeGate(BooleanSupplier governor,
                                                    BooleanSupplier followsCore,
                                                    BooleanSupplier current) {
        return new ReplicaServeGate(governor, followsCore, current);
    }

    public boolean mayServeMembers() {
        return governor.getAsBoolean()
               && followsCore.getAsBoolean()
               && current.getAsBoolean();
    }
}
