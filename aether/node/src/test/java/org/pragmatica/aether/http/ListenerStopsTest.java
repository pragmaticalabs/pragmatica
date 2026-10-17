// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// #1612: the two ways the management and app HTTP servers handle a listener pair whose stop can fail.
/// `ManagementServer.stop()`, `AppHttpContext.stopServersAsync` and both certificate rotations go through
/// these helpers.
class ListenerStopsTest {
    private static final Logger LOG = LoggerFactory.getLogger(ListenerStopsTest.class);
    private static final Cause H1_FAILED = Causes.cause("h1 close timed out");
    private static final Cause H3_FAILED = Causes.cause("h3 termination timed out");

    @Test
    void bothStopped_h1Fails_waitsForH3_thenReportsH1() {
        var h3Stop = Promise.<Unit> promise();
        var stopped = ListenerStops.bothStopped(H1_FAILED.promise(), h3Stop);

        assertThat(stopped.isResolved()).as("must not resolve while h3 is still stopping").isFalse();

        h3Stop.succeed(Unit.unit());

        var outcome = stopped.await();

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(H1_FAILED));
    }

    @Test
    void bothStopped_h1Succeeds_h3Fails_reportsH3() {
        var outcome = ListenerStops.bothStopped(Promise.unitPromise(), H3_FAILED.promise())
                                   .await();

        assertThat(outcome.isFailure()).as("an h3 failure is not dropped").isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(H3_FAILED));
    }

    @Test
    void bothStopped_bothSucceed_succeeds() {
        assertThat(ListenerStops.bothStopped(Promise.unitPromise(), Promise.unitPromise()).await().isSuccess()).isTrue();
    }

    /// A rotation whose stop failed still restarts: the failure is logged and recovered.
    @Test
    void stoppedForRestart_stopFails_succeedsSoTheRestartRuns() {
        var restarted = ListenerStops.stoppedForRestart(H1_FAILED.promise(), LOG, "test listeners")
                                     .map(_ -> "restarted")
                                     .await();

        assertThat(restarted.isSuccess()).isTrue();
        restarted.onSuccess(value -> assertThat(value).isEqualTo("restarted"));
    }
}
