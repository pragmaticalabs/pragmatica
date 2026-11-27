// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.pragmatica.aether.node.health.CoreSwimHealthDetector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.sun.management.UnixOperatingSystemMXBean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2016 / #2731: the cluster-port helper issues from a window outside the ephemeral range under cross-JVM locks, probes the protocols a node binds (UDP cluster, TCP cluster, UDP SWIM),
/// and no node test goes back to reserving its cluster port over TCP alone.
class ClusterTestPortsTest {
    private static final int SWIM = CoreSwimHealthDetector.SWIM_PORT_OFFSET;
    /// TCP-only: the port is for an HTTP/1 management server and nothing UDP ever binds it.
    /// Reservation forms caught: `ServerSocket(0`, `ServerSocket(0, backlog, addr)`, `ServerSocketChannel.open()` with no argument or with an INET/INET6 family (a UNIX-family channel binds a filesystem path and reserves no port), and an
    /// explicit `bind(new InetSocketAddress(..., 0))`. A bare `new InetSocketAddress(.., 0)` is NOT matched: it is also
    /// how a live `HttpServer.create(...)` binds (GitBackupRepositoryTest), which would be a false positive.
    /// KNOWN GAP (#2016): a port held in a variable (`new ServerSocket(port)`, `bind(addrOf(p))`) is not caught. Such a
    /// call is also how tests deliberately HOLD a taken port, so no text pattern separates the two without false
    /// positives; telling them apart needs data flow (the port came from a closed socket), which this scan cannot see.
    private static final java.util.regex.Pattern RESERVES = java.util.regex.Pattern.compile("ServerSocket\\(\\s*0|ServerSocketChannel\\.open\\(\\s*(?:\\)|(?:java\\.net\\.)?StandardProtocolFamily\\.INET)|\\.bind\\(\\s*new (?:java\\.net\\.)?InetSocketAddress\\([^()]*(?:\\([^()]*\\))?[^()]*\\b0\\s*\\)\\s*\\)");
    private final List<int[]> reservations = new ArrayList<>();
    /// TCP-only holders: the first holds a management port for an HTTP/1 server; the three TLS fail-closed tests (#3321) hold the
    /// app or management HTTP port so that a bind attempt by the subject would surface as a bind error, never as the TLS refusal.
    /// `AlertForwarderTransportRetryTest` (#3536) holds a plain TCP socket as the webhook receiver. No node binds UDP on any of those ports.
    private static final java.util.Set<String> TCP_ONLY_TESTS = java.util.Set.of("ManagementServerDhtCatchUpGaugeTest.java",
                                                                                 "AetherNodeBrokenTlsBootTest.java",
                                                                                 "ManagementServerTlsFailClosedTest.java",
                                                                                 "AppHttpServerTlsFailClosedTest.java",
                                                                                 "AlertForwarderTransportRetryTest.java");

    @AfterEach
    void dropLeftoverReservations() {
        reservations.forEach(ClusterTestPorts::unreserve);
    }

    @Test
    void issuedPort_hasFreeClusterUdpTcpAndSwimUdp() {
        var port = ClusterTestPorts.freeClusterPort();

        assertThat(ClusterTestPorts.udpFree(port)).as("cluster UDP " + port).isTrue();
        assertThat(ClusterTestPorts.tcpFree(port)).as("cluster TCP " + port).isTrue();
        assertThat(ClusterTestPorts.udpFree(port + SWIM)).as("SWIM UDP " + (port + SWIM)).isTrue();
    }

    @Test
    void issuedPorts_areDistinctWithinTheJvm() {
        var seen = new HashSet<Integer>();

        for (int i = 0; i < 50; i++) {
            assertThat(seen.add(ClusterTestPorts.freeClusterPort())).as("port issued twice").isTrue();
        }
    }

    /// Pins the uniqueness claim: a candidate source that keeps offering an already-issued port is refused until a new one appears.
    @Test
    void issuedPort_isNotIssuedAgainEvenWhenTheSourceOffersItAgain() {
        var first = ClusterTestPorts.freeClusterPort();
        var second = ClusterTestPorts.freeClusterPort();
        var third = ClusterTestPorts.freeClusterPort(offeringAll(first, first, first, second, second));

        assertThat(third).as("only a never-issued port may come back").isNotIn(first, second);
    }

