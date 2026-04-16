// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;


/// Runtime profile definition. S5.2
public record RuntimeProfile(String name, RuntimeType type, Option<String> image, Option<String> jvmArgs) {
    public static RuntimeProfile runtimeProfile(String name,
                                                RuntimeType type,
                                                Option<String> image,
                                                Option<String> jvmArgs) {
        return new RuntimeProfile(name, type, image, jvmArgs);
    }
}
