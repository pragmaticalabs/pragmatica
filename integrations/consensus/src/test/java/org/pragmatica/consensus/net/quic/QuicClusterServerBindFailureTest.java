/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.net.quic;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #3635 — a server that created its own event-loop group releases it when its bind fails, and never touches a
/// group it was handed. Invariant: a client-owned group is released on every exit path of its owner (close, failed
/// bind, replacement); a supplied shared group is never released.
@Timeout(60)
class QuicClusterServerBindFailureTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(30).seconds();

    /// The group's state is read AT the report, not after it: a dependent step (`withFailure`) runs synchronously inside the
    /// resolution of the start promise, so it sees the group as it was when the failure was reported, whatever the
    /// release's quiet period. Mutations that redden it: drop `releaseOwnedGroupAfterFailedBind` from `handleBind`'s failure
    /// branch; fail the promise first and release after (the release then has not even begun at the report).
    @Test
    void failedBindOnOccupiedPort_releasesTheOwnedGroup_beforeReportingTheFailure() throws Exception {
        try (var holder = new DatagramSocket(0)) {
            var port = holder.getLocalPort();
            var server = serverOn(port, Option.empty());
            var terminatedAtReport = new AtomicReference<Boolean>();
            var reportedOn = new AtomicReference<Thread>();

            var started = server.start(port)
                                .withFailure(_ -> recordReport(server, terminatedAtReport, reportedOn))
                                .await(AWAIT);

            assertThat(started.isFailure()).as("control: the start on an occupied UDP port must fail").isTrue();
            started.onFailure(cause -> assertThat(cause).isInstanceOf(QuicTransportError.BindFailed.class));
            assertThat(reportedOn.get()).as("control: the report was observed as it resolved, not on the thread that attached the observer later")
                                        .isNotNull()
                                        .isNotSameAs(Thread.currentThread());

            var group = ((QuicClusterServerInstance) server).eventLoopGroupForTest().or((EventLoopGroup) null);

            assertThat(group).as("control: the failed start did create an owned group").isNotNull();
            assertThat(terminatedAtReport.get()).as("the owned group is terminated at the moment the failure is reported").isTrue();
        }
    }

    private static void recordReport(QuicClusterServer server, AtomicReference<Boolean> terminatedAtReport, AtomicReference<Thread> reportedOn) {
        terminatedAtReport.set(((QuicClusterServerInstance) server).eventLoopGroupForTest().map(EventLoopGroup::isTerminated).or(false));
        reportedOn.set(Thread.currentThread());
    }

    /// Control for the arm above: with a supplied group the same failure leaves the group running.
    @Test
    void failedBindOnOccupiedPort_neverReleasesASuppliedGroup() throws Exception {
        var shared = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        try (var holder = new DatagramSocket(0)) {
            var port = holder.getLocalPort();
            var server = serverOn(port, Option.some(shared));

            assertThat(server.start(port).await(AWAIT).isFailure()).as("control: the start must fail").isTrue();
            assertThat(shared.isShuttingDown()).as("a supplied group is never released by its user").isFalse();
        } finally {
            shared.shutdownGracefully(0, 5, TimeUnit.SECONDS).await(AWAIT.millis(), TimeUnit.MILLISECONDS);
        }
    }

    /// stop() after the failed start (the owner's close path) stays a success: the release is idempotent.
    @Test
    void stopAfterFailedBind_succeeds() throws Exception {
        try (var holder = new DatagramSocket(0)) {
            var port = holder.getLocalPort();
            var server = serverOn(port, Option.empty());

            server.start(port).await(AWAIT);

            assertThat(server.stop().await(AWAIT).isSuccess()).isTrue();
        }
    }

    private static QuicClusterServer serverOn(int port, Option<EventLoopGroup> group) throws SocketException {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();

        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);

        var codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.copyOf(all));

        return QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("bind-failure"))
                              .fold(cause -> fail("Server SSL context creation failed: " + cause.message()),
                                    ssl -> QuicClusterServer.quicClusterServer(NodeId.randomNodeId(),
                                                                               new NodeAddress("127.0.0.1", port),
                                                                               Map.of(),
                                                                               codec,
                                                                               codec,
                                                                               QuicTransportMetrics.quicTransportMetrics(),
                                                                               ssl,
                                                                               group,
                                                                               (_, _, _) -> {},
                                                                               (_, _) -> {}));
    }
}
