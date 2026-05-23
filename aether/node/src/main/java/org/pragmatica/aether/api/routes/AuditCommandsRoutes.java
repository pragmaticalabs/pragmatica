// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.audit.RecentCommandsBuffer;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.routing.QueryParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;


/// `GET /api/audit/commands` — Phase 3 PR-C operator audit subscription endpoint.
///
/// Backed by the local-node `RecentCommandsBuffer` populated via a tee on the
/// `audit.lifecycle.commands` publisher. Returns the most recent events seen by *this*
/// node (after applying optional time + source filters) — a follower will only surface
/// events emitted via writers running on this same node, so for cluster-wide visibility
/// operators should target the leader (`-c <leader-host>`).
///
/// Query parameters:
///   - `since` — `Long` epoch-millis OR ISO-8601 string OR relative duration suffix
///                (`30s`, `5m`, `1h`, `2d`). When absent, all entries currently in the
///                buffer are returned (subject to `limit`).
///   - `source` — case-insensitive match against `CommandLifecycleEvent.source()`.
///                Common values: `OPERATOR`, `RECONCILER`, `CTM`, `DRAIN_COORDINATOR`,
///                `BOOTSTRAP`, `UNKNOWN`. `all` / empty string returns all sources.
///   - `limit` — most-recent N entries. Defaults to 100. Capped at buffer capacity.
///
/// **Scope note (RC2 follow-up):** the in-memory ring buffer is per-node and does not
/// survive restarts. Building a proper subscription on top of `StreamReadRouter` with
/// Codec round-trip is deferred — the audit channel is observability, not the source of
/// truth (the KV-Store is). See `RecentCommandsBuffer` Javadoc for design rationale.
public final class AuditCommandsRoutes implements RouteSource {
    private static final int DEFAULT_LIMIT = 100;

    private final Supplier<ManageableNode> nodeSupplier;

    private AuditCommandsRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static AuditCommandsRoutes auditCommandsRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new AuditCommandsRoutes(nodeSupplier);
    }

    record AuditCommandsResponse(List<CommandLifecycleEvent> events) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<AuditCommandsResponse>route(ManagementRoute.AUDIT_COMMANDS_LIST)
                                         .withQuery(QueryParameter.aString("since"),
                                                    QueryParameter.aString("source"),
                                                    QueryParameter.aString("limit"))
                                         .toValue(this::listAuditCommands)
                                         .asJson());
    }

    private AuditCommandsResponse listAuditCommands(Option<String> since,
                                                    Option<String> source,
                                                    Option<String> limit) {
        var sinceMs = since.map(AuditCommandsRoutes::parseSinceMs).or(0L);
        var sourceFilter = source.or((String) null);
        var limitValue = limit.map(AuditCommandsRoutes::parseLimit).or(DEFAULT_LIMIT);

        var events = recentCommandsBuffer().snapshot(sinceMs, sourceFilter, limitValue);
        return new AuditCommandsResponse(events);
    }

    private RecentCommandsBuffer recentCommandsBuffer() {
        return nodeSupplier.get().recentCommandsBuffer();
    }

    /// Accepts:
    ///   - bare digits         → epoch-millis.
    ///   - ISO-8601 timestamp  → parsed via `Instant.parse`.
    ///   - relative duration   → `<N><unit>` where unit ∈ {s, m, h, d} — interpreted as
    ///                           "N units ago from now".
    /// Returns `0L` on any parse failure — the calling route treats `0L` as "no time
    /// filter" so a malformed `since` degrades gracefully into "return everything".
    static long parseSinceMs(String raw) {
        if (raw == null) {
            return 0L;
        }
        var trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return 0L;
        }
        var asLong = tryParseLong(trimmed);
        if (asLong > 0L) {
            return asLong;
        }
        var asRelative = tryParseRelative(trimmed);
        if (asRelative > 0L) {
            return asRelative;
        }
        return tryParseInstant(trimmed);
    }

    static int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        var trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_LIMIT;
        }
        var parsed = tryParseInt(trimmed);
        return parsed > 0
               ? parsed
               : DEFAULT_LIMIT;
    }

    @SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01"})
    private static long tryParseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01"})
    private static int tryParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01"})
    private static long tryParseInstant(String s) {
        try {
            return Instant.parse(s).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    @SuppressWarnings({"JBCT-EX-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
    private static long tryParseRelative(String s) {
        if (s.length() < 2) {
            return 0L;
        }
        var unit = Character.toLowerCase(s.charAt(s.length() - 1));
        var numericPart = s.substring(0, s.length() - 1);
        long magnitude;
        try {
            magnitude = Long.parseLong(numericPart);
        } catch (NumberFormatException e) {
            return 0L;
        }
        var multiplier = relativeMultiplier(unit);
        if (multiplier <= 0L) {
            return 0L;
        }
        return System.currentTimeMillis() - (magnitude * multiplier);
    }

    private static long relativeMultiplier(char unit) {
        return switch (Character.toLowerCase(unit)) {
            case 's' -> 1_000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> 0L;
        };
    }

    /// Helper kept package-private for unit-test use — normalizes a free-form source filter
    /// for the same semantics the buffer applies.
    static String normalizeSourceFilter(String raw) {
        if (raw == null) {
            return null;
        }
        var trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("all")) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
