// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.slice.stream.DeclarationDefaults;
import org.pragmatica.aether.stream.segment.SealerThresholds;

import static org.assertj.core.api.Assertions.assertThat;

/// #3334 S2a1-1: the four seal defaults live as literals in three modules (`StreamingConfig` in `aether-config`,
/// `SealerThresholds` in `aether-stream`, `DeclarationDefaults` in `slice`), because none of them may depend on the
/// others. `node` sees all three, so the copies are pinned equal here: a default changed in one place only goes red.
class DeclarationSealMirrorTest {
    @Test
    void sealRunBytes_isTheSameInEveryCopy() {
        assertThat(DeclarationDefaults.SEAL_RUN_BYTES).isEqualTo(StreamingConfig.DEFAULT_SEAL_RUN_BYTES)
                                                      .isEqualTo(SealerThresholds.DEFAULT_RUN_BYTES);
    }

    @Test
    void sealRunEvents_isTheSameInEveryCopy() {
        assertThat(DeclarationDefaults.SEAL_RUN_EVENTS).isEqualTo(StreamingConfig.DEFAULT_SEAL_RUN_EVENTS)
                                                       .isEqualTo(SealerThresholds.DEFAULT_RUN_EVENTS);
    }

    @Test
    void sealRunAge_isTheSameInEveryCopy() {
        assertThat(DeclarationDefaults.SEAL_RUN_AGE).isEqualTo(StreamingConfig.DEFAULT_SEAL_RUN_AGE)
                                                    .isEqualTo(SealerThresholds.DEFAULT_RUN_AGE);
    }

    @Test
    void sealMergeBytes_isTheSameInEveryCopy() {
        assertThat(DeclarationDefaults.SEAL_MERGE_BYTES).isEqualTo(StreamingConfig.DEFAULT_SEAL_MERGE_BYTES)
                                                        .isEqualTo(SealerThresholds.DEFAULT_MERGE_BYTES);
    }
}
