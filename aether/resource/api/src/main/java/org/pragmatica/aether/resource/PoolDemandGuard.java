// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pragmatica.aether.resource.NodeShareable.PoolDemand;
import org.pragmatica.aether.resource.NodeShareable.ServerId;
import org.pragmatica.aether.resource.NodeShareable.ServerProbe;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


/// The node's datasource connection-demand guard (#2973 §6): after each pool of a [NodeShareable] factory opens, shared
/// or per-slice, it compares what this node's pools ask of that server with what the server allows. It WARNs and never
/// refuses.
///
/// - The probe runs as a detached continuation of the open, never on the provision path, bounded by the pool's
///   connection timeout. PostgreSQL only: a factory whose server has no comparable limit answers `none()` (DEBUG).
/// - A pool registers only on a successful open and deregisters in the provider's one close funnel, so the sums cover
///   exactly the pools that are open now.
/// - Sums are per server. A pool's server is its configured id until a probe answers, then the answered `(addr, port)`
///   (through `aliases`), so `localhost`, `127.0.0.1` and a DNS name meet in one sum after their first answers.
/// - `lastAvailable` caches the last answered limit per answered server, so a probe refused with SQLSTATE 53300 (the
///   server is already full) can still name the limit. A failed probe resolves its configured id through `aliases`, then
///   through `lastAvailable`; either miss means "limit unknown".
///
/// Emission: [OperatorWarnings#raise], so a condition reaches the cluster event log as well as the node log. The subject
/// is the server, never the pool or the slice, so the aggregator throttles the event per (code, server); the log line is
/// written on every pool open while the condition holds. There is no clear event: the guard reacts to an edge (a pool
/// open), not to a standing condition it tracks to recovery.
final class PoolDemandGuard {
    private static final Logger LOG = LoggerFactory.getLogger(PoolDemandGuard.class);
    private static final String SERVER_FULL_SQLSTATE = "53300";

    private static final String TEMPLATE = "Datasource pool demand exceeds what PostgreSQL {} allows {}: this node's pools"
                                         + " open {} connections at warm-up and up to {} under load across {} pools {};"
                                         + " the server allows {} non-superuser connections for ALL its clients,"
                                         + " including other nodes. Pools only: listeners and pinned sessions are not"
                                         + " counted. Size max_connections (per node per datasource) for this node's peak"
                                         + " concurrent DB work, keep pools shared (pool_scope = \"node\", the default),"
                                         + " or raise the server limit. If a connection pooler sits in front, compare"
                                         + " against its limits instead.";

    /// One open pool: its configured server, its demand and its log label.
    record Registered(ServerId configured, int warm, int max, String label) {}

    /// The sums over the pools of one server.
    private record Demand(int warm, int max, int pools, List<String> labels) {}

    private final Map<Object, Registered> pools = new ConcurrentHashMap<>();
    private final Map<ServerId, ServerId> aliases = new ConcurrentHashMap<>();
    private final Map<ServerId, Integer> lastAvailable = new ConcurrentHashMap<>();
    private final Supplier<OperatorWarningSink> sink;

    private PoolDemandGuard(Supplier<OperatorWarningSink> sink) {
        this.sink = sink;
    }

    /// `sink` is read on every evaluation: the node registers its sink as a runtime extension after construction.
    static PoolDemandGuard poolDemandGuard(Supplier<OperatorWarningSink> sink) {
        return new PoolDemandGuard(sink);
    }

    /// Registers the pool `poolId` that just opened and starts its probe, detached.
    <T> Unit opened(Object poolId, T pool, NodeShareable<T, ?> shareable, PoolDemand demand, String label) {
        pools.put(poolId,
                  new Registered(demand.configured(), demand.warm(), demand.max(), label));

        return shareable.probe(pool)
                        .fold(() -> skipped(label),
                              probe -> probed(probe.timeout(demand.probeTimeout()),
                                              demand.configured()));
    }

