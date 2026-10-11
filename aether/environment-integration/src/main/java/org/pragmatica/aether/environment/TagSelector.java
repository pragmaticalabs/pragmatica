// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Map;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record TagSelector(Map<String, String> requiredTags) {
    public static Result<TagSelector> tagSelector(Map<String, String> requiredTags) {
        return success(new TagSelector(Map.copyOf(requiredTags)));
    }

    public boolean matches(InstanceInfo instance) {
        return instance.tags()
                       .entrySet()
                       .containsAll(requiredTags.entrySet());
    }
}
