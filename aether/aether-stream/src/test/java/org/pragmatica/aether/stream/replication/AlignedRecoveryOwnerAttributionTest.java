// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.generation.Epoch;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// I-25 (#1938, v2858a R1): `applyOwnerAttributed` is `applyAttributed` wherever nothing distinguishes the committed owner's
/// answer: the interface default and the three-argument seam both route it through the attributed applier, never straight to
/// the page's appends (which would skip the install and the settle of N13).
class AlignedRecoveryOwnerAttributionTest {
    private final List<String> calls = new ArrayList<>();

    @Test
    void default_routesTheOwnerPageThroughApplyAttributed() {
        AlignedRecovery recovery = new AlignedRecovery() {
            @Override
            public Result<Long> appendRecovered(String streamName, int partition, long offset, byte[] payload, long timestamp) {
                return Result.success(offset);
            }

            @Override
            public Result<Long> applyAttributed(String streamName, int partition, long fromOffset, long toOffset,
                                                List<org.pragmatica.aether.stream.provenance.ProvenanceEntry> slice, PageApply apply) {
                calls.add("attributed");
                return Result.success(7L);
            }

            @Override
            public Result<Long> applyUnattributed(String streamName, int partition, long toOffset, PageApply apply) {
                return Result.success(toOffset);
            }
        };

        var result = recovery.applyOwnerAttributed("s", 0, 0L, 1L, Epoch.ZERO, List.of(), () -> {
            calls.add("apply");
            return Result.success(1L);
        });

        assertThat(result.unwrap()).isEqualTo(7L);
        assertThat(calls).as("the page is applied only by the attributed applier").containsExactly("attributed");
    }

    @Test
    void threeArgumentSeam_routesTheOwnerPageThroughTheAttributedApplier() {
        var recovery = AlignedRecovery.alignedRecovery((_, _, offset, _, _) -> Result.success(offset),
                                                       (stream, partition, from, to, _, _) -> {
                                                           calls.add("attributed:" + stream + partition);
                                                           return Result.success(from * 10 + to);
                                                       },
                                                       (_, _, toOffset, _) -> Result.success(toOffset));

        var result = recovery.applyOwnerAttributed("s", 3, 2L, 5L, Epoch.ZERO, List.of(), () -> {
            calls.add("apply");
            return Result.success(1L);
        });

        assertThat(result.unwrap()).as("offsets reach the attributed applier unchanged").isEqualTo(25L);
        assertThat(calls).as("stream and partition reach it unchanged").containsExactly("attributed:s3");
    }
}
