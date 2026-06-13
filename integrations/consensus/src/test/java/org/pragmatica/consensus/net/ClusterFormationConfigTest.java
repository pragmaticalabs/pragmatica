/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class ClusterFormationConfigTest {

    @Test
    void defaults_returnsFiveSecondWindows() {
        var config = ClusterFormationConfig.defaults();

        assertThat(config.stabilizationWindow()).isEqualTo(timeSpan(5).seconds());
        assertThat(config.postEstablishGrace()).isEqualTo(timeSpan(5).seconds());
        assertThat(config.quorumLossHysteresis()).isEqualTo(timeSpan(5).seconds());
    }

    @Test
    void clusterFormationConfig_acceptsValidPositiveDurations() {
        var result = ClusterFormationConfig.clusterFormationConfig(timeSpan(1).seconds(),
                                                                   timeSpan(2).seconds(),
                                                                   timeSpan(3).seconds());

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(config -> {
            assertThat(config.stabilizationWindow()).isEqualTo(timeSpan(1).seconds());
            assertThat(config.postEstablishGrace()).isEqualTo(timeSpan(2).seconds());
            assertThat(config.quorumLossHysteresis()).isEqualTo(timeSpan(3).seconds());
        });
    }

    @Test
    void clusterFormationConfig_rejectsZeroStabilization() {
        var result = ClusterFormationConfig.clusterFormationConfig(TimeSpan.timeSpan(0).millis(),
                                                                   timeSpan(1).seconds(),
                                                                   timeSpan(1).seconds());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("stabilizationWindow"));
    }

    @Test
    void clusterFormationConfig_rejectsZeroPostEstablishGrace() {
        var result = ClusterFormationConfig.clusterFormationConfig(timeSpan(1).seconds(),
                                                                   TimeSpan.timeSpan(0).millis(),
                                                                   timeSpan(1).seconds());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("postEstablishGrace"));
    }

    @Test
    void clusterFormationConfig_rejectsZeroQuorumLossHysteresis() {
        var result = ClusterFormationConfig.clusterFormationConfig(timeSpan(1).seconds(),
                                                                   timeSpan(1).seconds(),
                                                                   TimeSpan.timeSpan(0).millis());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("quorumLossHysteresis"));
    }
}
