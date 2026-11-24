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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.testing.PollUntil.pollUntil;

/// Start/stop lifecycle of [QuicClusterNetwork].
///
/// #1366 — `stop()` nulls `client`, and the dial path dereferenced the field twice: at the resolve step and in
/// the resolve-success continuation, which a stop can overtake. After a stop both paths threw an NPE (surfaced
/// by #1311's escape guard). Each is now a clean no-op that leaves no CONNECTING peer behind.
///
/// #1461 — `stop()` before `start()` was a silent no-op (its CAS on `isRunning` failed and nothing was recorded),
/// so the later start armed a server, a reconciler and a keepalive that nothing could stop. A stop is now
/// recorded and a stopped network stays stopped: the late start refuses with `NETWORK_STOPPED`.
@Timeout(60)
class QuicClusterNetworkLifecycleTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(20).seconds();
    private static final NodeId SELF = new NodeId("lifecycle-self");
    private static final NodeId PEER = new NodeId("lifecycle-peer");
    private static final Cause RELEASE_FAILED = Causes.cause("release failed (test)");

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    private final List<QuicClusterServer> peerServers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        peerServers.forEach(peer -> peer.stop().await(AWAIT));
    }

    /// #1366, resolve step. Mutation that reddens it: dereference the `client` field instead of the local.
    @Test
    void dialAfterStop_isANoOp_notAnNpe() {
        var network = started();

        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertThatCode(() -> network.dialForTests(peer(), true)).as("#1366: a dial after stop must not dereference null")
                                                               .doesNotThrowAnyException();
        assertThat(network.peerPhaseForTests(PEER)).as("nothing was dialed").isEqualTo(Option.empty());
    }

    /// #1366, resolve-success continuation: a resolve that completes after stop(). Mutation that reddens it:
    /// dereference the `client` field in `dialResolved`.
    @Test
    void resolveCompletingAfterStop_dialsNothing_andLeavesNoConnectingPeer() throws Exception {
        var network = started();

        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertThatCode(() -> network.dialResolvedForTests(peer(), InetAddress.getLoopbackAddress(), 1))
            .as("#1366: the continuation must not dereference null")
            .doesNotThrowAnyException();
        assertThat(network.peerPhaseForTests(PEER)).as("no peer is left CONNECTING by a dial that could not start").isEqualTo(Option.empty());
    }

    /// CONTROL — the same seams on a RUNNING network do dial: the peer goes CONNECTING. Without this, the empty
    /// phases above could mean the seams never reach the dial at all.
    @Test
    void resolveCompletingWhileRunning_beginsConnecting() {
        var network = started();

        network.dialResolvedForTests(peer(), InetAddress.getLoopbackAddress(), 1);

        assertThat(network.peerPhaseForTests(PEER)).as("control: a running network dials")
                                                   .isEqualTo(Option.some(PeerState.Phase.CONNECTING));
    }

    /// #1461. Mutation that reddens it: drop the `closed` check at the top of `startOnPort`.
    @Test
    void stopBeforeStart_theLateStartRefuses_andArmsNothing() {
        var network = network();
        var ready = new java.util.concurrent.atomic.AtomicBoolean();

        network.whenReady(() -> ready.set(true));
        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        var start = network.startOnPort(0).await(AWAIT);

        assertThat(start.isFailure()).as("#1461: a start after stop must refuse: %s", start).isTrue();
        start.onFailure(cause -> assertThat(cause).isEqualTo(QuicTransportError.General.NETWORK_STOPPED));
        assertThat(network.boundPort()).as("no server was bound").isEqualTo(Option.empty());
        assertThat(network.periodicTasksScheduledForTests()).as("no reconciler or keepalive was armed").isFalse();
        assertThat(ready.get()).as("the transport never reported ready").isFalse();
    }

    /// The same holds for a network that ran and was stopped: stopped stays stopped.
    @Test
    void stopAfterStart_thenStartAgain_refuses() {
        var network = started();

        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertThat(network.startOnPort(0).await(AWAIT).isFailure()).isTrue();
        assertThat(network.periodicTasksScheduledForTests()).isFalse();
    }

    /// CONTROL — a start with no prior stop binds and arms its periodic tasks, so the negatives above are real.
    @Test
    void startWithoutStop_bindsAndArmsItsPeriodicTasks() {
        var network = started();

        assertThat(network.boundPort().isPresent()).isTrue();
        assertThat(network.periodicTasksScheduledForTests()).isTrue();
    }

    /// A caller that awaited the start finds the transport-ready hooks already fired. The hook here is slow, so a hook fired by a
    /// handler that runs beside the start's dependents (not as one of them) is still running when the await returns.
    @Test
    void startedNetwork_awaitedStart_hasAlreadyFiredASlowReadyHook() {
        var network = network();
        var hookDone = new java.util.concurrent.atomic.AtomicBoolean();

        network.whenReady(() -> {
            java.util.concurrent.locks.LockSupport.parkNanos(150_000_000L);
            hookDone.set(true);
        });
        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));

        assertThat(hookDone.get()).as("the ready hook finished before the awaited start resolved").isTrue();
    }

    /// The start promise resolves on a Netty I/O thread, and the only production hook binds SWIM's UDP port and awaits it, so a hook
    /// must never run there. Fired as a plain dependent step it ran on the event loop (`FastThreadLocalThread`).
    @Test
    void readyHook_doesNotRunOnANettyIoThread() throws Exception {
        var network = network();
        var hookThread = new java.util.concurrent.atomic.AtomicReference<Thread>();

        network.whenReady(() -> hookThread.set(Thread.currentThread()));
        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));

        var thread = hookThread.get();

        assertThat(thread).as("the hook ran before the awaited start resolved").isNotNull();
        assertThat(thread instanceof io.netty.util.concurrent.FastThreadLocalThread || thread.getName().contains("EventLoop"))
            .as("hook ran on thread '%s' (virtual=%s, class=%s)", thread.getName(), thread.isVirtual(), thread.getClass().getName())
            .isFalse();
    }

    /// A failed start has released `isRunning` by the time its promise resolves, so a retry right after the failure binds. Reset by a
    /// handler beside the dependents, the retry was turned away as already running and bound nothing. Each trial fails a start for a
    /// held UDP port, then retries on a free one.
    @Test
    void failedStart_retryRightAfterTheFailure_isNotTurnedAwayAsAlreadyRunning() throws Exception {
        var turnedAway = 0;
        var trials = 20;
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("lifecycle-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("lifecycle-client")).fold(_ -> fail("client ssl"), ssl -> ssl);

        for (var trial = 0; trial < trials; trial++) {
            var network = network(serverSsl, clientSsl);
            var held = freeUdpPort();

            try (var holder = new java.net.DatagramSocket(held)) {
                assertThat(network.startOnPort(held).await(AWAIT).isFailure()).as("a start on a held port fails").isTrue();
            }

            network.startOnPort(0).await(AWAIT);
            if (network.boundPort().isEmpty()) {
                turnedAway++;
            }
        }

        assertThat(turnedAway).as("retries (of %d) turned away as already running right after a failed start", trials).isZero();
    }

    /// #3635, L2: a start that fails on a held UDP port releases the server's AND the client's owned group before it
    /// reports the failure. Mutation that reddens it: revert `onStartFailed` to log + `isRunning.set(false)` only.
    @Test
    void failedStart_releasesBothOwnedGroups_beforeReportingTheFailure() throws Exception {
        var network = network();
        var built = observeBuilt(network);

        try (var holder = new java.net.DatagramSocket(0)) {
            assertThat(network.startOnPort(holder.getLocalPort()).await(AWAIT).isFailure()).as("control: a start on a held port fails").isTrue();
        }

        assertThat(built).as("control: the failed attempt built a server and a client").hasSize(1);
        assertThat(built.getFirst().server().isTerminated()).as("the failed attempt's server group is terminated before the failure is reported").isTrue();
        assertTerminatesSoon(built.getFirst().client(), "the failed attempt's client group");
    }

    /// #3635, L2: the retry after the failure orphans nothing — the failed attempt's groups are gone, the retry's are live,
    /// and stop() releases the retry's. Mutation that reddens it: as above.
    @Test
    void retryAfterFailedStart_leavesOnlyTheRetrysGroupsLive_andStopReleasesThem() throws Exception {
        var network = network();
        var built = observeBuilt(network);

        try (var holder = new java.net.DatagramSocket(0)) {
            network.startOnPort(holder.getLocalPort()).await(AWAIT);
        }
        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("retry failed: " + cause.message()));

        assertThat(built).as("control: two attempts were built").hasSize(2);
        assertThat(built.get(0).server().isTerminated()).as("failed attempt's server group released").isTrue();
        assertTerminatesSoon(built.get(0).client(), "failed attempt's client group");
        assertThat(built.get(1).server().isShuttingDown() || built.get(1).client().isShuttingDown()).as("control: the retry's groups are live").isFalse();

        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertTerminatesSoon(built.get(1).server(), "stop releases the retry's server group");
        assertTerminatesSoon(built.get(1).client(), "stop releases the retry's client group");
    }

    /// #3635, L1: certificate rotation keeps ONE client — and so one owned group and one datagram-channel map — and only
    /// swaps the TLS context its later dials use. Each rotation moves to a new cluster secret, and a peer of that secret
    /// trusts only the new certificate, so its accepting the dial pins the DIAL, not just the field. Mutations that redden
    /// it: rebuild the client in `rebuildAndStart` (identity), drop `renewSslContext` from `stopAndRestartServer` (context),
    /// build the dial codec from a context captured at construction (dial).
    @Test
    void rotations_keepOneClientAndItsLiveGroup_andRenewItsDialContext() throws Exception {
        var network = started();
        var client = (QuicClusterClientInstance) network.clientForTests();
        var clientGroup = client.eventLoopGroupForTest();
        var firstPeer = new NodeId("rotation-peer-1");
        var firstPeerServer = peerServer(firstPeer, "rotation-secret-1");

        assertThat(dial(client, firstPeer, firstPeerServer).isFailure()).as("control: a peer of the next secret refuses the pre-rotation certificate").isTrue();

        for (var rotation = 1; rotation <= 3; rotation++) {
            var secret = "rotation-secret-" + rotation;
            var newClientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("rotated-client-" + rotation, secret)).fold(_ -> fail("client ssl"), ssl -> ssl);
            var newServerSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("rotated-server-" + rotation, secret)).fold(_ -> fail("server ssl"), ssl -> ssl);

            network.rotateCertificate(newServerSsl, newClientSsl).await(AWAIT).onFailure(cause -> fail("rotation failed: " + cause.message()));

            assertThat(network.clientForTests()).as("rotation %d keeps the same client", rotation).isSameAs(client);
            assertThat(client.sslContextForTest()).as("rotation %d: the next dial uses the new context", rotation).isSameAs(newClientSsl);
            assertThat(clientGroup.isShuttingDown()).as("rotation %d leaves the client's group running", rotation).isFalse();

            var peer = rotation == 1 ? firstPeer : new NodeId("rotation-peer-" + rotation);
            var peerServer = rotation == 1 ? firstPeerServer : peerServer(peer, secret);
            var redial = dial(client, peer, peerServer);

            assertThat(redial.isSuccess()).as("rotation %d: a peer that trusts only the new certificate accepts the dial: %s", rotation, redial).isTrue();
        }

        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertTerminatesSoon(clientGroup, "the one client's group, released by stop()");
    }

    /// #3635, control for the two rotation-racing-stop tests below: a rotation awaited, THEN stop(), reads "released" on
    /// the same instruments (no bound server, no client, the UDP port free again).
    @Test
    void control_rotationThenStop_releasesServerClientAndPort() throws Exception {
        var network = started();
        var port = network.boundPort().or(-1);

        network.rotateCertificate(serverSsl("control-rotated"), clientSsl("control-rotated")).await(AWAIT).onFailure(cause -> fail("rotation failed: " + cause.message()));
        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));

        assertThat(network.boundPort().isEmpty()).as("control: no bound server after stop").isTrue();
        assertThat(network.clientForTests()).as("control: no client after stop").isNull();
        assertThat(awaitPortFree(port)).as("control: UDP port %d free after stop", port).isTrue();
    }

    /// #3635, F1: stop() lands while the rotation is stopping the old server (Netty's 2 s quiet period), so the rebuild
    /// starts after the stop. It builds nothing and the rotation fails with `NETWORK_STOPPED`; before, it bound a new
    /// server and built a new client that no later stop() could release. Mutation that reddens it: drop the first
    /// `closed` check in `rebuildAndStart` (a second pair is built, even though the re-check releases it).
    @Test
    void stopDuringRotation_buildsNothing_andFailsTheRotation() throws Exception {
        var network = network();
        var built = observeBuilt(network);

        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));
        var port = network.boundPort().or(-1);

        var passedFirstCheck = new AtomicBoolean();

        network.beforeRebuildForTests(() -> passedFirstCheck.set(true));
        var rotation = network.rotateCertificate(serverSsl("stop-during-rotated"), clientSsl("stop-during-rotated"));
        network.stop().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));
        var outcome = rotation.await(AWAIT);

        assertThat(passedFirstCheck.get()).as("control: stop() landed before the first check, so the rebuild never got past it (the seam is run only after it)").isFalse();
        assertRotationReportsTheStop(outcome);
        assertThat(built).as("nothing is built after the stop: only the start's pair").hasSize(1);
        assertThat(network.boundPort().isEmpty()).as("no bound server after stop").isTrue();
        assertThat(network.clientForTests()).as("no client after stop").isNull();
        assertThat(awaitPortFree(port)).as("UDP port %d free after stop", port).isTrue();
    }

    /// #3635, F1: stop() overtakes the rebuild's first `closed` check and runs before `server`/`client` are assigned, so
    /// it releases only the old pair. The test reaches that window deterministically: the `beforeRebuildForTests` seam
    /// runs stop() between the check and the assignment. The re-check after the assignment releases what the rotation
    /// built: the new server never binds, the new client's group terminates, and the rotation fails with
    /// `NETWORK_STOPPED`. Mutation that reddens it: drop the re-check after the assignment.
    @Test
    void stopInsideTheRebuildWindow_releasesWhatTheRotationBuilt() throws Exception {
        var network = started();
        var built = observeBuilt(network);
        var racingStop = new AtomicReference<Promise<Unit>>();

        network.beforeRebuildForTests(() -> racingStop.set(network.stop()));
        var outcome = network.rotateCertificate(serverSsl("window-rotated"), clientSsl("window-rotated")).await(AWAIT);

        assertThat(racingStop.get()).as("control: the stop ran inside the rebuild window").isNotNull();
        racingStop.get().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));
        assertRotationReportsTheStop(outcome);
        assertThat(built).as("control: the rotation got past its first check and built a pair").hasSize(1);
        assertThat(built.getFirst().serverInstance().eventLoopGroupForTest().isEmpty()).as("the rotation's server never bound").isTrue();
        assertThat(network.boundPort().isEmpty()).as("no bound server after stop").isTrue();
        assertThat(network.clientForTests()).as("no client after stop").isNull();
        assertTerminatesSoon(built.getFirst().client(), "the client the rotation built after the stop");
    }

    /// #3635, LOW-2, rotation site. Close-chain rule: every release runs, the FIRST failure wins. The first failure is the
    /// stop the re-check observed, so a release that then fails does not displace it: the rotation still fails with
    /// `NETWORK_STOPPED`, and the release still ran (the client the rotation built terminates). Mutation that reddens it:
    /// propagate the release's failure instead of the stop (`flatMap` in `releaseAndReportStopped`).
    @Test
    void failedReleaseInTheRotationRecheck_stillReportsTheStop() throws Exception {
        var network = started();
        var built = observeBuilt(network);
        var racingStop = new AtomicReference<Promise<Unit>>();
        var failedReleases = new AtomicInteger();

        network.releaseTapForTests(failingRelease(failedReleases));
        network.beforeRebuildForTests(() -> racingStop.set(network.stop()));
        var outcome = network.rotateCertificate(serverSsl("failed-release-rotated"), clientSsl("failed-release-rotated")).await(AWAIT);

        assertThat(racingStop.get()).as("control: the stop ran inside the rebuild window").isNotNull();
        assertThat(failedReleases.get()).as("control: the re-check's release ran and failed").isEqualTo(1);
        assertRotationReportsTheStop(outcome);
        racingStop.get().await(AWAIT).onFailure(cause -> fail("stop failed: " + cause.message()));
        assertTerminatesSoon(built.getFirst().client(), "the client the rotation built after the stop, released despite the failing tap");
    }

    /// #3635, LOW-2, `startOnPort` site (#1461): the same rule, the same helper. The stop runs from the built-observer, which
    /// runs after the assignment and before the re-check. Mutation that reddens it: as above.
    @Test
    void failedReleaseInTheStartRecheck_stillReportsTheStop() throws Exception {
        var network = network();
        var racingStop = new AtomicReference<Promise<Unit>>();
        var failedReleases = new AtomicInteger();

        network.releaseTapForTests(failingRelease(failedReleases));
        network.builtObserverForTests((_, _) -> racingStop.set(network.stop()));
        var outcome = network.startOnPort(0).await(AWAIT);

        assertThat(racingStop.get()).as("control: the stop ran between the assignment and the re-check").isNotNull();
        assertThat(failedReleases.get()).as("control: the re-check's release ran and failed").isEqualTo(1);
        assertThat(outcome.isFailure()).as("the start fails: %s", outcome).isTrue();
        outcome.onFailure(cause -> assertThat(cause).as("the start reports the stop").isEqualTo(QuicTransportError.General.NETWORK_STOPPED));
        assertThat(network.boundPort().isEmpty()).as("no bound server after stop").isTrue();
    }

    /// Wraps the re-check's real release (it still runs) so that it reports a failure, counting each such report.
    private static UnaryOperator<Promise<Unit>> failingRelease(AtomicInteger failedReleases) {
        return release -> release.flatMap(_ -> countedReleaseFailure(failedReleases));
    }

    private static Promise<Unit> countedReleaseFailure(AtomicInteger failedReleases) {
        failedReleases.incrementAndGet();

        return RELEASE_FAILED.promise();
    }

    /// Netty's default shutdown has a 2 s quiet period, so a client group terminates shortly AFTER its close was initiated.
    private static void assertTerminatesSoon(io.netty.channel.EventLoopGroup group, String what) throws InterruptedException {
        assertThat(group.terminationFuture().await(AWAIT.millis(), java.util.concurrent.TimeUnit.MILLISECONDS)).as("%s terminates", what).isTrue();
    }

    /// The server and client one `startOnPort` built. The server's group exists only once it has started binding, so it is read late.
    private record Built(QuicClusterServerInstance serverInstance, QuicClusterClientInstance clientInstance) {
        io.netty.channel.EventLoopGroup server() {
            return serverInstance.eventLoopGroupForTest().or((io.netty.channel.EventLoopGroup) null);
        }

        io.netty.channel.EventLoopGroup client() {
            return clientInstance.eventLoopGroupForTest();
        }
    }

    private static List<Built> observeBuilt(QuicClusterNetwork network) {
        var built = new java.util.concurrent.CopyOnWriteArrayList<Built>();

        network.builtObserverForTests((server, client) -> built.add(new Built((QuicClusterServerInstance) server, (QuicClusterClientInstance) client)));

        return built;
    }

    private static void assertRotationReportsTheStop(Result<Unit> outcome) {
        assertThat(outcome.isFailure()).as("the rotation fails: %s", outcome).isTrue();
        outcome.onFailure(cause -> assertThat(cause).as("the rotation reports the stop").isEqualTo(QuicTransportError.General.NETWORK_STOPPED));
    }

    /// Polls until the UDP port can be bound again (8 s bound), so a released server reads as released, not as busy.
    private static boolean awaitPortFree(int port) {
        return pollUntil(() -> canBind(port), Boolean::booleanValue, Duration.ofSeconds(8)).met();
    }

    private static boolean canBind(int port) {
        try (var _ = new DatagramSocket(port)) {
            return true;
        } catch (SocketException busy) {
            return false;
        }
    }

    private static Result<QuicPeerConnection> dial(QuicClusterClient client, NodeId peerId, QuicClusterServer peerServer) {
        var port = peerServer.boundPort().fold(() -> fail("peer not bound"), p -> p);

        return client.connect(peerId, new InetSocketAddress("127.0.0.1", port)).await(AWAIT);
    }

    /// A standalone server under its own cluster secret: it accepts only a client certificate signed by that secret's CA.
    private QuicClusterServer peerServer(NodeId peerId, String secret) {
        var codec = LaneProbe.codec();
        var ssl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls(peerId.id(), secret)).fold(_ -> fail("peer ssl"), context -> context);
        var address = NodeAddress.nodeAddress("127.0.0.1", 19994).fold(_ -> fail("bad address"), a -> a);
        var peer = QuicClusterServer.quicClusterServer(peerId, address, Map.of(), codec, codec, QuicTransportMetrics.quicTransportMetrics(), ssl, Option.empty(), (_, _, _) -> {}, (_, _) -> {});

        peerServers.add(peer);
        peer.start(0).await(AWAIT).onFailure(cause -> fail("peer start failed: " + cause.message()));

        return peer;
    }

    private static io.netty.handler.codec.quic.QuicSslContext serverSsl(String name) {
        return QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("lifecycle-server-" + name)).fold(_ -> fail("server ssl"), ssl -> ssl);
    }

    private static io.netty.handler.codec.quic.QuicSslContext clientSsl(String name) {
        return QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("lifecycle-client-" + name)).fold(_ -> fail("client ssl"), ssl -> ssl);
    }

    private static int freeUdpPort() throws Exception {
        try (var socket = new java.net.DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private QuicClusterNetwork started() {
        var network = network();

        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));

        return network;
    }

    private QuicClusterNetwork network() {
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("lifecycle-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("lifecycle-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);

        return network(serverSsl, clientSsl);
    }

    private QuicClusterNetwork network(io.netty.handler.codec.quic.QuicSslContext serverSsl, io.netty.handler.codec.quic.QuicSslContext clientSsl) {
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 19995).fold(_ -> fail("bad address"), a -> a));
        var network = new QuicClusterNetwork(stubTopology(self), codec, codec, MessageRouter.mutable(), serverSsl, clientSsl);

        networks.add(network);

        return network;
    }

    private static NodeInfo peer() {
        return NodeInfo.nodeInfo(PEER, NodeAddress.nodeAddress("127.0.0.1", 1).fold(_ -> fail("bad address"), a -> a));
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return new TopologyObserver() {
            @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 3;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return TimeSpan.timeSpan(30).seconds();}
            @Override public TimeSpan helloTimeout() {return TimeSpan.timeSpan(5).seconds();}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {return List.of(self.id());}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }
}
