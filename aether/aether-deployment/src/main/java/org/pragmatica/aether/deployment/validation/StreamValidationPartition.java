// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.stream.StreamResource;


/// #1336 — the deploy path's outcome from [StreamResourceValidator#partition]: the sections that
/// passed every rule (`accepted`, to be bound), the sections that failed one (`rejected`, each naming
/// its field and rule, to be reported in the deploy response) and the non-blocking warnings. A rule
/// whose violation leaves nothing to bind is not represented here — it fails the deploy instead.
public record StreamValidationPartition(Map<String, StreamResource> accepted,
                                        List<StreamValidationFailure> rejected,
                                        List<StreamValidationWarning> warnings) {
    public StreamValidationPartition {
        accepted = Map.copyOf(accepted);
        rejected = List.copyOf(rejected);
        warnings = List.copyOf(warnings);
    }

    public static StreamValidationPartition streamValidationPartition(Map<String, StreamResource> accepted,
                                                                      List<StreamValidationFailure> rejected,
                                                                      List<StreamValidationWarning> warnings) {
        return new StreamValidationPartition(accepted, rejected, warnings);
    }
}
