// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.migration;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record MigrationRequest(String targetProvider,
                               String targetZone,
                               MigrationStrategy strategy,
                               Option<String> dnsHostname) {
    public static Result<MigrationRequest> migrationRequest(String targetProvider,
                                                            String targetZone,
                                                            MigrationStrategy strategy,
                                                            String dnsHostname) {
        return success(new MigrationRequest(targetProvider,
                                            targetZone,
                                            strategy,
                                            option(dnsHostname).filter(s -> !s.isEmpty())));
    }
}
