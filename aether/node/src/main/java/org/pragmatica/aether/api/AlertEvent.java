// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;


public sealed interface AlertEvent {
    enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    String alertId();
    long timestamp();
    Severity severity();

    record ThresholdAlert(String alertId,
                          long timestamp,
                          Severity severity,
                          String metric,
                          NodeId nodeId,
                          double value,
                          double threshold) implements AlertEvent {}

    record SliceFailureAlert(String alertId,
                             long timestamp,
                             Severity severity,
                             Artifact artifact,
                             MethodName method,
                             String requestId,
                             List<NodeId> attemptedNodes,
                             String lastError) implements AlertEvent {
        public static SliceFailureAlert sliceFailureAlert(String alertId,
                                                          Artifact artifact,
                                                          MethodName method,
                                                          String requestId,
                                                          List<NodeId> attemptedNodes,
                                                          String lastError) {
            return new SliceFailureAlert(alertId,
                                         System.currentTimeMillis(),
                                         Severity.CRITICAL,
                                         artifact,
                                         method,
                                         requestId,
                                         attemptedNodes,
                                         lastError);
        }
    }

    record AlertResolved(String alertId, long timestamp, Severity severity, String resolvedBy) implements AlertEvent {
        public static AlertResolved resolved(String alertId, String resolvedBy) {
            return new AlertResolved(alertId, System.currentTimeMillis(), Severity.INFO, resolvedBy);
        }
    }
}
