// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import io.netty.buffer.ByteBuf;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.node.AssemblySource;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.NodeLease;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.SharedScheduler;
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
    private volatile boolean autoCommit;

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

    /// Design §2.1 / §8 (NEW-3) through the assembly's wiring: after a store reset, an OLDER row naming this node re-applied (what a
    /// restore of an older snapshot delivers) is refused, and so is a renewal SUBMITTED before the reset and delivered after it;
    /// only a renewal submitted after the reset validates the lease again. "Refused" = the lease stays invalid, so every owner
    /// check (admission, `ownerAdvance`, the ack continuation, the announcement) refuses.
    @Test
    void nodeLease_afterAReset_anOlderRowAndARenewalSubmittedBeforeIt_areRefused_aRenewalSubmittedAfterValidates() {
        var lease = wiredLease();

        lease.renew();
        var olderRow = List.copyOf(submitted);
        commitSubmitted();
        await().atMost(Duration.ofSeconds(5)).until(lease::valid);
        lease.renew();
        var submittedBeforeReset = List.copyOf(submitted);
        submitted.clear();

        store.reset();
        assertThat(lease.valid()).as("control: the reset revoked").isFalse();
        commit(olderRow);
        assertThat(lease.valid()).as("an older row naming this node, re-applied after the reset, is refused").isFalse();
        commit(submittedBeforeReset);
        assertThat(lease.valid()).as("a renewal submitted before the reset and delivered after it does not clear").isFalse();
        lease.renew();
        commitSubmitted();
        await().atMost(Duration.ofSeconds(5)).until(lease::valid);
    }

    /// Liveness (design §2.4): with the renewal running at its configured period `R` and each renewal delivered at once, a lease
    /// revoked by a store reset is valid again within `R` (plus a delivery margin).
    @Test
    void nodeLease_revokedByAReset_isValidAgainWithinOneRenewalPeriod() {
        var renewal = StreamingConfig.streamingConfig()
                                     .ownerLeaseRenewal();
        var lease = wiredLease();

        autoCommit = true;
        var renewing = SharedScheduler.scheduleAtFixedRate(lease::renew, renewal);

        try {
            await().atMost(Duration.ofSeconds(5)).until(lease::valid);
            store.reset();
            var revokedAt = System.nanoTime();

            assertThat(lease.valid()).as("control: the reset revoked").isFalse();
            await().atMost(Duration.ofNanos(renewal.nanos() + 500_000_000L)).pollInterval(Duration.ofMillis(5)).until(lease::valid);
            assertThat(System.nanoTime() - revokedAt).isLessThanOrEqualTo(renewal.nanos() + 500_000_000L);
        } finally {
            renewing.cancel(false);
        }
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

    /// The assembly hands the SAME lease to CF >= 2 owner admission (with the manager's confirmation factor) and to the partition
    /// manager, and schedules its renewal. Pinned by source text, like `thePeerLineageEnd_isWiredToTheBackfillMemory`
    /// [unverified: wiring pinned by text only]. Mutation: any of the three lines changed or removed -> RED.
    @Test
    void assembly_wiresTheLeaseIntoOwnerAdmission_andThePartitionManager_andSchedulesItsRenewal() {
        var source = AssemblySource.stripped();

        assertThat(source).containsOnlyOnce("hlcClock),nodeLease,streamPartitionManager::confirmationFactorFor);");
        assertThat(source).containsOnlyOnce("streamPartitionManager.ownerLease(nodeLease);");
        assertThat(source).containsOnlyOnce("streamPartitionManager.ownerActivated(ownerActivation::isActivated);");
        assertThat(source).containsOnlyOnce("nodeLease.onValidityRegained(streamPartitionManager::onLeaseRegained);");
        assertThat(source).containsOnlyOnce("defer.accept(()->SharedScheduler.scheduleAtFixedRate(nodeLease::renew,streamingConfig.ownerLeaseRenewal()));");
    }

    /// #2981 step 2 (S11): the partition manager's record source reads lock-free, so it answers while another thread holds the store
    /// monitor (as the apply thread does for a whole batch). Mutation: the source back to the synchronized `getTyped` -> RED (it waits
    /// for the monitor past the deadline).
    @Test
    void committedOwnershipRecords_whileAnotherThreadHoldsTheStoreMonitor_answersWithoutWaiting() throws Exception {
        var source = StreamAssembly.committedOwnershipRecords(store);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = Thread.ofVirtual().start(() -> holdMonitor(held, release));

        try {
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var read = CompletableFuture.supplyAsync(() -> source.committed("orders", 0));

            assertThat(read.get(2, TimeUnit.SECONDS).isEmpty()).isTrue();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    private void holdMonitor(CountDownLatch held, CountDownLatch release) {
        synchronized (store) {
            held.countDown();
            awaitQuietly(release);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        if (autoCommit) {
            commit(commands);
            return Promise.success(List.of());
        }
        submitted.addAll(commands);

        return Promise.success(List.of());
    }

    private void commitSubmitted() {
        commit(List.copyOf(submitted));
        submitted.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private synchronized void commit(List<KVCommand<AetherKey>> commands) {
        var raw = (KVStore) store;

        raw.processCommitted(raw.createBatch((List) commands), ++slot);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(MessageRouter.Entry<?> entry) {
        entry.entries().forEach(route -> router.addRoute((Class) route.first(), (Consumer) route.last()));
    }
}
