// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import io.netty.buffer.ByteBuf;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.NodeLease;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #2981 step 2 (design §2.1, r7 S2, know 018945dae1): the node lease as [StreamAssembly#nodeLease] wires it. Its own renewal reaches it
/// through the ordered notification route, a store reset and a replay start revoke it, and its events raise the operator codes with the
/// node as subject.
class StreamAssemblyNodeLeaseTest {
    private static final NodeId SELF = NodeId.nodeId("lease-self").unwrap();

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(router, new Serializer() {
        @Override
        public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override
        public <T> T read(ByteBuf buffer) {
            return null;
        }
    });
    private final List<KVCommand<AetherKey>> submitted = new CopyOnWriteArrayList<>();
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private long slot;

    /// Mutation: the `NodeLeaseKey` route not added to `entries` -> RED (the committed renewal never validates the lease).
    @Test
    void nodeLease_ownRenewalCommitted_validatesTheLeaseThroughTheNotificationRoute() {
        var lease = wiredLease();

        assertThat(lease.valid()).as("never renewed").isFalse();
        lease.renew();
        commitSubmitted();

        await().atMost(Duration.ofSeconds(5)).until(lease::valid);
    }

    /// Mutation: `kvStore.onReset(lease::revoke)` dropped -> RED (the reset leaves the lease valid).
    @Test
    void nodeLease_storeReset_revokesTheLease() {
        var lease = grantedLease();

        store.reset();

        assertThat(lease.valid()).isFalse();
    }

    /// Mutation: `kvStore.onReplayStart(_ -> lease.revoke())` dropped -> RED (the replay leaves the lease valid).
    @Test
    void nodeLease_replayStart_revokesTheLease() {
        var lease = grantedLease();

        store.replayNotifications();

        assertThat(lease.valid()).isFalse();
    }

    /// Each event raises its own code with this node as subject (one subject per node, so a lapse and its restoration pair). Mutation: any
    /// method raising another code, or another subject -> RED.
    @Test
    void leaseEvents_eachEventRaisesItsCode_withTheNodeAsSubject() {
        var events = StreamAssembly.leaseEvents(OperatorWarningSink.handingOffTo(raised::add), SELF);

        events.lapsed(NodeLease.LapseReason.EXPIRED, 1_000_000L);
        events.restored();
        events.clockRateDivergence(1L, 2L);
        events.clockRateNormal();

        await().atMost(Duration.ofSeconds(5)).until(() -> raised.size() == 4);
        assertThat(raised).extracting(OperatorWarning::code)
                          .containsExactly(OperatorWarningCode.STREAM_OWNER_LEASE_LAPSED,
                                           OperatorWarningCode.STREAM_OWNER_LEASE_RESTORED,
                                           OperatorWarningCode.NODE_CLOCK_RATE_DIVERGENCE,
                                           OperatorWarningCode.NODE_CLOCK_RATE_NORMAL);
        assertThat(raised).extracting(OperatorWarning::subject)
                          .containsOnly(SELF.id());
    }

    private NodeLease wiredLease() {
        var entries = new ArrayList<MessageRouter.Entry<?>>();
        var lease = StreamAssembly.nodeLease(SELF,
                                             StreamingConfig.streamingConfig(),
                                             store,
                                             this::submit,
                                             OperatorWarningSink.logOnly(),
                                             entries);

        entries.forEach(this::register);

        return lease;
    }

    private NodeLease grantedLease() {
        var lease = wiredLease();

        lease.renew();
        commitSubmitted();
        await().atMost(Duration.ofSeconds(5)).until(lease::valid);

        return lease;
    }

    private Promise<List<Object>> submit(List<KVCommand<AetherKey>> commands) {
        submitted.addAll(commands);

        return Promise.success(List.of());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitSubmitted() {
        var raw = (KVStore) store;

        raw.processCommitted(raw.createBatch((List) List.copyOf(submitted)), ++slot);
        submitted.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(MessageRouter.Entry<?> entry) {
        entry.entries().forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last()));
    }
}