    /// The close funnel's deregistration; a pool that never registered (a failed open) has nothing to remove.
    Unit closed(Object poolId) {
        pools.remove(poolId);

        return Unit.unit();
    }

    private static Unit skipped(String label) {
        LOG.debug("Datasource demand guard skipped for {}: not a PostgreSQL server", label);

        return Unit.unit();
    }

    private Unit probed(Promise<ServerProbe> probe, ServerId configured) {
        probe.onSuccess(answer -> answered(configured, answer)).onFailure(cause -> refused(configured, cause));

        return Unit.unit();
    }

    /// Records the answer, then evaluates the answered server's sums.
    Unit answered(ServerId configured, ServerProbe answer) {
        var server = answer.addr()
                           .map(addr -> ServerId.serverId(configured.type(),
                                                          addr,
                                                          answer.port()))
                           .or(configured);

        aliases.put(configured, server);
        lastAvailable.put(server, answer.available());

        return evaluate(server, answer.available());
    }

    private Unit evaluate(ServerId server, int available) {
        var demand = demandOn(server);

        if (demand.warm() > available) {
            return raise(OperatorWarningCode.DATASOURCE_DEMAND_EXCEEDS_SERVER_LIMIT,
                         server,
                         "at warm-up",
                         demand,
                         Integer.toString(available));
        }

        if (demand.max() > available) {
            return raise(OperatorWarningCode.DATASOURCE_DEMAND_EXCEEDS_SERVER_LIMIT,
                         server,
                         "under load",
                         demand,
                         Integer.toString(available));
        }

        return Unit.unit();
    }

    /// A probe that failed with SQLSTATE 53300 means the server is refusing connections now. Any other failure is not a
    /// statement about the limit and stays at DEBUG; the pool is unaffected either way.
    Unit refused(ServerId configured, Cause cause) {
        return isServerFull(cause)
               ? raiseRefused(configured)
               : probeFailed(configured, cause);
    }

    private Unit raiseRefused(ServerId configured) {
        var server = option(aliases.get(configured)).or(configured);
        var limit = option(lastAvailable.get(server)).map(Object::toString).or("unknown");

        return raise(OperatorWarningCode.DATASOURCE_SERVER_REFUSED_CONNECTION,
                     server,
                     "now - server refused a connection (53300)",
                     demandOn(server),
                     limit);
    }

    private static Unit probeFailed(ServerId configured, Cause cause) {
        LOG.debug("Datasource demand probe for {} failed: {}", configured.subject(), cause.message());

        return Unit.unit();
    }

    private static boolean isServerFull(Cause cause) {
        return cause instanceof DatabaseConnectorError error && error.sqlState()
                                                                     .filter(SERVER_FULL_SQLSTATE::equals)
                                                                     .isPresent();
    }

    private Demand demandOn(ServerId server) {
        var onServer = pools.values().stream().filter(registered -> serverOf(registered).equals(server)).toList();

        return new Demand(onServer.stream().mapToInt(Registered::warm).sum(),
                          onServer.stream().mapToInt(Registered::max).sum(),
                          onServer.size(),
                          onServer.stream().map(Registered::label).sorted().toList());
    }

    private ServerId serverOf(Registered registered) {
        return option(aliases.get(registered.configured())).or(registered.configured());
    }

    private Unit raise(OperatorWarningCode code, ServerId server, String when, Demand demand, String available) {
        return OperatorWarnings.raise(LOG,
                                      sink.get(),
                                      code,
                                      server.subject(),
                                      TEMPLATE,
                                      server.subject(),
                                      when,
                                      demand.warm(),
                                      demand.max(),
                                      demand.pools(),
                                      demand.labels(),
                                      available);
    }

    /// The registered pools, for tests.
    int registeredCount() {
        return pools.size();
    }

    /// The last limit cached for the server `configured` resolves to.
    Option<Integer> lastKnownLimit(ServerId configured) {
        return option(aliases.get(configured)).flatMap(server -> option(lastAvailable.get(server)));
    }
}
