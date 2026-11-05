// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// L-0 tripwires (spec `l-lifecycle-spec.md` section 7, L-0 (b)). Each ENABLED test pins what the engine does today and
/// names, in its `_pinsCurrentBehaviour` suffix, the PR that changes it; the `@Disabled` test beside it carries the
/// assertion that PR enables. When that PR lands it deletes the pin and enables the desired test, so a change of
/// behaviour is always a visible, named edit. The one pin without a disabled partner,
/// `triggerResync_onPaused_movesToSyncing_ruling_a20d1dbca5`, records ruling a20d1dbca5 and stays enabled forever.
class LifecycleTripwireTest {
    private LifecycleRig rig;

    @AfterEach
    void tearDown() {
        if (rig != null) {
            rig.stop();
        }
    }

    // ---- L-2: triggerResync on a gated, unauthorized engine ----

    /// A-04: a far-future Propose must not start a sync round on a gated engine that was never authorized.
    @Test
    void triggerResync_onGatedUnauthorizedEngine_sendsNoSyncRequest() {
        rig = LifecycleRig.rig(true).started();

        rig.proposeAt(LifecycleRig.FAR);

        assertThat(syncRequests()).isZero();
        assertThat(rig.mode()).isEqualTo("Stopped");
    }

    /// Control: the guard is the authorization, not the gate. Once authorized (no held notification), the same Propose syncs.
    @Test
    void triggerResync_onGatedAuthorizedEngine_stillSyncs() {
        rig = LifecycleRig.rig(true).started();
        rig.engine.authorizeActivation();
        rig.settle();

        rig.proposeAt(LifecycleRig.FAR);

        assertThat(rig.mode()).isEqualTo("Syncing");
        assertThat(syncRequests()).isGreaterThanOrEqualTo(1);
    }

    /// Ruling a20d1dbca5: a far-future Propose resyncs a Paused engine. Stays enabled forever.
    @Test
    void triggerResync_onPaused_movesToSyncing_ruling_a20d1dbca5() {
        rig = LifecycleRig.rig(false).started();
        rig.active();
        rig.emptyAnswers();
        rig.passive();
        assertThat(rig.mode()).as("control: quorum loss paused the engine").isEqualTo("Paused");
        rig.mark();

        rig.proposeAt(LifecycleRig.FAR);

        assertThat(rig.mode()).isEqualTo("Syncing");
        assertThat(syncRequests()).isGreaterThanOrEqualTo(1);
    }

    // ---- L-3: Paused remembers where it was paused from (#2287) ----

    @Test
    void pausedDuringSyncing_thenQuorumActive_goesIdleWithoutParticipationRecord_pinsCurrentBehaviour() {
        rig = LifecycleRig.rig(false).started();
        rig.active();
        rig.passive();
        assertThat(rig.mode()).as("control: paused while Syncing").isEqualTo("Paused");

        rig.active();

        assertThat(rig.mode()).as("today the resume is blind to its origin").isEqualTo("Idle");
        assertThat(rig.marker.recordCalls.get()).as("no participation was recorded before serving (#2287)").isZero();
        assertThat(rig.startPromise.isResolved()).as("start() never resolves on this path").isFalse();
    }

    @Test
    @Disabled("L-3 enables this and deletes the _pinsCurrentBehaviour tripwire above")
    void pausedWhileSyncing_quorumActive_resyncsAndRecordsParticipationBeforeIdle() {
        rig = LifecycleRig.rig(false).started();
        rig.active();
        rig.passive();

        rig.active();

        assertThat(rig.mode()).isEqualTo("Syncing");
        assertThat(rig.marker.recordCalls.get()).isZero();
        rig.emptyAnswers();
        assertThat(rig.mode()).isEqualTo("Idle");
        assertThat(rig.marker.recordCalls.get()).isEqualTo(1);
        assertThat(rig.startPromise.isResolved()).isTrue();
    }

    // ---- L-4: an undecodable responder snapshot ----

    /// Enabled by L-4 (#2316). The refusal is raised through the operator-warning route, not as an engine event (CTO design
    /// for L-4), so the original `rig.events` contains "RestoreRefused" is asserted as a published
    /// `consensus-restore-refused` instead.
    @Test
    void undecodableResponderSnapshot_threeRounds_surfacesTypedFailureInStatus() {
        var warnings = new CopyOnWriteArrayList<OperatorWarningCode>();

        rig = LifecycleRig.rig(false).started();
        rig.engine.useOperatorWarningSink(OperatorWarningSink.handingOffTo(warning -> warnings.add(warning.code())));
        rig.machine.undecodable = true;
        rig.active();

        for (var round = 0; round < 3; round++) {
            rig.snapshotAnswers(10);
            rig.tick();
        }

        assertThat(rig.engine.voterReconfigurationStatus().failure()).isNotEmpty();
        assertThat(publishedWithin(warnings, OperatorWarningCode.CONSENSUS_RESTORE_REFUSED)).isTrue();
    }

    /// The sink hands warnings off to another thread: wait for the code, bounded.
    private static boolean publishedWithin(List<OperatorWarningCode> warnings, OperatorWarningCode code) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while (!warnings.contains(code) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        return warnings.contains(code);
    }

    // ---- L-6: the table rows R21 and R22 of an Observing engine (A3) ----

    @Test
    void authorizeActivation_withHeldNotification_onObserving_restartsSync_pinsCurrentBehaviour() {
        rig = LifecycleRig.rig(true).started();
        rig.engine.authorizeObservation();
        rig.active();
        rig.emptyAnswers();
        assertThat(rig.mode()).as("control: an authorized observer settled in Observing").isEqualTo("Observing");
        rig.holdQuorumNotification();
        rig.mark();

        rig.engine.authorizeActivation();
        rig.settle();

        assertThat(rig.mode()).as("R22: a held notification restarts sync from ANY mode, Observing included").isEqualTo("Syncing");
        assertThat(syncRequests()).isGreaterThanOrEqualTo(1);
    }

    /// R21 and R22 are mutually exclusive: with no held notification the same authorization promotes straight to Idle.
    @Test
    void authorizeActivation_withNoHeldNotification_onObserving_promotesWithoutSync() {
        rig = LifecycleRig.rig(true).started();
        rig.engine.authorizeObservation();
        rig.active();
        rig.emptyAnswers();
        rig.mark();
        var before = syncRequests();

        rig.engine.authorizeActivation();
        rig.settle();

        assertThat(rig.mode()).isEqualTo("Idle");
        assertThat(syncRequests()).isEqualTo(before);
    }

    private long syncRequests() {
        return rig.network.getMessages().stream().filter(SyncRequest.class::isInstance).count();
    }
}
