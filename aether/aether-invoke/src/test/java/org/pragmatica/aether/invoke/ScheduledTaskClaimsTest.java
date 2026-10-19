// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskClaimValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.VersionFenced;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The durable claim of a SINGLE-mode task (#1155), driven directly with a controllable clock.
class ScheduledTaskClaimsTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:scheduled-slice:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("cleanup").unwrap();
    private static final ScheduledTask SINGLE = new ScheduledTask("s", ARTIFACT, METHOD, A, "1s", "", ExecutionMode.SINGLE, false);
    private static final ScheduledTask ALL = new ScheduledTask("s", ARTIFACT, METHOD, A, "1s", "", ExecutionMode.ALL, false);
    private static final ScheduledTaskClaimKey KEY = ScheduledTaskClaimKey.scheduledTaskClaimKey("s", ARTIFACT, METHOD);
    /// Stale after max(1 s, 2 s + 1 s) = 3 s.
    private static final TimeSpan BOUND = TimeSpan.timeSpan(2_000).millis();

    private final ScheduledClaimStore store = ScheduledClaimStore.inMemory();
    private final AtomicLong now = new AtomicLong(10_000);

    private ScheduledTaskClaims claimsOf(NodeId node, ScheduledClaimStore over) {
        return new ScheduledTaskClaims(over, node, new ScheduledTaskManagerTest.TestLeaderManager(node), BOUND, now::get);
    }

    private static ScheduledTaskClaims.Ticket ticketOf(ScheduledTaskClaims claims) {
        return claims.acquire(SINGLE).await().unwrap().unwrap();
    }

    @Test
    void claimRow_isVersionFenced_andItsKeyRoundTrips() {
        var value = ScheduledTaskClaimValue.claimed(Option.none(), A, 3, "t", 1);

        assertThat(value).isInstanceOf(VersionFenced.class);
        assertThat(value.released().fenceVersion()).isEqualTo(value.fenceVersion() + 1);
        assertThat(ScheduledTaskClaimKey.scheduledTaskClaimKey(KEY.asString()).unwrap()).isEqualTo(KEY);
        assertThat(ScheduledTaskClaimKey.scheduledTaskClaimKey(ScheduledTaskKey.scheduledTaskKey("s", ARTIFACT, METHOD).asString()).isFailure()).isTrue();
    }

    @Test
    void acquire_allMode_takesNoClaim() {
        var ticket = claimsOf(A, store).acquire(ALL).await().unwrap();

        assertThat(ticket.isPresent()).isTrue();
        assertThat(store.read(KEY).isPresent()).isFalse();
    }

    @Test
    void acquire_whileAnotherNodeHoldsAFreshClaim_isRefused() {
        ticketOf(claimsOf(A, store));
        now.addAndGet(2_900);

        assertThat(claimsOf(B, store).acquire(SINGLE).await().unwrap().isPresent()).isFalse();
    }

    @Test
    void acquire_afterTheClaimWentStale_overridesIt_andTheOldHoldersReleaseIsRefused() {
        var oldTicket = ticketOf(claimsOf(A, store));

        now.addAndGet(3_000);
        var newTicket = ticketOf(claimsOf(B, store));

        assertThat(oldTicket.release().await().unwrap()).as("the successor was written by B: A's release is no longer the successor").isFalse();
        assertThat(store.read(KEY).unwrap().holder()).isEqualTo(B);
        assertThat(newTicket.release().await().unwrap()).isTrue();
        assertThat(store.read(KEY).unwrap().held()).isFalse();
    }

    @Test
    void acquire_afterARelease_takesTheSuccessor() {
        var first = ticketOf(claimsOf(A, store));

        assertThat(first.release().await().unwrap()).isTrue();
        ticketOf(claimsOf(B, store));

        assertThat(store.read(KEY).unwrap().version()).isEqualTo(3);
    }

    @Test
    void acquire_whenTheClaimWriteFails_isRefused_andDoesNotFire() {
        ScheduledClaimStore failing = new ScheduledClaimStore() {
            @Override
            public Option<ScheduledTaskClaimValue> read(ScheduledTaskClaimKey key) {
                return store.read(key);
            }

            @Override
            public Promise<Unit> write(KVCommand<AetherKey> command) {
                return Causes.cause("consensus unavailable").promise();
            }
        };

        assertThat(claimsOf(A, failing).acquire(SINGLE).await().unwrap().isPresent()).isFalse();
    }

    @Test
    void acquire_overAnOrphanOfItsOwnFailedRelease_takesItAtOnce_butNotAnotherNodesClaim() {
        var failRelease = new AtomicBoolean(false);
        ScheduledClaimStore flaky = new ScheduledClaimStore() {
            @Override
            public Option<ScheduledTaskClaimValue> read(ScheduledTaskClaimKey key) {
                return store.read(key);
            }

            @Override
            public Promise<Unit> write(KVCommand<AetherKey> command) {
                return failRelease.get()
                       ? Causes.cause("consensus unavailable").promise()
                       : store.write(command);
            }
        };
        var a = claimsOf(A, flaky);
        var ticket = ticketOf(a);

        failRelease.set(true);
        assertThat(ticket.release().await().unwrap()).isFalse();
        failRelease.set(false);

        assertThat(claimsOf(B, flaky).acquire(SINGLE).await().unwrap().isPresent()).as("another node respects the held claim").isFalse();
        assertThat(a.acquire(SINGLE).await().unwrap().isPresent()).as("the process that took it knows it is not running").isTrue();
    }

    @Test
    void forget_removesTheRow() {
        ticketOf(claimsOf(A, store));

        claimsOf(A, store).forget(ScheduledTaskKey.scheduledTaskKey("s", ARTIFACT, METHOD)).await();

        assertThat(store.read(KEY).isPresent()).isFalse();
    }
}
