// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.pragmatica.aether.slice.stream.StreamResource;

import java.util.List;
import java.util.Map;


/// Successful validation outcome from [StreamResourceValidator].
///
/// Carries the parsed-and-validated stream resource map alongside any non-blocking warnings (spec
/// §11.1.3 multi-version pin recommendation). Callers proceed with deploy on this value.
public record ValidatedStreamResources(Map<String, StreamResource> resources, List<StreamValidationWarning> warnings) {
    public ValidatedStreamResources {
        resources = Map.copyOf(resources);
        warnings = List.copyOf(warnings);
    }

    public static ValidatedStreamResources validatedStreamResources(Map<String, StreamResource> resources,
                                                                    List<StreamValidationWarning> warnings) {
        return new ValidatedStreamResources(resources, warnings);
    }
}
