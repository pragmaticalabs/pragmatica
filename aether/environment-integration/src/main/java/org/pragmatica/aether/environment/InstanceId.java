// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record InstanceId(String value) {
    public static Result<InstanceId> instanceId(String value) {
        return success(new InstanceId(value));
    }
}
