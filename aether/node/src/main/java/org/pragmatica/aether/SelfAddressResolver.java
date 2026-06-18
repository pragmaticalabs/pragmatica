// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Resolves a ROUTABLE advertise host for this node at boot, BEFORE `selfInfo` is built, so the
/// node never advertises a container/VM hostname that peers cannot resolve.
///
/// The problem this guards against: a CTM-provisioned auto-heal replacement node is not present in
/// its own seed PEERS (the leader does not yet know the new VM's IP). `Main.parsePeers` therefore
/// has to synthesize the node's own `NodeInfo` — and the naive source, `InetAddress.getLocalHost()
/// .getHostName()`, is a private container/VM hostname unresolvable from other nodes. That poisoned
/// hostname propagates immutably into SWIM probing, the QUIC Hello, consensus and the DHT; peers
/// DNS-fail resolving it, suspect the node, and kill it — a silent multi-minute flap.
///
/// Resolution chain (first that yields a host wins):
///   1. EXPLICIT OVERRIDE — `AETHER_ADVERTISE_HOST` env / `--advertise-host=` arg. The primary
///      load-bearing path; CTM user-data sets it to the VM's routable IP. No transport touched.
///   2. SWIM `WhoAmI` REFLECTION — bind a transient SWIM transport on an ephemeral port using the
///      SAME serializer/encryptor as the cluster, ask each seed "what source address did you
///      observe my datagram coming from?", and take the IP from the first [SwimMessage.WhoAmIReply].
///   3. LOUD FALLBACK — the local hostname, accompanied by a WARN naming the seeds tried. This turns
///      an otherwise-silent flap into a single visible log line.
///
/// The transport construction is injected ([Transports]) so tests exercise the full chain without
/// binding a real socket.
public final class SelfAddressResolver {
    private static final Logger log = LoggerFactory.getLogger(SelfAddressResolver.class);
    /// SWIM listens on `clusterPort + SWIM_PORT_OFFSET`; mirrors
    /// `CoreSwimHealthDetector.SWIM_PORT_OFFSET` (kept local — the `node` package must not depend on
    /// the health detector for a boot-time constant).
    static final int SWIM_PORT_OFFSET = 100;
    private static final int EPHEMERAL_PORT = 0;
    private static final int MAX_SEEDS = 3;
    private static final TimeSpan PER_ATTEMPT_TIMEOUT = timeSpan(3).seconds();

    private final Serializer serializer;
    private final Deserializer deserializer;
    private final GossipEncryptor encryptor;
    private final Transports transports;

    /// Builds a transient SWIM transport for a one-shot reflection probe. The real binding
    /// (`NettySwimTransport`) is injected so tests substitute an in-memory transport that echoes a
    /// reply without touching a socket.
    public interface Transports {
        Result<SwimTransport> create(Serializer serializer, Deserializer deserializer, GossipEncryptor encryptor);
    }

    private SelfAddressResolver(Serializer serializer,
                                Deserializer deserializer,
                                GossipEncryptor encryptor,
                                Transports transports) {
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.encryptor = encryptor;
        this.transports = transports;
    }

    public static SelfAddressResolver selfAddressResolver(Serializer serializer,
                                                          Deserializer deserializer,
                                                          GossipEncryptor encryptor,
                                                          Transports transports) {
        return new SelfAddressResolver(serializer, deserializer, encryptor, transports);
    }

    /// Resolve the advertise host for `self`. `override` short-circuits the whole chain (path 1);
    /// otherwise the seeds drive SWIM reflection (path 2); `hostnameFallback` is the loud last
    /// resort (path 3). The fallback is a supplier so tests stay deterministic without touching the
    /// JVM's local hostname.
    ///
    /// `clusterPort` is this node's own cluster port. It is part of the boot contract (callers pass
    /// it from the parsed `--port=`/`CLUSTER_PORT`), but reflection addresses each seed at the
    /// SEED's own cluster port plus [#SWIM_PORT_OFFSET], so self's port is not needed here today.
    @TerminalOperation
    @SuppressWarnings("unused")
    public String resolve(NodeId self,
                          int clusterPort,
                          List<NodeInfo> seeds,
                          Option<String> override,
                          Fn0<String> hostnameFallback) {
        return override.filter(host -> !host.isBlank())
                       .map(String::trim)
                       .orElse(() -> reflectViaSwim(self, seeds))
                       .or(() -> warnAndFallback(seeds, hostnameFallback));
    }

