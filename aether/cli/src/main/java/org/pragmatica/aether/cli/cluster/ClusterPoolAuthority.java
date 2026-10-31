// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_AUTO_HEAL_STATUS;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.NODES_LIVE;
import static org.pragmatica.lang.Result.success;


/// The running cluster's answer to "who creates the nodes of this pool" ([PoolAuthority]), read from its management API.
///
/// What the leader fills (read from `ClusterTopologyManagerRecord`, `LeaderReconciler`; G1-g):
/// - CORE: `LeaderReconciler` provisions a sustained deficit against the committed core count, but only while auto-heal is ENABLED
///   (the operator's kill switch gates it). So a core pool is the leader's with the switch on and apply's with it off.
/// - WORKER and SPOT: `reconcileWorkerTopology` fills every non-core committed `(source, role)` entry from the provider's label inventory,
///   and the switch does NOT gate it (it holds only automatic DELETIONS). An explicit-community worker pool is filled by the community
///   placement reconciler instead; either way the leader. So a non-core pool is the leader's whatever the switch says.
/// - Only a pool whose entry is COMMITTED at the wanted count is the leader's to fill: an entry it has not been given (apply has not
///   committed the config, or committed a smaller one) is never filled, so waiting on it would wait forever. That is a refusal.
final class ClusterPoolAuthority implements PoolAuthority {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(10);

    private final Function<ManagementRoute, Result<String>> fetch;
    private final Consumer<Duration> pause;
    private final LongSupplier clockMillis;
    private final Duration timeout;
    private final Duration interval;

    private ClusterPoolAuthority(Function<ManagementRoute, Result<String>> fetch,
                                 Consumer<Duration> pause,
                                 LongSupplier clockMillis,
                                 Duration timeout,
                                 Duration interval) {
        this.fetch = fetch;
        this.pause = pause;
        this.clockMillis = clockMillis;
        this.timeout = timeout;
        this.interval = interval;
    }

    static ClusterPoolAuthority production() {
        return clusterPoolAuthority(ClusterHttpClient::fetch,
                                    ClusterPoolAuthority::sleep,
                                    System::currentTimeMillis,
                                    DEFAULT_TIMEOUT,
                                    DEFAULT_INTERVAL);
    }

    static ClusterPoolAuthority clusterPoolAuthority(Function<ManagementRoute, Result<String>> fetch,
                                                     Consumer<Duration> pause,
                                                     LongSupplier clockMillis,
                                                     Duration timeout,
                                                     Duration interval) {
        return new ClusterPoolAuthority(fetch, pause, clockMillis, timeout, interval);
    }

    @Override
    public Result<Actor> actorFor(String source, NodeRole role, int wanted) {
        return autoHealEnabled().flatMap(enabled -> role == NodeRole.CORE && !enabled
                                                    ? success(Actor.APPLY)
                                                    : leaderIfCommitted(source, role, wanted));
    }

    private Result<Boolean> autoHealEnabled() {
        return fetchJson(CLUSTER_AUTO_HEAL_STATUS, "the auto-heal setting").map(json -> json.path("enabled")
                                                                                            .asBoolean(true));
    }

    private Result<Actor> leaderIfCommitted(String source, NodeRole role, int wanted) {
        return fetchJson(CLUSTER_CONFIG_GET, "the committed config").flatMap(json -> decide(json, source, role, wanted));
    }

    private static Result<Actor> decide(JsonNode config, String source, NodeRole role, int wanted) {
        var committed = committedCount(config, source, role);

        return committed >= wanted
               ? success(Actor.LEADER)
               : new PoolRefused(source,
                                 role,
                                 "the leader's committed target is " + committed
                                + " and this apply needs " + wanted
                                + "; a pool the leader was not given is never filled by it, so apply would wait for nothing."
                                + " Commit the config first (a plain `aether cluster apply <file>`), then `--resume`").result();
    }

    private static int committedCount(JsonNode config, String source, NodeRole role) {
        for (var entry : config.path("desiredTopology")) {
            if (source.equals(entry.path("sourceName").asText()) && role.value().equals(entry.path("role").asText())) {
                return entry.path("count")
                            .asInt(0);
            }
        }

        return 0;
    }

    @Override
    public Result<Unit> awaitLeader(NodeRole role, int wantedInRole, Consumer<String> progress) {
        var started = clockMillis.getAsLong();

        while (true) {
            var live = liveCount(role);

            if (live.isFailure()) {
                return live.map(_ -> Unit.unit());
            }

            var count = live.or(0);

            progress.accept("waiting for the leader to provision " + role.value()
                           + ": " + count
                           + " live of " + wantedInRole);
            if (count >= wantedInRole) {
                return Result.unitResult();
            }

            var waited = clockMillis.getAsLong() - started;

            if (waited >= timeout.toMillis()) {
                return new LeaderDidNotConverge(role, count, wantedInRole, waited / 1000).result();
            }

            pause.accept(interval);
        }
    }

    private Result<Integer> liveCount(NodeRole role) {
        return fetchJson(NODES_LIVE, "the live nodes").map(json -> liveOfRole(json, role));
    }

    private static int liveOfRole(JsonNode liveNodes, NodeRole role) {
        var live = 0;

        for (var node : liveNodes.path("nodes")) {
            if (role.value().equalsIgnoreCase(node.path("role").asText("")) && node.path("swimAlive").asBoolean(false)) {
                live++;
            }
        }

        return live;
    }

    private Result<JsonNode> fetchJson(ManagementRoute route, String what) {
        return fetch.apply(route)
                    .flatMap(MAPPER::readTree)
                    .mapError(cause -> new PoolStateUnreadable(what, cause));
    }

    @Contract
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
