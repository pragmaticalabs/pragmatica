// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.fsm;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import static org.assertj.core.api.Assertions.assertThat;

/// #1612: an HTTP listener's `stop()` can now fail (a timed-out close or termination). Stopping both listeners
/// must wait for BOTH and report the first failure. The previous `h1Stop.flatMap(_ -> h3Stop)` returned h1's
/// failure at once, while h3 was still stopping.
class AppHttpContextStopTest {
    private static final Cause H1_TIMED_OUT = Causes.cause("h1 close timed out");

    @Test
    void stopServersAsync_h1Fails_waitsForH3_thenReportsTheH1Failure() {
        var context = context();
        var h3Stopped = Promise.<Unit> promise();
        var stopped = context.stopServersAsync(Option.some(server(H1_TIMED_OUT.promise())),
                                               Option.some(server(h3Stopped)));

        assertThat(stopped.isResolved()).as("must not resolve while h3 is still stopping").isFalse();

        h3Stopped.succeed(Unit.unit());

        var outcome = stopped.await();

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(H1_TIMED_OUT));
    }

    @Test
    void stopServersAsync_bothSucceed_succeeds() {
        var stopped = context().stopServersAsync(Option.some(server(Promise.success(Unit.unit()))),
                                                 Option.some(server(Promise.success(Unit.unit()))));

        assertThat(stopped.await().isSuccess()).isTrue();
    }

    private static AppHttpContext context() {
        var holder = new AtomicReference<AppHttpContext>();

        FsmTestHarness.<AppHttpState, ClusterFsmEvent> harness("app-http-stop-test", fsm -> stoppedState(fsm, holder));

        return holder.get();
    }

    private static AppHttpState stoppedState(Fsm<AppHttpState, ClusterFsmEvent> fsm, AtomicReference<AppHttpContext> holder) {
        var context = new AppHttpContext(fsm);

        holder.set(context);

        return context.stopped();
    }

    private static HttpServer server(Promise<Unit> stop) {
        return new HttpServer() {
            @Override
            public int port() {
                return 0;
            }

            @Override
            public Promise<Unit> stop() {
                return stop;
            }
        };
    }
}
