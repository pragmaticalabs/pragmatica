// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.aether.stream.segment.SealerThresholds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2201 C-02: each `[streaming] seal_*` key reaches its own sealer threshold, and the sealer's defaults, duplicated as
/// literals in `aether-stream`, agree with the config's.
class StreamAssemblySealerThresholdsTest {
    @Test
    void eachSealKey_mapsToItsOwnThreshold() {
        var streaming = StreamingConfig.streamingConfig().withSealRun(11, 22, timeSpan(33).millis(), 44);

        assertThat(StreamAssembly.sealerThresholds(streaming)).isEqualTo(SealerThresholds.sealerThresholds(11, 22, timeSpan(33).millis(), 44));
    }

    @Test
    void theSealerDefaults_agreeWithTheConfigDefaults() {
        assertThat(StreamAssembly.sealerThresholds(StreamingConfig.streamingConfig())).isEqualTo(SealerThresholds.defaults());
    }
}