    /// #2731: the window lies outside the host's ephemeral range, so the kernel's autobind (any `bind(0)`, any outgoing
    /// connect) can never hand an issued port to another socket. The old helper drew from `DatagramSocket(0)`, i.e. from
    /// INSIDE that range, so this is red against it by construction.
    @Test
    void issuedPorts_lieInTheWindow_outsideTheEphemeralRange() {
        var window = ClusterTestPorts.WINDOW;
        var ephemeral = window.ephemeral();

        assertThat(window.size()).isGreaterThanOrEqualTo(ClusterTestPorts.MIN_WINDOW_PORTS);
        assertThat(window.first() > ephemeral.last() || window.last() < ephemeral.first()).as("window " + window + " vs ephemeral " + ephemeral)
                                                                                          .isTrue();
        assertThat(window.first()).as("above the fixed test ports").isGreaterThan(ClusterTestPorts.FIXED_TEST_PORTS_TOP);

        for (int i = 0; i < 50; i++) {
            var port = ClusterTestPorts.freeClusterPort();

            assertThat(window.contains(port) && window.contains(port + SWIM)).as("cluster " + port + " and SWIM in " + window)
                                                                                .isTrue();
            assertThat(port >= ephemeral.first() && port <= ephemeral.last()).as("cluster " + port + " ephemeral").isFalse();
            assertThat(port + SWIM >= ephemeral.first() && port + SWIM <= ephemeral.last()).as("SWIM ephemeral").isFalse();
        }
    }

