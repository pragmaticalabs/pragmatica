// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.serialization.Codec;


/// One per-minute (or rolled-up per-hour) capacity record of the system streams `capacity-minute` and
/// `capacity-hour` (spec 7.2). `minute` is the epoch-minute the sample covers; `p95` is in milliseconds.
@Codec
public record CapacitySample(long minute,
                             String community,
                             Artifact artifact,
                             long arrivals,
                             long accepted,
                             long completed,
                             long failed,
                             long rejected,
                             double p95,
                             int instances,
                             CapacityQuality quality) {
    public static CapacitySample capacitySample(long minute,
                                                String community,
                                                Artifact artifact,
                                                long arrivals,
                                                long accepted,
                                                long completed,
                                                long failed,
                                                long rejected,
                                                double p95,
                                                int instances,
                                                CapacityQuality quality) {
        return new CapacitySample(minute,
                                  community,
                                  artifact,
                                  arrivals,
                                  accepted,
                                  completed,
                                  failed,
                                  rejected,
                                  p95,
                                  instances,
                                  quality);
    }
}