    /// Path 2. Binds the transient transport once, fires `WhoAmI` at up to [#MAX_SEEDS] seeds, and
    /// returns the observed IP of the first reply. The transport is always stopped, success or
    /// failure. Returns `none()` (so the caller falls through to the fallback) when the transport
    /// fails to start, there are no seeds, or no seed replies within the timeout cap.
    private Option<String> reflectViaSwim(NodeId self, List<NodeInfo> seeds) {
        var seedAddresses = swimSeedAddresses(seeds);

        if (seedAddresses.isEmpty()) {
            return Option.none();
        }

        return transports.create(serializer, deserializer, encryptor)
                         .onFailure(cause -> log.warn("SWIM reflection transport failed to start: {}",
                                                      cause.message()))
                         .option()
                         .flatMap(transport -> probeSeeds(transport, self, seedAddresses));
    }

    @TerminalOperation
    private Option<String> probeSeeds(SwimTransport transport, NodeId self, List<InetSocketAddress> seedAddresses) {
        var observed = Promise.<InetSocketAddress> promise();

        return transport.start(EPHEMERAL_PORT,
                               (_, message) -> completeOnReply(observed, message))
                        .flatMap(_ -> sendWhoAmI(transport, self, seedAddresses))
                        .flatMap(_ -> observed.timeout(PER_ATTEMPT_TIMEOUT))
                        .map(SelfAddressResolver::observedHost)
                        .fold(result -> stopThen(transport, result))
                        .await(overallCap())
                        .onFailure(cause -> log.warn("WhoAmI reflection failed: {}",
                                                     cause.message()))
                        .option();
    }

    /// Stop the transient transport, then surface the carried result — keeping the cleanup
    /// `Promise` in the chain rather than abandoning it, so the ephemeral socket is always released
    /// whether the probe succeeded or failed.
    private static Promise<String> stopThen(SwimTransport transport, Result<String> carried) {
        return transport.stop()
                        .flatMap(_ -> carried.async());
    }

    private static void completeOnReply(Promise<InetSocketAddress> observed, SwimMessage message) {
        if (message instanceof SwimMessage.WhoAmIReply reply) {
            observed.succeed(reply.observedAddress());
        }
    }

    private static Promise<List<Result<Unit>>> sendWhoAmI(SwimTransport transport,
                                                          NodeId self,
                                                          List<InetSocketAddress> seedAddresses) {
        var probe = SwimMessage.WhoAmI.whoAmI(self);

        return Promise.allOf(seedAddresses.stream().map(seed -> transport.send(seed, probe)).toList());
    }

    private static String observedHost(InetSocketAddress observedAddress) {
        return observedAddress.getAddress()
                              .getHostAddress();
    }

    /// Overall cap across all seeds: the per-attempt timeout plus a small slack so the probe
    /// promise's own timeout fires first and yields the named cause, rather than the await cap.
    private static TimeSpan overallCap() {
        return PER_ATTEMPT_TIMEOUT.plus(timeSpan(2).seconds());
    }

    /// Path 3. Names the seeds tried so an operator can see WHY the node fell back to an unverified
    /// hostname, then returns the local hostname.
    private static String warnAndFallback(List<NodeInfo> seeds, Fn0<String> hostnameFallback) {
        var hostname = hostnameFallback.apply();

        log.warn("Advertising UNVERIFIED hostname '{}' — AETHER_ADVERTISE_HOST was unset and WhoAmI "
                + "reflection found no answering seed (tried {}). Peers may be unable to route to this "
                + "node; set AETHER_ADVERTISE_HOST to its routable IP.",
                 hostname,
                 seedHosts(seeds));

        return hostname;
    }

    private static List<InetSocketAddress> swimSeedAddresses(List<NodeInfo> seeds) {
        return seeds.stream()
                    .limit(MAX_SEEDS)
                    .map(SelfAddressResolver::swimSeedAddress)
                    .toList();
    }

    private static InetSocketAddress swimSeedAddress(NodeInfo seed) {
        return InetSocketAddress.createUnresolved(seed.address().host(),
                                                  seed.address().port() + SWIM_PORT_OFFSET);
    }

    private static List<String> seedHosts(List<NodeInfo> seeds) {
        return seeds.stream()
                    .map(seed -> seed.address()
                                     .asString())
                    .toList();
    }
}
