// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Phase 3 PR-C — covers the parse helpers on `AuditCommandsRoutes` and the buffer-
/// fed snapshot pipeline. The route stream registration is exercised end-to-end by
/// the integration test suite; this test focuses on the parsing surface.
class AuditCommandsRoutesTest {
    private static final long ONE_SECOND_MS = 1_000L;
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long ONE_DAY_MS = 86_400_000L;

    @Nested
    class ParseSince {
        @Test
        void parseSinceMs_acceptsEpochMillis() {
            assertThat(AuditCommandsRoutes.parseSinceMs("1700000000000")).isEqualTo(1_700_000_000_000L);
        }

        @Test
        void parseSinceMs_acceptsIso8601() {
            var parsed = AuditCommandsRoutes.parseSinceMs("2026-05-23T10:00:00Z");
            assertThat(parsed).isGreaterThan(0L);
        }

        @Test
        void parseSinceMs_acceptsRelativeSeconds() {
            var before = System.currentTimeMillis();
            var parsed = AuditCommandsRoutes.parseSinceMs("30s");
            var after = System.currentTimeMillis();
            assertThat(parsed).isBetween(before - 30 * ONE_SECOND_MS - 100, after - 30 * ONE_SECOND_MS + 100);
        }

        @Test
        void parseSinceMs_acceptsRelativeMinutes() {
            var before = System.currentTimeMillis();
            var parsed = AuditCommandsRoutes.parseSinceMs("5m");
            var after = System.currentTimeMillis();
            assertThat(parsed).isBetween(before - 5 * ONE_MINUTE_MS - 100, after - 5 * ONE_MINUTE_MS + 100);
        }

        @Test
        void parseSinceMs_acceptsRelativeHours() {
            var before = System.currentTimeMillis();
            var parsed = AuditCommandsRoutes.parseSinceMs("1h");
            var after = System.currentTimeMillis();
            assertThat(parsed).isBetween(before - ONE_HOUR_MS - 100, after - ONE_HOUR_MS + 100);
        }

        @Test
        void parseSinceMs_acceptsRelativeDays() {
            var before = System.currentTimeMillis();
            var parsed = AuditCommandsRoutes.parseSinceMs("2d");
            var after = System.currentTimeMillis();
            assertThat(parsed).isBetween(before - 2 * ONE_DAY_MS - 100, after - 2 * ONE_DAY_MS + 100);
        }

        @Test
        void parseSinceMs_returnsZeroOnNull() {
            assertThat(AuditCommandsRoutes.parseSinceMs(null)).isZero();
        }

        @Test
        void parseSinceMs_returnsZeroOnEmpty() {
            assertThat(AuditCommandsRoutes.parseSinceMs("")).isZero();
            assertThat(AuditCommandsRoutes.parseSinceMs("   ")).isZero();
        }

        @Test
        void parseSinceMs_returnsZeroOnGarbage() {
            assertThat(AuditCommandsRoutes.parseSinceMs("not-a-time")).isZero();
        }

        @Test
        void parseSinceMs_returnsZeroOnUnknownUnit() {
            assertThat(AuditCommandsRoutes.parseSinceMs("5z")).isZero();
        }
    }

    @Nested
    class ParseLimit {
        @Test
        void parseLimit_acceptsPositiveInteger() {
            assertThat(AuditCommandsRoutes.parseLimit("250")).isEqualTo(250);
        }

        @Test
        void parseLimit_defaultsOnNull() {
            assertThat(AuditCommandsRoutes.parseLimit(null)).isEqualTo(100);
        }

        @Test
        void parseLimit_defaultsOnEmpty() {
            assertThat(AuditCommandsRoutes.parseLimit("")).isEqualTo(100);
        }

        @Test
        void parseLimit_defaultsOnGarbage() {
            assertThat(AuditCommandsRoutes.parseLimit("ten")).isEqualTo(100);
        }

        @Test
        void parseLimit_defaultsOnZero() {
            assertThat(AuditCommandsRoutes.parseLimit("0")).isEqualTo(100);
        }

        @Test
        void parseLimit_defaultsOnNegative() {
            assertThat(AuditCommandsRoutes.parseLimit("-5")).isEqualTo(100);
        }
    }

    @Nested
    class NormalizeSourceFilter {
        @Test
        void normalizeSourceFilter_uppercasesSource() {
            assertThat(AuditCommandsRoutes.normalizeSourceFilter("operator")).isEqualTo("OPERATOR");
        }

        @Test
        void normalizeSourceFilter_returnsNullForAll() {
            assertThat(AuditCommandsRoutes.normalizeSourceFilter("ALL")).isNull();
            assertThat(AuditCommandsRoutes.normalizeSourceFilter("all")).isNull();
        }

        @Test
        void normalizeSourceFilter_returnsNullForEmpty() {
            assertThat(AuditCommandsRoutes.normalizeSourceFilter("")).isNull();
            assertThat(AuditCommandsRoutes.normalizeSourceFilter("   ")).isNull();
            assertThat(AuditCommandsRoutes.normalizeSourceFilter(null)).isNull();
        }
    }
}
