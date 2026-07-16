// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.DhtInjectRequest;
import org.pragmatica.aether.api.ManagementApiResponses.DhtInjectResponse;
import org.pragmatica.aether.api.ManagementApiResponses.DhtReplicationEntry;
import org.pragmatica.aether.api.ManagementApiResponses.DhtReplicationMapResponse;
import org.pragmatica.aether.api.ManagementApiResponses.HlcShape;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// Management routes for the DHT subsystem. Two endpoints:
///   - `POST /api/dht/inject` (dev-mode-only) — write a value with an explicit HLC
///     timestamp so TC-10-G2 (DHT versioned writes) can build deterministic
///     version-conflict scenarios. Local-only target — tests POST directly to the
///     node they wish to mutate. Gated by `AETHER_INSECURE_DEV_MODE=true` (same gate
///     as `/api/alerts/inject`, `/api/scheduled-tasks/inject`, `/api/metrics/backfill`).
///   - `GET /api/dht/replication-map` — operator-facing inspection of the active
///     replication topology (which keys live on which nodes under the current
///     replication factor). Supports `?limit=N` and `?prefix=...` query params.
///
/// See `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-B / P-NEW-F.
public final class DhtRoutes implements RouteSource {
    private static final String DEV_MODE_ENV = "AETHER_INSECURE_DEV_MODE";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 10_000;

    private final Supplier<ManageableNode> nodeSupplier;
    private final BooleanSupplier devModeEnabled;

    private DhtRoutes(Supplier<ManageableNode> nodeSupplier, BooleanSupplier devModeEnabled) {
        this.nodeSupplier = nodeSupplier;
        this.devModeEnabled = devModeEnabled;
    }

