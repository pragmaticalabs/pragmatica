// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
/// written on every pool open while the condition holds.
///
/// Recovery: the guard tracks which servers it has warned about (`overLimit`, `refusing`) and publishes the INFO
/// recovery for the same subject when the condition no longer holds. [OperatorWarningCode#DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT]
/// follows a demand warning when a later probe answer, or a pool close against the last answered limit, finds the
/// server's sums back within it. [OperatorWarningCode#DATASOURCE_SERVER_ACCEPTING_CONNECTIONS] follows a refusal when a
/// later probe of that server is answered. A warning for a server that never gets another probe or close stays open
/// until the node restarts: no event exists to clear it.
final class PoolDemandGuard {
    private static final Logger LOG = LoggerFactory.getLogger(PoolDemandGuard.class);
    private static final String SERVER_FULL_SQLSTATE = "53300";

    private static final String WITHIN_TEMPLATE = "Datasource pool demand on PostgreSQL {} is within what the server allows"
                                                + " again: this node's pools open {} connections at warm-up and up to {}"
                                                + " under load, the server allows {} non-superuser connections.";

    private static final String RETIRED_TEMPLATE = "PostgreSQL {} is no longer where this node's datasource pools"
                                                 + " connect: their configured server now answers as another address."
                                                 + " The earlier warning for it is cleared.";

    private static final String ACCEPTING_TEMPLATE = "PostgreSQL {} answered the demand probe again after refusing a"
                                                   + " connection (53300); the server allows {} non-superuser connections.";

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
    /// The warning sets are guarded by `this`, together with the publication of every raise and recovery (see
    /// [#answered], [#refused], [#closed]): a recovery interleaved with a raise on another thread can then neither
    /// publish a warning that no set entry stands for, nor leave an entry whose warning was never published.
    private final Set<ServerId> overLimit = new HashSet<>();
    private final Set<ServerId> refusing = new HashSet<>();
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

    /// The close funnel's deregistration; a pool that never registered (a failed open) has nothing to remove. The
    /// close lowers the server's sums, so it is also a chance to clear a standing demand warning.
    synchronized Unit closed(Object poolId) {
        return option(pools.remove(poolId)).map(this::clearAfterClose)
                     .or(Unit.unit());
    }

    private Unit clearAfterClose(Registered registered) {
        var server = serverOf(registered);

        return option(lastAvailable.get(server)).map(available -> clearIfWithin(server, available))
                     .or(Unit.unit());
    }

    private Unit clearIfWithin(ServerId server, int available) {
        var demand = demandOn(server);

        return demand.max() <= available
               ? recoverDemand(server, demand, available)
               : Unit.unit();
    }

    private Unit recoverDemand(ServerId server, Demand demand, int available) {
        return overLimit.remove(server)
               ? OperatorWarnings.raise(LOG,
                                        sink.get(),
                                        OperatorWarningCode.DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT,
                                        server.subject(),
                                        WITHIN_TEMPLATE,
                                        server.subject(),
                                        demand.warm(),
                                        demand.max(),
                                        available)
               : Unit.unit();
    }

    /// A probe answer proves the server accepts connections: it closes a standing refusal, for the id the refusal was
    /// raised under (the configured one before any answer, the answered one after).
    private Unit acceptedAgain(ServerId configured, ServerId answered, int available) {
        Stream.of(answered, configured).distinct().filter(refusing::remove).forEach(id -> raiseAccepting(id, available));

        return Unit.unit();
    }

    private Unit raiseAccepting(ServerId server, int available) {
        return OperatorWarnings.raise(LOG,
                                      sink.get(),
                                      OperatorWarningCode.DATASOURCE_SERVER_ACCEPTING_CONNECTIONS,
                                      server.subject(),
                                      ACCEPTING_TEMPLATE,
                                      server.subject(),
                                      available);
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
    synchronized Unit answered(ServerId configured, ServerProbe answer) {
        var server = answer.addr()
                           .map(addr -> ServerId.serverId(configured.type(),
                                                          addr,
                                                          answer.port()))
                           .or(configured);

        option(aliases.put(configured, server)).filter(previous -> !previous.equals(server))
              .onPresent(this::retireIfUnused);
        lastAvailable.put(server, answer.available());
        acceptedAgain(configured, server, answer.available());

        return evaluate(server, answer.available());
    }

    /// The configured server was remapped away from `previous` to a different answered id, so a warning standing under
    /// `previous` would never see its clearing event if no pool is left there: it is cleared now, with its recovery
    /// event. A pool still resolving to `previous` keeps the warning: that server's own probes and closes clear it.
    private Unit retireIfUnused(ServerId previous) {
        return pools.values()
                    .stream()
                    .anyMatch(registered -> serverOf(registered).equals(previous))
               ? Unit.unit()
               : retire(previous);
    }

    private Unit retire(ServerId previous) {
        var demandCleared = overLimit.remove(previous)
                            ? raiseRetired(OperatorWarningCode.DATASOURCE_DEMAND_WITHIN_SERVER_LIMIT, previous)
                            : Unit.unit();

        return refusing.remove(previous)
               ? raiseRetired(OperatorWarningCode.DATASOURCE_SERVER_ACCEPTING_CONNECTIONS, previous)
               : demandCleared;
    }

    private Unit raiseRetired(OperatorWarningCode code, ServerId previous) {
        return OperatorWarnings.raise(LOG, sink.get(), code, previous.subject(), RETIRED_TEMPLATE, previous.subject());
    }

    private Unit evaluate(ServerId server, int available) {
        var demand = demandOn(server);

        if (demand.warm() > available) {
            return raiseExceeding(server, "at warm-up", demand, available);
        }

        if (demand.max() > available) {
            return raiseExceeding(server, "under load", demand, available);
        }

        return recoverDemand(server, demand, available);
    }

    private Unit raiseExceeding(ServerId server, String when, Demand demand, int available) {
        overLimit.add(server);

        return raise(OperatorWarningCode.DATASOURCE_DEMAND_EXCEEDS_SERVER_LIMIT,
                     server,
                     when,
                     demand,
                     Integer.toString(available));
    }

    /// A probe that failed with SQLSTATE 53300 means the server is refusing connections now. Any other failure is not a
    /// statement about the limit and stays at DEBUG; the pool is unaffected either way.
    synchronized Unit refused(ServerId configured, Cause cause) {
        return isServerFull(cause)
               ? raiseRefused(configured)
               : probeFailed(configured, cause);
    }

    private Unit raiseRefused(ServerId configured) {
        var server = option(aliases.get(configured)).or(configured);
        var limit = option(lastAvailable.get(server)).map(Object::toString).or("unknown");

        refusing.add(server);

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
