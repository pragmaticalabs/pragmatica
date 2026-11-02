// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;

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

    @Test
    void triggerResync_onGatedUnauthorizedStopped_sendsSyncRequest_pinsCurrentBehaviour() {
        rig = LifecycleRig.rig(true).started();

        rig.proposeAt(LifecycleRig.FAR);

        assertThat(rig.mode()).as("control: the engine is gated and was never authorized, yet the far-future Propose moved it").isEqualTo("Syncing");
        assertThat(syncRequests()).as("today a gated unauthorized engine broadcasts a SyncRequest (A-04)").isGreaterThanOrEqualTo(1);
    }

    @Test
    @Disabled("L-2 enables this and deletes the _pinsCurrentBehaviour tripwire above")
    void triggerResync_onGatedUnauthorizedEngine_sendsNoSyncRequest() {
        rig = LifecycleRig.rig(true).started();

        rig.proposeAt(LifecycleRig.FAR);

        assertThat(syncRequests()).isZero();
        assertThat(rig.mode()).isEqualTo("Stopped");
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

    @Test
    void undecodableResponderSnapshot_repeatedRounds_staysSyncingWithEmptyStatus_pinsCurrentBehaviour() {
        rig = LifecycleRig.rig(false).started();
        rig.machine.undecodable = true;
        rig.active();

        for (var round = 0; round < 3; round++) {
            rig.snapshotAnswers(10);
            rig.tick();
        }

        assertThat(rig.mode()).as("fail-closed: the node never serves a state it could not read").isEqualTo("Syncing");
        assertThat(rig.machine.restores.get()).as("every round re-enters the same failing restore").isGreaterThanOrEqualTo(3);
        assertThat(rig.engine.voterReconfigurationStatus().failure()).as("today the status is silent about it").isEmpty();
        assertThat(rig.events).as("and no engine event names the refusal").doesNotContain("RestoreRefused");
    }

    @Test
    @Disabled("L-4 enables this and deletes the _pinsCurrentBehaviour tripwire above")
    void undecodableResponderSnapshot_threeRounds_surfacesTypedFailureInStatus() {
        rig = LifecycleRig.rig(false).started();
        rig.machine.undecodable = true;
        rig.active();

        for (var round = 0; round < 3; round++) {
            rig.snapshotAnswers(10);
            rig.tick();
        }

        assertThat(rig.engine.voterReconfigurationStatus().failure()).isNotEmpty();
        assertThat(rig.events).contains("RestoreRefused");
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