    public static DhtRoutes dhtRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new DhtRoutes(nodeSupplier, DhtRoutes::devModeFromEnv);
    }

    /// Test-friendly factory: callers (unit tests) inject the dev-mode flag directly
    /// rather than mutating the JVM-wide environment.
    public static DhtRoutes dhtRoutes(Supplier<ManageableNode> nodeSupplier, BooleanSupplier devModeEnabled) {
        return new DhtRoutes(nodeSupplier, devModeEnabled);
    }

    private static boolean devModeFromEnv() {
        return "true".equalsIgnoreCase(System.getenv(DEV_MODE_ENV));
    }

    /// Package-private accessor for unit tests that exercise the inject handler without
    /// the HTTP layer.
    Promise<DhtInjectResponse> handleInjectForTest(DhtInjectRequest req) {
        return handleInject(req);
    }

    /// Package-private accessor for unit tests that exercise the replication-map handler
    /// without the HTTP layer.
    Promise<DhtReplicationMapResponse> handleReplicationMapForTest(Option<String> limit, Option<String> prefix) {
        return handleReplicationMap(limit, prefix);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<DhtInjectResponse> route(ManagementRoute.DHT_INJECT)
                                         .withBody(DhtInjectRequest.class)
                                         .toJson(this::handleInject),
                         ManagementRoutes.<DhtReplicationMapResponse> route(ManagementRoute.DHT_REPLICATION_MAP)
                                         .withQuery(QueryParameter.aString("limit"),
                                                    QueryParameter.aString("prefix"))
                                         .to(this::handleReplicationMap)
                                         .asJson());
    }

    // --- POST /api/dht/inject (dev-mode-only) ---
    private Promise<DhtInjectResponse> handleInject(DhtInjectRequest req) {
        if (!devModeEnabled.getAsBoolean()) {
            return DhtError.DEV_MODE_DISABLED.promise();
        }

        return validateInject(req).async()
                             .flatMap(this::executeInject);
    }

    private Result<DhtInjectRequest> validateInject(DhtInjectRequest req) {
        if (req == null) {
            return DhtError.MISSING_BODY.result();
        }

        if (req.key() == null || req.key().isBlank()) {
            return DhtError.MISSING_KEY.result();
        }

        if (req.value() == null) {
            return DhtError.MISSING_VALUE.result();
        }

        if (req.hlc() == null) {
            return DhtError.MISSING_HLC.result();
        }

        return Result.success(req);
    }

    private Promise<DhtInjectResponse> executeInject(DhtInjectRequest req) {
        return resolveDhtNode().flatMap(node -> writeVersioned(node, req));
    }

    private Promise<DhtInjectResponse> writeVersioned(DHTNode node, DhtInjectRequest req) {
        return mergeHlc(node,
                        req.hlc()).async()
                       .flatMap(committed -> putAndWrap(node, req, committed));
    }

    private Result<HlcTimestamp> mergeHlc(DHTNode node, HlcShape requested) {
        var remote = new HlcTimestamp(HlcTimestamp.pack(requested.physical(), requested.logical()),
                                      node.nodeId());

        return node.hlcClock()
                   .update(remote);
    }

    private Promise<DhtInjectResponse> putAndWrap(DHTNode node, DhtInjectRequest req, HlcTimestamp committed) {
        var keyBytes = req.key().getBytes(StandardCharsets.UTF_8);
        var valueBytes = req.value().getBytes(StandardCharsets.UTF_8);

        return node.putLocalVersioned(keyBytes,
                                      valueBytes,
                                      committed.packed())
                   .map(written -> toInjectResponse(req.key(),
                                                    committed,
                                                    written));
    }

    private static DhtInjectResponse toInjectResponse(String key, HlcTimestamp committed, boolean written) {
        return new DhtInjectResponse(key,
                                     new HlcShape(committed.physicalMillis(), committed.counter()),
                                     written);
    }

    // --- GET /api/dht/replication-map ---
    private Promise<DhtReplicationMapResponse> handleReplicationMap(Option<String> rawLimit, Option<String> rawPrefix) {
        return resolveDhtNode().flatMap(node -> buildReplicationMap(node, rawLimit, rawPrefix));
    }

    private Promise<DhtReplicationMapResponse> buildReplicationMap(DHTNode node,
                                                                   Option<String> rawLimit,
                                                                   Option<String> rawPrefix) {
        var limit = clampLimit(rawLimit);
        var prefix = rawPrefix.or("");

        return node.storage()
                   .keys()
                   .map(keys -> assembleResponse(node, keys, limit, prefix));
    }

    private static int clampLimit(Option<String> raw) {
        return raw.flatMap(DhtRoutes::parsePositiveIntAsOption)
                  .map(v -> Math.min(v, MAX_LIMIT))
                  .or(DEFAULT_LIMIT);
    }

    private static Option<Integer> parsePositiveIntAsOption(String raw) {
        return org.pragmatica.lang.parse.Number.parseInt(raw)
                                               .option()
                                               .filter(v -> v > 0);
    }

    private static DhtReplicationMapResponse assembleResponse(DHTNode node,
                                                              List<byte[]> keys,
                                                              int limit,
                                                              String prefix) {
        var replicationFactor = node.config().effectiveReplicationFactor(node.ring().nodeCount());
        var prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        var matched = keys.stream().filter(key -> matchesPrefix(key, prefixBytes)).toList();
        var entries = matched.stream()
                             .limit(limit)
                             .map(key -> toReplicationEntry(node, key, replicationFactor))
                             .toList();

        return new DhtReplicationMapResponse(replicationFactor, matched.size(), entries.size(), entries);
    }

    private static boolean matchesPrefix(byte[] key, byte[] prefix) {
        if (prefix.length == 0) {
            return true;
        }

        if (key.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    private static DhtReplicationEntry toReplicationEntry(DHTNode node, byte[] key, int replicationFactor) {
        var nodeIds = node.ring()
                          .nodesFor(key, replicationFactor)
                          .stream()
                          .map(org.pragmatica.consensus.NodeId::id)
                          .toList();

        return new DhtReplicationEntry(new String(key, StandardCharsets.UTF_8), nodeIds);
    }

    // --- shared helpers ---
    private Promise<DHTNode> resolveDhtNode() {
        return nodeSupplier.get()
                           .dhtNode()
                           .async(DhtError.DHT_UNAVAILABLE);
    }

    private enum DhtError implements Cause {
        DEV_MODE_DISABLED("dht inject requires AETHER_INSECURE_DEV_MODE=true"),
        DHT_UNAVAILABLE("DHT subsystem not available on this node"),
        MISSING_BODY("Request body is required"),
        MISSING_KEY("key field is required"),
        MISSING_VALUE("value field is required"),
        MISSING_HLC("hlc field is required");
        private final String message;
        DhtError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
