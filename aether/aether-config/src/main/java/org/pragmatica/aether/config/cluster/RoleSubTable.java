// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;

import java.util.List;


public record RoleSubTable(NodeRole role,
                           Option<Integer> count,
                           Option<List<String>> hosts,
                           Option<String> instanceType,
                           String runtimeRef) {
    public static RoleSubTable roleSubTable(NodeRole role,
                                            Option<Integer> count,
                                            Option<List<String>> hosts,
                                            Option<String> instanceType,
                                            String runtimeRef) {
        return new RoleSubTable(role, count, hosts, instanceType, runtimeRef);
    }
}
