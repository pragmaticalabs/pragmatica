// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Map;


public record NodeGroupConfig(SourceName sourceName,
                              String role,
                              int count,
                              String instanceType,
                              String zone,
                              Map<String, String> tags) {
    public static NodeGroupConfig nodeGroupConfig(SourceName sourceName,
                                                  String role,
                                                  int count,
                                                  String instanceType,
                                                  String zone,
                                                  Map<String, String> tags) {
        return new NodeGroupConfig(sourceName, role, count, instanceType, zone, Map.copyOf(tags));
    }
}
