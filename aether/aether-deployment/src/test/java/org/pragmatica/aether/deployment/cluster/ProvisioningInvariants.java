// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;

/// The G3 invariants (q4 section 7.3), each a pure function of a finished [ProvisioningWorld]: the call log, the fake's server set and the
/// committed KV history. A violation is a sentence naming the node and the time; no violation is an empty list.
final class ProvisioningInvariants {
    private ProvisioningInvariants() {}

    /// I1, core path: no `create` in the call log without an earlier committed `CapacityReservationKey(node) = DISPATCHED` for that NodeId.
    static List<String> i1(ProvisioningWorld world) {
        var violations = new ArrayList<String>();

        for (var call : world.provider.calls("create")) {
            if (call.outcome().startsWith("sent") && world.reservationFacts.stream().noneMatch(fact -> fact.node().id().equals(call.name())
                                                                                                   && fact.phase().equals("DISPATCHED")
                                                                                                   && fact.seq() < call.seq())) {
                violations.add("create of " + call.name() + " (seq " + call.seq() + ") has no earlier committed DISPATCHED reservation");
            }
        }

        return violations;
    }

    /// I2: live cluster servers that are not members stay within the deficit plus the explicit allowance, at every sampled second.
    static List<String> i2(ProvisioningWorld world) {
        return List.copyOf(world.i2Violations);
    }

    /// I3: after faults stop (the caller runs the world past the ceiling plus three confirmation passes), no reservation is still DISPATCHED.
    static List<String> i3(ProvisioningWorld world) {
        return world.reservations().entrySet().stream()
                    .filter(entry -> entry.getValue().phase() == CapacityReservationPhase.DISPATCHED)
                    .map(entry -> "reservation of " + entry.getKey().id() + " is still DISPATCHED")
                    .toList();
    }

    /// I4: every live cluster server that is not a member maps to a DISPATCHED/OBSERVED reservation, or is gone.
    static List<String> i4(ProvisioningWorld world) {
        return world.nonMemberServers().stream()
                    .filter(server -> {
                        var phase = world.reservationPhase(new org.pragmatica.consensus.NodeId(server.name()));

                        return phase != CapacityReservationPhase.DISPATCHED && phase != CapacityReservationPhase.OBSERVED;
                    })
                    .map(server -> "server " + server.name() + " is live, not a member, and has no live reservation")
                    .toList();
    }

    /// I5, for a run in which auto-heal is disabled: no automatic delete in the log.
    static List<String> i5(ProvisioningWorld world) {
        return world.provider.calls("delete").stream()
                    .filter(call -> call.outcome().startsWith("deleted"))
                    .map(call -> "automatic delete of " + call.name() + " (" + call.outcome() + ") with auto-heal disabled")
                    .toList();
    }

    /// I6, create arm: at most one `create` per NodeId, and at most one live server per name.
    static List<String> i6(ProvisioningWorld world) {
        var violations = new ArrayList<String>();
        var creates = world.provider.calls("create").stream()
                           .filter(call -> call.outcome().startsWith("sent"))
                           .collect(Collectors.groupingBy(ModelComputeProvider.Call::name, Collectors.counting()));

        creates.forEach((name, count) -> {
            if (count > 1) {
                violations.add(count + " creates for " + name);
            }
        });
        world.provider.servers().stream()
             .collect(Collectors.groupingBy(ModelComputeProvider.Server::name, Collectors.counting()))
             .forEach((name, count) -> {
                 if (count > 1) {
                     violations.add(count + " live servers named " + name);
                 }
             });

        return violations;
    }

    /// I6, delete arm: no automatic delete of a member or of a server holding a DISPATCHED/OBSERVED reservation (n/a until G1-d).
    static List<String> i6Delete(ProvisioningWorld world) {
        return world.provider.calls("delete").stream()
                    .filter(call -> call.outcome().startsWith("deleted:"))
                    .map(call -> "delete of " + call.name() + " " + call.outcome())
                    .toList();
    }

    /// Every invariant that holds in today's code, in one list.
    static List<String> allToday(ProvisioningWorld world) {
        var all = new ArrayList<String>();

        i1(world).forEach(v -> all.add("I1 " + v));
        i2(world).forEach(v -> all.add("I2 " + v));
        i3(world).forEach(v -> all.add("I3 " + v));
        i6(world).forEach(v -> all.add("I6 " + v));

        return all;
    }
}
