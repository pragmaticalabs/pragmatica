// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.config.AlertConfig.EventConfig;
import org.pragmatica.aether.config.AlertConfig.WebhookConfig;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.OwnerLifetime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3754: the webhook retry is scheduled on the forwarder's lifetime, and `close()` ends it. Each test drives the production
/// order (arm a send or a retry, then `close()`, then let the late event happen) over a fake seam and a scripted client, so
/// there is no socket, thread or clock.
///
/// - (a) a retry armed before the close never posts after it (the wrapper is fired by hand, past the cancel);
/// - (b) a send in flight at the close ends without a WARN, a retry or a hang: its `forward` promise resolves, which the
///   unchecked path (a retry onto an ended lifetime) would leave unresolved;
/// - (c) a `forward` waiting on a retry the close cancelled does not dangle: the close resolves it as a failure naming the stop.
class AlertForwarderStopTest {
    private final FakeOwnerSeam seam = new FakeOwnerSeam();
    private final ScriptedHttp http = new ScriptedHttp();

    @Test
    void close_endsARetryArmedBefore_soItNeverPostsAfterTheClose() {
        http.answers.add(Causes.cause("connection refused").promise());
        var forwarder = forwarderWithRetries(2);

        forwarder.forward(AlertEvent.AlertResolved.resolved("a-1", "test"));

        assertThat(seam.enqueued()).as("control: the failed send armed a retry").isEqualTo(1);
        forwarder.close();
        seam.fire(0);

        assertThat(http.sends).as("the first POST only; the retry armed before the close did not run").isEqualTo(1);
    }

    @Test
    void close_duringASend_resolvesTheForwardWithoutARetry() {
        var inFlight = Promise.<HttpResult<String>> promise();

        http.answers.add(inFlight);
        var forwarder = forwarderWithRetries(2);
        var forwarded = forwarder.forward(AlertEvent.AlertResolved.resolved("a-2", "test"));

        forwarder.close();
        inFlight.resolve(Causes.cause("connection reset").result());

        assertThat(forwarded.isResolved()).as("the send ended on the stopped forwarder instead of retrying into it").isTrue();
        assertThat(isStopped(forwarded)).isTrue();
        assertThat(seam.enqueued()).isZero();
        assertThat(http.sends).isEqualTo(1);
    }

    @Test
    void close_resolvesTheForwardOfACancelledRetry_asStopped() {
        http.answers.add(Causes.cause("connection refused").promise());
        var forwarder = forwarderWithRetries(2);
        var forwarded = forwarder.forward(AlertEvent.AlertResolved.resolved("a-4", "test"));

        assertThat(forwarded.isResolved()).as("control: the retry is armed and the forward waits on it").isFalse();
        forwarder.close();

        assertThat(forwarded.isResolved()).as("the close resolves the forward it cancelled the retry of").isTrue();
        assertThat(isStopped(forwarded)).as("as a failure naming the stop").isTrue();
        assertThat(seam.enqueued()).isEqualTo(1);
    }

    /// Control for the two tests above: the same failure without a close arms a retry and leaves the forward pending, so the
    /// resolved promise there is the close's doing.
    @Test
    void send_failingWithoutAClose_armsARetryAndStaysPending() {
        var inFlight = Promise.<HttpResult<String>> promise();

        http.answers.add(inFlight);
        var forwarded = forwarderWithRetries(2).forward(AlertEvent.AlertResolved.resolved("a-3", "test"));

        inFlight.resolve(Causes.cause("connection reset").result());

        assertThat(forwarded.isResolved()).isFalse();
        assertThat(seam.enqueued()).isEqualTo(1);
    }

    private static boolean isStopped(Promise<?> forwarded) {
        return forwarded.await(timeSpan(1).seconds())
                        .fold(cause -> cause instanceof AlertForwarder.AlertForwarderError.Stopped, _ -> false);
    }

    private AlertForwarder forwarderWithRetries(int retries) {
        var webhook = WebhookConfig.webhookConfig(true, List.of("http://127.0.0.1:1/hook"), retries, timeSpan(5).seconds()).unwrap();
        var config = AlertConfig.alertConfig(true, webhook, EventConfig.eventConfig(true).unwrap(), 0.05).unwrap();

        return AlertForwarder.alertForwarder(config, OwnerLifetime.ownerLifetime(seam), Option.some(http));
    }

    /// Answers each send with the next scripted promise and counts the sends.
    private static final class ScriptedHttp implements HttpOperations {
        private final List<Promise<HttpResult<String>>> answers = new ArrayList<>();
        private int sends;

        @Override
        public Promise<HttpResult<String>> sendString(HttpRequest request) {
            sends++;

            return answers.removeFirst();
        }

        @Override
        public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
            throw new AssertionError("the forwarder sends strings");
        }
    }
}
