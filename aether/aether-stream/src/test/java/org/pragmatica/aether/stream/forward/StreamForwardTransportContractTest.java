// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #2583 P1: what [StreamForwardTransport] itself promises, apart from any network. The 3-arg `send` defaults to the 2-arg
/// one and hands its outcome on; `NOOP` accepts every frame (`Sent`), never refuses one.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StreamForwardTransportContractTest {
    private static final NodeId TARGET = new NodeId("target");
    private static final TimeSpan WAIT = TimeSpan.timeSpan(1).seconds();
    private static final StreamForwardMessage MESSAGE = StreamForwardMessage.ReadForward.readForward(TARGET, "c-1", "s", 0, 0L, 1, false, true);

    @Test
    void send_withCallerWaitDefault_delegatesToThePlainSend_andReturnsItsOutcome() {
        var plainSends = new AtomicInteger();
        StreamForwardTransport refusing = (target, _) -> refuse(plainSends, target);

        var outcome = refusing.send(TARGET, MESSAGE, WAIT).await(WAIT);

        assertThat(outcome.unwrap()).isEqualTo(new WriteOutcome.BackpressureRefused(TARGET));
        assertThat(plainSends.get()).as("the default reached the plain send exactly once").isEqualTo(1);
    }

    @Test
    void noop_reportsSent_forBothOverloads() {
        assertThat(StreamForwardTransport.NOOP.send(TARGET, MESSAGE).await(WAIT).unwrap()).isEqualTo(new WriteOutcome.Sent(TARGET));
        assertThat(StreamForwardTransport.NOOP.send(TARGET, MESSAGE, WAIT).await(WAIT).unwrap()).isEqualTo(new WriteOutcome.Sent(TARGET));
    }

    private static Promise<WriteOutcome> refuse(AtomicInteger sends, NodeId target) {
        sends.incrementAndGet();

        return Promise.success(new WriteOutcome.BackpressureRefused(target));
    }
}
