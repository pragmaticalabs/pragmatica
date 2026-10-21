// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import static org.assertj.core.api.Assertions.assertThat;

/// A worker holds a scoped view of the cluster, not the cluster-wide metrics feed, so the evaluator must
/// stop at the cluster-view check and read nothing else from the node (no metrics collector, no alert
/// manager — the manager is deliberately `null` here, so any read past the check would throw).
class AlertThresholdEvaluatorWorkerScopeTest {
    @Test
    void workerEvaluatesNothingBeforeReadingAnyClusterState() {
        var reads = new ArrayList<String>();
        var node = (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
            new Class<?>[]{ManageableNode.class}, (_, method, _) -> {
                reads.add(method.getName());
                return false;
            });
        var evaluator = AlertThresholdEvaluator.alertThresholdEvaluator(() -> node, null);

        evaluator.evaluateThresholds();

        assertThat(reads).containsOnly("hasCompleteClusterView");
    }
}