    /// The window choice, on the ranges that matter: Linux default, macOS default, and a host with no room left.
    @Test
    void window_isTheLargerSpanOutsideTheEphemeralRange_orRefusesNamingTheRange() {
        var linux = ClusterTestPorts.Window.choose(new ClusterTestPorts.EphemeralRange(32_768, 60_999, "linux"));
        var mac = ClusterTestPorts.Window.choose(new ClusterTestPorts.EphemeralRange(49_152, 65_535, "mac"));

        assertThat(List.of(linux.first(), linux.last())).containsExactly(61_000, 65_535);
        assertThat(List.of(mac.first(), mac.last())).containsExactly(32_768, 49_151);
        assertThatThrownBy(() -> ClusterTestPorts.Window.choose(new ClusterTestPorts.EphemeralRange(1_024, 65_535, "wide")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1024-65535 (wide)");
        assertThatThrownBy(() -> ClusterTestPorts.Window.choose(new ClusterTestPorts.EphemeralRange(32_768, 64_999, "narrow-top")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32768-64999");
    }

    /// #2731 proof (non-cooperating holder): a candidate whose cluster UDP port a plain socket holds is skipped; once the
    /// socket closes, the SAME candidate is accepted, so the refusal came from the occupation and nothing else.
    @Test
    void freeClusterPort_skipsACandidateWhoseClusterUdpIsHeld_andAcceptsItOnceReleased() throws IOException {
        var taken = occupiableClusterCandidate(true);

        try (var held = new DatagramSocket(new InetSocketAddress(taken))) {
            var skips = new ArrayList<ClusterTestPorts.Skip>();
            var got = ClusterTestPorts.freeClusterPort(offering(taken), skips);

            assertThat(got).as("a port with cluster UDP held must not be issued").isNotEqualTo(taken);
            assertSkippedBusy(skips, "the skip names the probe that read it busy", taken, taken, ClusterTestPorts.Protocol.UDP);
        }
        var skips = new ArrayList<ClusterTestPorts.Skip>();

        assertAcceptedUnlessProbedBusy(ClusterTestPorts.freeClusterPort(offering(taken), skips), taken, skips, "control: the same port, released");
    }

    /// #3188: the released-port control cannot promise the SAME port back. The lock held since [#occupiableClusterCandidate] keeps every
    /// cooperating allocator out, but a process that binds an explicit window port without the lock (another module under `-T 1C`) can take the
    /// released port before the allocator's probe, and the allocator then correctly issues another one. This forces that contender into
    /// the window deterministically: the control must hold, and the allocator must have skipped the port because a probe read it busy.
    @Test
    void releasedPortControl_holdsWhenAThirdPartyTakesThePortInTheWindow() throws IOException {
        var taken = occupiableClusterCandidate(true);

        try (var contender = new DatagramSocket(new InetSocketAddress(taken))) {
            var skips = new ArrayList<ClusterTestPorts.Skip>();
            var got = ClusterTestPorts.freeClusterPort(offering(taken), skips);

            assertThat(got).as("the contender holds " + taken + ", so it must not be issued").isNotEqualTo(taken);
            assertAcceptedUnlessProbedBusy(got, taken, skips, "control: the same port, released");
        }
    }

    /// The control still bites: a refusal that no busy probe explains (the allocator turned away a port every probe called free)
    /// fails it, so it cannot pass vacuously. A refusal that names a busy probe passes, whatever the port's state afterwards. A skip for
    /// any other cause (window, lock) is not "a probe read it busy" and does not excuse the refusal; the failure names the cause.
    @Test
    void releasedPortControl_failsWhenAFreePortWasRefused() {
        var taken = occupiableClusterCandidate(true);
        var refusedForLock = List.of(ClusterTestPorts.Skip.skip(taken, ClusterTestPorts.SkipCause.LockRefused.lockRefused()));
        var refusedForWindow = List.of(ClusterTestPorts.Skip.skip(taken, ClusterTestPorts.SkipCause.OutsideWindow.outsideWindow()));
        var refusedForBusyProbe = List.of(ClusterTestPorts.Skip.skip(taken, busyProbe(new ClusterTestPorts.Busy(taken + SWIM, ClusterTestPorts.Protocol.UDP))));

        assertThatThrownBy(() -> assertAcceptedUnlessProbedBusy(taken + 1, taken, refusedForLock, "refused"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("LockRefused");
        assertThatThrownBy(() -> assertAcceptedUnlessProbedBusy(taken + 1, taken, refusedForWindow, "refused"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("OutsideWindow");
        assertThatThrownBy(() -> assertAcceptedUnlessProbedBusy(taken + 1, taken, List.of(), "refused"))
            .isInstanceOf(AssertionError.class);
        assertAcceptedUnlessProbedBusy(taken + 1, taken, refusedForBusyProbe, "refused for a busy probe");
    }

    /// #3471: the probe-busy cause cannot be built from an empty set: "no probe read it busy" is not a cause the record can carry.
    @Test
    void probeBusy_refusesAnEmptySet_andKeepsEveryProbeOfANonEmptyOne() {
        var probes = Set.of(new ClusterTestPorts.Busy(40_000, ClusterTestPorts.Protocol.UDP),
                            new ClusterTestPorts.Busy(40_000, ClusterTestPorts.Protocol.TCP));

        assertThat(ClusterTestPorts.SkipCause.ProbeBusy.probeBusy(Set.of()).isFailure()).as("empty set refused").isTrue();
        assertThat(busyProbe(probes.toArray(new ClusterTestPorts.Busy[0])).busy()).isEqualTo(probes);
    }

    /// #3471: "released, so accepted again" holds unless the allocator skipped the port because a probe read it busy. The control asserts on
    /// the allocator's own record of that probe, never on a later re-probe: a contender that binds and releases the port inside the
    /// probe leaves the port free afterwards, and the skip is still correct. A skip with no busy probe behind it is the defect.
    private static void assertAcceptedUnlessProbedBusy(int got, int taken, List<ClusterTestPorts.Skip> skips, String label) {
        if (got != taken) {
            assertThat(skips).as(label + ": " + taken + " was refused although no probe read it busy; causes recorded for it: " + causesOf(skips, taken))
                             .anyMatch(skip -> skip.candidate() == taken && skip.probedBusy());
        }
    }

    /// The held probe is among the busy probes recorded for `candidate`. Set membership, not equality: a third party that holds another
    /// port of the same candidate can only add to the record, never remove the held one.
    private static void assertSkippedBusy(List<ClusterTestPorts.Skip> skips, String label, int candidate, int port, ClusterTestPorts.Protocol protocol) {
        assertThat(skips).as(label)
                         .anyMatch(skip -> skip.candidate() == candidate && skip.busy().contains(new ClusterTestPorts.Busy(port, protocol)));
    }

    /// #3471: a third party that holds more of the candidate than the probe the test holds must not hide it: the skip records every busy
    /// probe (cluster UDP, cluster TCP, SWIM UDP), so "the held protocol is in the record" holds whatever else is taken.
    @Test
    void freeClusterPort_recordsEveryBusyProbeOfASkippedCandidate() throws IOException {
        var taken = occupiableClusterCandidate(true);

        try (var udp = new DatagramSocket(new InetSocketAddress(taken));
             var tcp = new ServerSocket();
             var swim = new DatagramSocket(new InetSocketAddress(taken + SWIM))) {
            tcp.bind(new InetSocketAddress(taken));
            var skips = new ArrayList<ClusterTestPorts.Skip>();

            assertThat(ClusterTestPorts.freeClusterPort(offering(taken), skips)).isNotEqualTo(taken);
            assertSkippedBusy(skips, "cluster UDP", taken, taken, ClusterTestPorts.Protocol.UDP);
            assertSkippedBusy(skips, "cluster TCP", taken, taken, ClusterTestPorts.Protocol.TCP);
            assertSkippedBusy(skips, "SWIM UDP", taken, taken + SWIM, ClusterTestPorts.Protocol.UDP);
        }
    }

    @Test
    void freeClusterPort_skipsACandidateWhoseSwimUdpIsHeld_andAcceptsItOnceReleased() throws IOException {
        var taken = occupiableClusterCandidate(true);

        try (var held = new DatagramSocket(new InetSocketAddress(taken + SWIM))) {
            var skips = new ArrayList<ClusterTestPorts.Skip>();

            assertThat(ClusterTestPorts.freeClusterPort(offering(taken), skips)).as("SWIM UDP held").isNotEqualTo(taken);
            assertSkippedBusy(skips, "the skip names the SWIM probe", taken, taken + SWIM, ClusterTestPorts.Protocol.UDP);
        }
        var skips = new ArrayList<ClusterTestPorts.Skip>();

        assertAcceptedUnlessProbedBusy(ClusterTestPorts.freeClusterPort(offering(taken), skips), taken, skips, "control: SWIM released");
    }

    /// The both-protocol helper must skip a candidate whose TCP side is taken, and one whose UDP side is taken.
    @Test
    void freeTcpAndUdpPort_skipsACandidateWithTcpTaken() throws IOException {
        var taken = occupiableClusterCandidate(false);

        try (var held = new ServerSocket()) {
            held.bind(new InetSocketAddress(taken));
            var skips = new ArrayList<ClusterTestPorts.Skip>();

            assertThat(ClusterTestPorts.freeTcpAndUdpPort(offering(taken), skips)).as("TCP held").isNotEqualTo(taken);
            assertSkippedBusy(skips, "the skip names the TCP probe", taken, taken, ClusterTestPorts.Protocol.TCP);
        }
        var skips = new ArrayList<ClusterTestPorts.Skip>();

        assertAcceptedUnlessProbedBusy(ClusterTestPorts.freeTcpAndUdpPort(offering(taken), skips), taken, skips, "control: TCP released");
    }

    @Test
    void freeTcpAndUdpPort_skipsACandidateWithUdpTaken() throws IOException {
        var taken = occupiableClusterCandidate(false);

        try (var held = new DatagramSocket(new InetSocketAddress(taken))) {
            var skips = new ArrayList<ClusterTestPorts.Skip>();

            assertThat(ClusterTestPorts.freeTcpAndUdpPort(offering(taken), skips)).as("UDP held").isNotEqualTo(taken);
            assertSkippedBusy(skips, "the skip names the UDP probe", taken, taken, ClusterTestPorts.Protocol.UDP);
        }
        var skips = new ArrayList<ClusterTestPorts.Skip>();

        assertAcceptedUnlessProbedBusy(ClusterTestPorts.freeTcpAndUdpPort(offering(taken), skips), taken, skips, "control: UDP released");
    }

    /// #2731 proof (cooperating allocators in two JVMs): a child JVM allocates ports and holds them WITHOUT binding, i.e.
    /// it sits in the window between allocation and a node's bind. The parent never issues any of them: neither by its own
    /// draws nor when its candidate source offers exactly the child's port. Once the child is killed (`kill -9`), the OS
    /// drops its locks and the parent obtains that port: no stale lock, and the port itself was usable all along.
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void portsHeldByAnotherJvm_areNeverIssued_untilThatJvmDies() throws Exception {
        var child = PortHolder.start("new", "100");

        try {
            var childPorts = child.ports();
            var parentPorts = new HashSet<Integer>();

            for (int i = 0; i < 100; i++) {
                parentPorts.add(ClusterTestPorts.freeClusterPort());
            }
            var claimedByBoth = new HashSet<>(parentPorts);

            claimedByBoth.retainAll(childPorts);
            assertThat(childPorts).hasSize(100);
            assertThat(claimedByBoth).as("issued to both JVMs").isEmpty();

            var victim = childPorts.getFirst();

            assertThatThrownBy(() -> ClusterTestPorts.freeClusterPort(() -> victim)).as("child's port " + victim + " offered to the parent")
                                                                                   .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ClusterTestPorts.freeTcpAndUdpPort(() -> victim + SWIM)).as("child's SWIM port offered")
                                                                                          .isInstanceOf(IllegalStateException.class);
            child.kill();
            assertThat(ClusterTestPorts.freeClusterPort(() -> victim)).as("after the child died").isEqualTo(victim);
        } finally {
            child.kill();
        }
    }

    /// #2902: the reservation a control test relies on is a real cross-JVM lock. It is refused for a port another JVM holds, and for a port it
    /// took, a second claim on the same lock file overlaps (this JVM holds the lock), so no other allocator could take the port either.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void reserve_isRefusedForAPortAnotherJvmHolds_andHoldsTheLockForThePortsItTook() throws Exception {
        var child = PortHolder.start("new", "5");

        try {
            var victim = child.ports().getFirst();
            var mine = occupiableClusterCandidate(false);

            assertThat(ClusterTestPorts.reserve(victim)).as("child's port " + victim + " is locked by another JVM").isFalse();
            // Opening and closing the lock file here drops this JVM's OS lock on it (POSIX), so `mine` is not used after the check.
            try (var channel = FileChannel.open(ClusterTestPorts.LOCK_DIR.resolve(mine + ".lock"), StandardOpenOption.WRITE)) {
                assertThatThrownBy(channel::tryLock).as("the lock on the reserved port " + mine + " is held").isInstanceOf(OverlappingFileLockException.class);
            }
        } finally {
            child.kill();
        }
    }

    /// RED CONTROL, the pre-#2731 allocator: a child JVM picks a port the old way (`DatagramSocket(0)` probe, released)
    /// and is still alive in the allocation-to-bind window, yet nothing holds the port. A third party binds it at once, and
    /// the child's node would then fail with BindFailed. The port also lies inside the kernel's ephemeral range, where any
    /// outgoing socket on the host can be handed it without asking.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void legacyAllocator_leavesItsPortTakeableByAThirdParty() throws Exception {
        var child = PortHolder.start("legacy");

        try {
            var port = child.ports().getFirst();
            var ephemeral = ClusterTestPorts.WINDOW.ephemeral();

            assertThat(port).as("legacy candidate comes from the ephemeral range").isBetween(ephemeral.first(), ephemeral.last());
            try (var thirdParty = new DatagramSocket(new InetSocketAddress(port))) {
                assertThat(thirdParty.getLocalPort()).as("a third party took the legacy port").isEqualTo(port);
            }
        } finally {
            child.kill();
        }
    }

    /// #3471, window path: a candidate outside the window (either port of a cluster candidate, or the only port of the TCP+UDP form) is
    /// recorded as OutsideWindow, takes no lock, and is never read as a probe-busy refusal.
    @Test
    void windowSkips_recordOutsideWindow_andTakeNoLock() {
        var window = ClusterTestPorts.WINDOW;
        var below = window.first() - 1;
        var swimOutside = window.last() - SWIM + 1;
        var tcpUdpOutside = window.last() + 1;
        var clusterSkips = new ArrayList<ClusterTestPorts.Skip>();
        var tcpUdpSkips = new ArrayList<ClusterTestPorts.Skip>();

        ClusterTestPorts.freeClusterPort(offeringAll(below, swimOutside), clusterSkips);
        ClusterTestPorts.freeTcpAndUdpPort(offeringAll(tcpUdpOutside), tcpUdpSkips);

        assertSingleSkip(clusterSkips, below, ClusterTestPorts.SkipCause.OutsideWindow.class);
        assertSingleSkip(clusterSkips, swimOutside, ClusterTestPorts.SkipCause.OutsideWindow.class);
        assertSingleSkip(tcpUdpSkips, tcpUdpOutside, ClusterTestPorts.SkipCause.OutsideWindow.class);
        assertThat(List.of(below, tcpUdpOutside)).as("nothing locked for a candidate outside the window")
                                                              .noneMatch(ClusterTestPorts::heldHere);
    }

    /// #3471, m3: the record belongs to the call. A second call with its own list sees nothing of the first call's skips, so one shared
    /// record copied into every caller's list is caught.
    @Test
    void skipRecord_isPerCall_notSharedAcrossCallers() throws IOException {
        var taken = occupiableClusterCandidate(true);
        var free = occupiableClusterCandidate(true);
        var first = new ArrayList<ClusterTestPorts.Skip>();
        var second = new ArrayList<ClusterTestPorts.Skip>();

        try (var held = new DatagramSocket(new InetSocketAddress(taken))) {
            ClusterTestPorts.freeClusterPort(offering(taken), first);
        }
        ClusterTestPorts.freeClusterPort(offering(free), second);

        assertSingleSkip(first, taken, ClusterTestPorts.SkipCause.ProbeBusy.class);
        assertThat(skipsFor(second, taken)).as("the first call's skip leaked into the second caller's list").isEmpty();
    }

    /// #3471, m5: with nothing holding it, a reserved free port is accepted and no cause is recorded for it. A refusal fabricated for a free
    /// port (the allocator citing a busy probe nobody saw) fails here; the released-port controls tolerate any busy probe, so they cannot.
    @Test
    void reservedFreePort_isAccepted_withNoCauseRecorded() {
        var taken = occupiableClusterCandidate(true);
        var skips = new ArrayList<ClusterTestPorts.Skip>();

        assertThat(ClusterTestPorts.freeClusterPort(offering(taken), skips)).as("nothing holds " + taken).isEqualTo(taken);
        assertThat(skips).as("a free port is issued without a skip").isEmpty();
    }

    /// #3471, m17: issuing a port the test reserved moves its lock from the reservation to the issued set. Dropping a reservation afterwards
    /// must not release the issued port: it stays held for the JVM's life.
    @Test
    void issuedReservedPort_staysLocked_afterTheReservationIsDropped() {
        var taken = occupiableClusterCandidate(true);
        var skips = new ArrayList<ClusterTestPorts.Skip>();
        var got = ClusterTestPorts.freeClusterPort(offering(taken), skips);

        assertAcceptedUnlessProbedBusy(got, taken, skips, "control: a reserved free port");
        if (got == taken) {
            ClusterTestPorts.unreserve(taken, taken + SWIM);
            assertThat(List.of(ClusterTestPorts.heldHere(taken), ClusterTestPorts.heldHere(taken + SWIM)))
                .as("the issued port's locks survive unreserve (adoptReserved moved them)")
                .containsExactly(true, true);
        }
    }

    /// #3471, m4: a probe releases its socket on every outcome. Many probes of a free port leave no descriptor behind and the port stays
    /// bindable; a probe socket that is never closed shows as growing descriptors.
    @Test
    void probes_releaseTheirSocket() throws IOException {
        var port = occupiableClusterCandidate(true);
        var before = openFds();

        for (int i = 0; i < 200; i++) {
            assertThat(ClusterTestPorts.udpFree(port)).as("UDP probe " + i).isTrue();
            assertThat(ClusterTestPorts.tcpFree(port)).as("TCP probe " + i).isTrue();
        }
        assertThat(openFds()).as("descriptors left behind by 400 probes").isLessThanOrEqualTo(before + 20);
        try (var udp = new DatagramSocket(new InetSocketAddress(port)); var tcp = new ServerSocket()) {
            tcp.bind(new InetSocketAddress(port));
            assertThat(udp.getLocalPort()).as("the probes left the port bindable").isEqualTo(port);
        }
    }

    /// #3471, m10: a probe-busy skip releases the locks it took. The candidate is NOT reserved (a reservation would hide a leak: the
    /// allocator would leave nothing of its own in the held set), so a leaked lock shows as the port still held here, and as a refused
    /// `reserve`.
    @Test
    void probeBusySkip_releasesTheLocksItTook_andLeavesNoProbeSocket() throws IOException {
        var candidate = unlockedFreeCandidate();
        var skips = new ArrayList<ClusterTestPorts.Skip>();

        try (var held = new DatagramSocket(new InetSocketAddress(candidate))) {
            ClusterTestPorts.freeClusterPort(offering(drawingFirst(candidate), candidate), skips);
        }
        assertSingleSkip(skips, candidate, ClusterTestPorts.SkipCause.ProbeBusy.class);
        assertThat(List.of(ClusterTestPorts.heldHere(candidate), ClusterTestPorts.heldHere(candidate + SWIM)))
            .as("locks left behind by a probe-busy skip").containsExactly(false, false);
        assertThat(ClusterTestPorts.isFreeClusterPort(candidate)).as("a probe socket left behind").isTrue();
        assertThat(ClusterTestPorts.reserve(candidate, candidate + SWIM)).as("the locks can be taken again").isTrue();
        reservations.add(new int[] {candidate, candidate + SWIM});
    }

    /// #3471, A6 and m12: a candidate whose lock another JVM holds is skipped for the lock, with every probe reading it free; the cause is
    /// LockRefused, never ProbeBusy. Nothing is bound or held here afterwards.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void lockHeldByAnotherJvm_isRecordedAsLockRefused_notAsAProbe() throws Exception {
        var child = PortHolder.start("new", "5");

        try {
            var candidate = child.ports().getFirst();
            var skips = new ArrayList<ClusterTestPorts.Skip>();

            assertThat(ClusterTestPorts.isFreeClusterPort(candidate)).as("the lock holder binds nothing: every probe reads it free").isTrue();
            assertThat(ClusterTestPorts.freeClusterPort(offering(drawingFirst(candidate), candidate), skips)).isNotEqualTo(candidate);
            assertSingleSkip(skips, candidate, ClusterTestPorts.SkipCause.LockRefused.class);
            assertThat(skipsFor(skips, candidate).getFirst().probedBusy()).as("a lock refusal is not a probe read").isFalse();
            assertThat(ClusterTestPorts.isFreeClusterPort(candidate)).as("no probe socket left").isTrue();
        } finally {
            child.kill();
        }
    }

    /// #3471, m11: a lock refusal on the SECOND port of a candidate releases the lock already taken on the first. The candidate's SWIM
    /// port belongs to another JVM, its cluster port to nobody: after the skip the cluster port can be locked again.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void lockRefusalOnTheSwimPort_releasesTheLockTakenOnTheClusterPort() throws Exception {
        var child = PortHolder.start("new", "20");

        try {
            var candidate = child.ports().stream()
                                 .map(port -> port - SWIM)
                                 .filter(ClusterTestPorts.WINDOW::contains)
                                 .filter(ClusterTestPortsTest::lockIsFree)
                                 .findFirst()
                                 .orElseThrow();
            var skips = new ArrayList<ClusterTestPorts.Skip>();

            assertThat(ClusterTestPorts.freeClusterPort(offering(candidate), skips)).isNotEqualTo(candidate);
            assertSingleSkip(skips, candidate, ClusterTestPorts.SkipCause.LockRefused.class);
            assertThat(ClusterTestPorts.heldHere(candidate)).as("the cluster port's lock left behind by the refusal").isFalse();
            assertThat(ClusterTestPorts.reserve(candidate)).as("the cluster port can be locked again").isTrue();
            reservations.add(new int[] {candidate});
        } finally {
            child.kill();
        }
    }

    /// Positive controls: each probe must read a port this test holds as taken, or "free" is vacuous.
    @Test
    void probe_seesHeldClusterUdp() throws IOException {
        try (var held = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) { // port0-held: the positive-control socket stays open while the probe reads its port
            assertThat(ClusterTestPorts.isFreeClusterPort(held.getLocalPort())).isFalse();
        }
    }

    @Test
    void probe_seesHeldClusterTcp() throws IOException {
        try (var held = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { // port0-held: the positive-control socket stays open while the probe reads its port
            assertThat(ClusterTestPorts.isFreeClusterPort(held.getLocalPort())).isFalse();
        }
    }

    /// The derived port the old helper never looked at: cluster port and TCP free, only the SWIM UDP port taken.
    @Test
    void probe_seesHeldSwimUdpAtTheDerivedPort() throws IOException {
        var clusterPort = ClusterTestPorts.freeClusterPort();

        try (var held = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), clusterPort + SWIM))) {
            assertThat(held.getLocalPort()).isEqualTo(clusterPort + SWIM);
            assertThat(ClusterTestPorts.isFreeClusterPort(clusterPort)).isFalse();
        }
    }

    /// The tripwire pattern flags every TCP reservation form and no UNIX-family channel (#3101).
    @Test
    void reservesPattern_flagsTcpForms_andNotUnixDomainChannels() {
        assertThat(RESERVES.matcher("new ServerSocket(0)").find()).as("ServerSocket(0").isTrue();
        assertThat(RESERVES.matcher("ServerSocketChannel.open()").find()).as("open() no args").isTrue();
        assertThat(RESERVES.matcher("ServerSocketChannel.open(StandardProtocolFamily.INET)").find()).as("INET").isTrue();
        assertThat(RESERVES.matcher("ServerSocketChannel.open(StandardProtocolFamily.INET6)").find()).as("INET6").isTrue();
        assertThat(RESERVES.matcher("ch.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))").find()).as("bind InetSocketAddress 0").isTrue();
        assertThat(RESERVES.matcher("ServerSocketChannel.open(StandardProtocolFamily.UNIX)").find()).as("UNIX family").isFalse();
        assertThat(RESERVES.matcher("var a = UnixDomainSocketAddress.of(path); server.bind(a);").find()).as("UnixDomainSocketAddress").isFalse();
    }

    /// Tripwire for the class: a node test that reserves a cluster port over TCP and lets a node bind UDP on it.
    @Test
    void nodeTests_doNotReserveAPortWithTcpServerSocket() throws IOException {
        var testRoot = Path.of("src", "test", "java");

        assertThat(testRoot).as("run from the module directory").isDirectory();

        try (Stream<Path> files = Files.walk(testRoot)) {
            var offenders = files.filter(p -> p.toString().endsWith(".java"))
                                 .filter(p -> !TCP_ONLY_TESTS.contains(p.getFileName().toString()))
                                 .filter(p -> !p.getFileName().toString().equals("ClusterTestPortsTest.java"))
                                 .filter(ClusterTestPortsTest::reservesWithTcpServerSocket)
                                 .map(Path::toString)
                                 .toList();

            assertThat(offenders).as("use ClusterTestPorts.freeClusterPort() (#2016)").isEmpty();
        }
    }

    /// A window port free right now on cluster UDP, cluster TCP and SWIM UDP and NOT held by this JVM: a test occupies it.
    /// #2902: the cross-JVM lock on it (and on its SWIM port, when `withSwim`) is taken first and kept through the whole test, so
    /// no other allocator can issue it between the probe and the control "released, accepted again" (the allocation attempt adopts
    /// the reservation, see [ClusterTestPorts#reserve]). A port another JVM holds is simply not chosen.
    private int occupiableClusterCandidate(boolean withSwim) {
        for (int attempt = 0; attempt < 200; attempt++) {
            var port = ClusterTestPorts.windowCandidate();
            var ports = withSwim ? new int[] {port, port + SWIM} : new int[] {port};

            if (ClusterTestPorts.WINDOW.contains(port + SWIM) && ClusterTestPorts.reserve(ports)) {
                if (ClusterTestPorts.isFreeClusterPort(port)) {
                    reservations.add(ports);
                    return port;
                }
                ClusterTestPorts.unreserve(ports);
            }
        }
        throw new IllegalStateException("no free window port to occupy");
    }

    /// Offers `port` first, then random window ports other than `port`.
    private static IntSupplier offering(int port) {
        return offeringAll(port);
    }

    /// As [#offering(int)], the fallback drawing from `draws` (#3819 seam: a test makes the first fallback draw the subject).
    private static IntSupplier offering(IntSupplier draws, int port) {
        return offeringAll(draws, port);
    }

    /// A fallback source whose first draw is `subject`, then random window ports: the draw that used to record a second skip.
    private static IntSupplier drawingFirst(int subject) {
        var first = new AtomicBoolean(true);

        return () -> first.getAndSet(false)
                     ? subject
                     : ClusterTestPorts.windowCandidate();
    }

    /// A window port, with its SWIM port, that no allocator holds right now (checked by taking and dropping the locks) and whose
    /// sockets are free. NOT kept reserved: a reservation would hide a lock the allocator leaks.
    private static int unlockedFreeCandidate() {
        for (int attempt = 0; attempt < 200; attempt++) {
            var port = ClusterTestPorts.windowCandidate();

            if (ClusterTestPorts.WINDOW.contains(port + SWIM) && lockIsFree(port) && lockIsFree(port + SWIM) && ClusterTestPorts.isFreeClusterPort(port)) {
                return port;
            }
        }
        throw new IllegalStateException("no unlocked free window port");
    }

    private static boolean lockIsFree(int port) {
        var taken = ClusterTestPorts.reserve(port);

        ClusterTestPorts.unreserve(port);
        return taken;
    }

    private static long openFds() {
        return ((UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getOpenFileDescriptorCount();
    }

    private static ClusterTestPorts.SkipCause.ProbeBusy busyProbe(ClusterTestPorts.Busy... probes) {
        return ClusterTestPorts.SkipCause.ProbeBusy.probeBusy(Set.of(probes)).unwrap();
    }

    private static List<ClusterTestPorts.Skip> skipsFor(List<ClusterTestPorts.Skip> skips, int candidate) {
        return skips.stream().filter(skip -> skip.candidate() == candidate).toList();
    }

    private static List<String> causesOf(List<ClusterTestPorts.Skip> skips, int candidate) {
        return skipsFor(skips, candidate).stream().map(skip -> skip.cause().getClass().getSimpleName()).toList();
    }

    /// Exactly one skip for `candidate` in the record, and its cause is of type `cause`.
    private static void assertSingleSkip(List<ClusterTestPorts.Skip> skips, int candidate, Class<? extends ClusterTestPorts.SkipCause> cause) {
        assertThat(skipsFor(skips, candidate)).as("skips recorded for " + candidate).hasSize(1)
                                              .allSatisfy(skip -> assertThat(skip.cause()).isInstanceOf(cause));
    }

    /// Offers each of `ports` once, in order, then random window ports. #3819: the fallback NEVER draws a port offered explicitly,
    /// so a fixture that offers a subject once never offers it again (a second draw of a held subject recorded a second skip).
    private static IntSupplier offeringAll(int... ports) {
        return offeringAll(ClusterTestPorts::windowCandidate, ports);
    }

    /// As [#offeringAll(int...)], the fallback drawing from `draws`.
    private static IntSupplier offeringAll(IntSupplier draws, int... ports) {
        var offers = new ArrayDeque<Integer>();
        var offered = new HashSet<Integer>();

        for (int port : ports) {
            offers.add(port);
            offered.add(port);
        }
        return () -> offers.isEmpty()
                     ? drawOtherThan(draws, offered)
                     : offers.poll();
    }

    private static int drawOtherThan(IntSupplier draws, Set<Integer> offered) {
        return draws.getAsInt();
    }

    /// A child JVM running [PortHolderMain] on this JVM's classpath.
    private record PortHolder(Process process, List<Integer> ports) {
        static PortHolder start(String... args) throws IOException {
            var command = new ArrayList<String>();

            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(PortHolderMain.class.getName());
            command.addAll(List.of(args));

            var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            var ports = new ArrayList<Integer>();
            String line;

            while ((line = reader.readLine()) != null && !line.equals("READY")) {
                if (line.startsWith("PORT ")) {
                    ports.add(Integer.parseInt(line.substring(5).trim()));
                }
            }
            if (line == null) {
                process.destroyForcibly();
                throw new IllegalStateException("port holder exited before READY: " + command);
            }
            return new PortHolder(process, List.copyOf(ports));
        }

        void kill() throws InterruptedException {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    private static boolean reservesWithTcpServerSocket(Path file) {
        try {
            return RESERVES.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
