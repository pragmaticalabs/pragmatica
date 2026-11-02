// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// O3-3 review (v-2541): the guard of a decision taken on a value read earlier is keyed by the implementation's own [CapacityRecords#recordKey], so an
/// implementation that moves the reservation (O3-4's impl B) cannot inherit impl A's key through a default. The stand-in below is impl A in every
/// respect but its record key, and goes through the interface's own default for the witness.
class CapacityRecordsWitnessTest {
    private static final NodeId NODE = new NodeId("heal-7");
    private static final CapacityReservationValue HELD = new CapacityReservationValue("east", "bind", "core", CapacityReservationPhase.DISPATCHED);

    @Test
    void theWitnessOfAnExpectedValue_followsTheRecordKeyOfTheImplementation() {
        var implA = CapacityRecords.forBuilding();
        var elsewhere = AetherKey.NodeIdentityKey.nodeIdentityKey(NODE);
        var standIn = (CapacityRecords) Proxy.newProxyInstance(CapacityRecords.class.getClassLoader(),
                                                               new Class<?>[] {CapacityRecords.class},
                                                               (proxy, method, args) -> {
                                                                   if (method.getName().equals("recordKey")) {
                                                                       return elsewhere;
                                                                   }
                                                                   if (method.isDefault() && method.getName().equals("witness")) {
                                                                       return InvocationHandler.invokeDefault(proxy, method, args);
                                                                   }
                                                                   return method.invoke(implA, args);
                                                               });

        assertThat(implA.witness(NODE, Option.none()).key()).as("control: impl A guards its own reservation key").isEqualTo(implA.recordKey(NODE));
        assertThat(standIn.recordKey(NODE)).as("control: the stand-in moved the record").isEqualTo(elsewhere);
        assertThat(standIn.witness(NODE, Option.none()).key()).isEqualTo(elsewhere);
        assertThat(standIn.witness(NODE, Option.none()).expected().isEmpty()).as("an expected absence stays an absence").isTrue();
        assertThat(standIn.witness(NODE, Option.some(HELD)).expected().or((Object) null)).as("an expected value is carried exactly").isEqualTo(HELD);
    }
}
