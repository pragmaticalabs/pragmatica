// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;


/// Tests for the await-quiesced epoch/timeout query parsing helpers.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §14.1.
class ClusterAwaitQuiescedRoutesTest {

    @Nested
    class EpochParsing {
        @Test
        void parseEpoch_validForm_succeeds() {
            var parsed = ClusterAwaitQuiescedRoute.parseEpoch(Option.some("7:142"));
            assertThat(parsed.isSuccess()).isTrue();
            parsed.onSuccess(epoch -> {
                assertThat(epoch.rabiaTerm()).isEqualTo(7L);
                assertThat(epoch.localCounter()).isEqualTo(142L);
            });
        }

        @Test
        void parseEpoch_zeroCounter_succeeds() {
            var parsed = ClusterAwaitQuiescedRoute.parseEpoch(Option.some("3:0"));
            assertThat(parsed.isSuccess()).isTrue();
        }

        @Test
        void parseEpoch_missingParam_fails() {
            assertThat(ClusterAwaitQuiescedRoute.parseEpoch(Option.none()).isFailure()).isTrue();
        }

        @Test
        void parseEpoch_singleNumber_fails() {
            assertThat(ClusterAwaitQuiescedRoute.parseEpoch(Option.some("7")).isFailure()).isTrue();
        }

        @Test
        void parseEpoch_nonNumeric_fails() {
            assertThat(ClusterAwaitQuiescedRoute.parseEpoch(Option.some("a:b")).isFailure()).isTrue();
        }

        @Test
        void parseEpoch_tooManyParts_fails() {
            assertThat(ClusterAwaitQuiescedRoute.parseEpoch(Option.some("1:2:3")).isFailure()).isTrue();
        }

        @Test
        void parseEpoch_emptyString_fails() {
            assertThat(ClusterAwaitQuiescedRoute.parseEpoch(Option.some("")).isFailure()).isTrue();
        }
    }

    @Nested
    class TimeoutParsing {
        @Test
        void parseTimeout_missingParam_returnsDefault30s() {
            var parsed = ClusterAwaitQuiescedRoute.parseTimeout(Option.none());
            assertThat(parsed.isSuccess()).isTrue();
            parsed.onSuccess(ts -> assertThat(ts.millis()).isEqualTo(30_000L));
        }

        @Test
        void parseTimeout_secondsSuffix_succeeds() {
            var parsed = ClusterAwaitQuiescedRoute.parseTimeout(Option.some("15s"));
            assertThat(parsed.isSuccess()).isTrue();
            parsed.onSuccess(ts -> assertThat(ts.millis()).isEqualTo(15_000L));
        }

        @Test
        void parseTimeout_plainNumber_treatedAsSeconds() {
            var parsed = ClusterAwaitQuiescedRoute.parseTimeout(Option.some("45"));
            assertThat(parsed.isSuccess()).isTrue();
            parsed.onSuccess(ts -> assertThat(ts.millis()).isEqualTo(45_000L));
        }

        @Test
        void parseTimeout_clampedToMax120s() {
            var parsed = ClusterAwaitQuiescedRoute.parseTimeout(Option.some("999s"));
            assertThat(parsed.isSuccess()).isTrue();
            parsed.onSuccess(ts -> assertThat(ts.millis()).isEqualTo(120_000L));
        }

        @Test
        void parseTimeout_nonNumeric_fails() {
            assertThat(ClusterAwaitQuiescedRoute.parseTimeout(Option.some("xyz")).isFailure()).isTrue();
        }
    }
}
