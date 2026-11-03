// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.pragmatica.aether.stream.forward.StreamForwardMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/// #2574 wiring: the node's stream forward transport reports the network's tracked-write outcome, with the caller's wait as
/// the offline TTL. Without it the transport reports `Sent` for every frame, the forward client never sees a FORWARD-lane
/// refusal, and a refused read waits out its timeout again (the #2574 mechanism). Red when `sendOutcome` is not overridden
/// or does not pass the wait.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StreamAssemblyForwardTransportTest {
    private static final NodeId TARGET = new NodeId("target");
    private static final TimeSpan WAIT = TimeSpan.timeSpan(1500).millis();

    @Test
    void sendOutcome_reportsTheNetworksTrackedWriteOutcome_withTheCallersWait() {
        var network = Mockito.mock(ClusterNetwork.class);
        var message = StreamForwardMessage.ReadForward.readForward(TARGET, "c-1", "s", 0, 0L, 1, false, true);

        Mockito.when(network.sendOutcome(eq(TARGET), any(StreamForwardMessage.class), eq(WAIT)))
               .thenReturn(Promise.success(new WriteOutcome.BackpressureRefused(TARGET)));

        var outcome = StreamAssembly.createStreamForwardTransport(network)
                                    .sendOutcome(TARGET, message, WAIT)
                                    .await(TimeSpan.timeSpan(5).seconds());

        assertThat(outcome.unwrap()).isEqualTo(new WriteOutcome.BackpressureRefused(TARGET));
        Mockito.verify(network).sendOutcome(TARGET, message, WAIT);
    }
}
