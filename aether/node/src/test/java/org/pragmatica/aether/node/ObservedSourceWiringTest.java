// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.deployment.AdmissionSeam;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// F5b (pragmatica #2765): pins the node-side wiring of the FSM2 observed sources through the production [ObservedSourceWiring]
/// (not a rebuilt fixture): the counted-member predicate and admitted-token reader of the all-instances-failed detector's source. The control loop's observed
/// reader is the F5a state feed, pinned by `ObservedSliceFeedTest` and `DeploymentWiringReadersTest`. Mutation-checked: counted-member -> all,
/// token -> constant, supplier -> empty.
class ObservedSourceWiringTest {
    private static final NodeId COUNTED = NodeId.nodeId("osw-counted").unwrap();
    private static final NodeId UNCOUNTED = NodeId.nodeId("osw-uncounted").unwrap();
    private static final Artifact V1 = Artifact.artifact("org.example:osw:1.0.0").unwrap();
    private static final long ROW_TOKEN = 11L;

    @Test
    void observedActiveInstances_onlyMembershipCountedNodes_areHosts() {
        var store = storeWith(COUNTED, UNCOUNTED);

        assertThat(ObservedSourceWiring.observedActiveInstances(store, Option.some(fsmCounting(COUNTED)), seam(ROW_TOKEN)))
            .isEqualTo(Map.of(V1, Set.of(COUNTED)));
    }

    @Test
    void observedActiveInstances_rowTokenAgainstTheSeamsAdmittedToken_decidesStaleness() {
        var store = storeWith(COUNTED);

        assertThat(ObservedSourceWiring.observedActiveInstances(store, Option.some(fsmCounting(COUNTED)), seam(ROW_TOKEN + 1)))
            .isEmpty();
        assertThat(ObservedSourceWiring.observedActiveInstances(store, Option.some(fsmCounting(COUNTED)), seam(ROW_TOKEN)))
            .isEqualTo(Map.of(V1, Set.of(COUNTED)));
    }

    @Test
    void observedActiveInstances_noMembershipFsmYet_isEmpty() {
        assertThat(ObservedSourceWiring.observedActiveInstances(storeWith(COUNTED), Option.none(), seam(ROW_TOKEN))).isEmpty();
    }

    private static AdmissionSeam seam(long token) {
        return AdmissionSeam.admissionSeam(_ -> token, _ -> 1L);
    }

    private static MembershipFsm fsmCounting(NodeId counted) {
        var fsm = MembershipFsm.membershipFsm();

        fsm.onMemberDescriptor(NodeInfo.nodeInfo(counted,
                                                 NodeAddress.nodeAddress("localhost", 6000).unwrap(),
                                                 Map.of(NodeInfo.LABEL_ROLE, "worker")));
        fsm.onSwimHealthy(counted, 1);
        assertThat(fsm.isCountedMember(counted)).as("precondition: counted").isTrue();
        assertThat(fsm.isCountedMember(UNCOUNTED)).as("precondition: uncounted").isFalse();
        return fsm;
    }

    private static KVStore<AetherKey, AetherValue> storeWith(NodeId... hosts) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), serializer(), deserializer());

        for (var host : hosts) {
            store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<AetherKey, AetherValue>(new SliceObservedKey(host, V1),
                                                                                                                      row(SliceObservedState.ACTIVE,
                                                                                                                          ROW_TOKEN)))));
        }
        return store;
    }

    private static SliceObservedValue row(SliceObservedState state, long bootToken) {
        return new SliceObservedValue(state,
                                      1L,
                                      new Generation(1L, 1L),
                                      new ProcessIncarnation(1L, bootToken),
                                      Option.none(),
                                      false,
                                      0,
                                      List.of(),
                                      0L);
    }

    private static Serializer serializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer deserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
