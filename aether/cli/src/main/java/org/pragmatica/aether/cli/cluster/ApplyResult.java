// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.DiffPlan;


public record ApplyResult(DiffPlan executedPlan, int nodesAdded, int nodesRemoved, int nodesModified) {
    public static ApplyResult applyResult(DiffPlan executedPlan, int nodesAdded, int nodesRemoved, int nodesModified) {
        return new ApplyResult(executedPlan, nodesAdded, nodesRemoved, nodesModified);
    }
}
