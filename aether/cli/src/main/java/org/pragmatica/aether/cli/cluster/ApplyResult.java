// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.DiffPlan;


public record ApplyResult(DiffPlan executedPlan, int nodesAdded, int nodesRemoved, int nodesModified) {
    public static ApplyResult applyResult(DiffPlan executedPlan, int nodesAdded, int nodesRemoved, int nodesModified) {
        return new ApplyResult(executedPlan, nodesAdded, nodesRemoved, nodesModified);
    }
}
