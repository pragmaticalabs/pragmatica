// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Evaluates every alert threshold against the cluster's metrics once per interval.
///
/// Threshold evaluation is an operator-facing capability: it runs on every node whether or not any
/// client is connected to anything, because a headless cluster is the normal production state. It
/// was written inside the old dashboard's metrics publisher and sat after that publisher's
/// "any client connected" guard until #957 moved it ahead of the guard; the dashboard has since been
/// removed and this class is what remained of the publisher once the UI feeds were taken away.
@SuppressWarnings("JBCT-RET-01")
public class AlertThresholdEvaluator {
    private static final Logger log = LoggerFactory.getLogger(AlertThresholdEvaluator.class);
    private static final long EVALUATION_INTERVAL_MS = 1000;

    private final Supplier<ManageableNode> nodeSupplier;
    private final AlertManager alertManager;

    private final AtomicReference<Option<ScheduledFuture<?>>> scheduledTask = new AtomicReference<>(none());

    private final AtomicBoolean running = new AtomicBoolean(false);

    private AlertThresholdEvaluator(Supplier<ManageableNode> nodeSupplier, AlertManager alertManager) {
        this.nodeSupplier = nodeSupplier;
        this.alertManager = alertManager;
    }

    public static AlertThresholdEvaluator alertThresholdEvaluator(Supplier<ManageableNode> nodeSupplier,
                                                                  AlertManager alertManager) {
        return new AlertThresholdEvaluator(nodeSupplier, alertManager);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        scheduledTask.set(some(SharedScheduler.scheduleAtFixedRate(this::evaluateThresholds,
                                                                   TimeSpan.timeSpan(EVALUATION_INTERVAL_MS).millis())));
        log.info("Alert threshold evaluator started");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        scheduledTask.getAndSet(none()).onPresent(task -> task.cancel(false));
        log.info("Alert threshold evaluator stopped");
    }

    /// Package-visible so the "evaluation is not conditioned on any connected client" contract is
    /// directly pinnable: the tests drive it with nothing attached to the node.
    @SuppressWarnings("JBCT-EX-01")
    void evaluateThresholds() {
        if (!hasCompleteClusterView()) {
            return;
        }

        try {
            evaluateAllMetrics();
        } catch (Exception e) {
            log.error("Error evaluating alert thresholds", e);
        }
    }

    private boolean hasCompleteClusterView() {
        return nodeSupplier.get()
                           .hasCompleteClusterView();
    }

    private void evaluateAllMetrics() {
        var allMetrics = nodeSupplier.get().metricsCollector().allMetrics();

        for (var entry : allMetrics.entrySet()) {
            var nodeId = entry.getKey();

            for (var metric : entry.getValue().entrySet()) {
                alertManager.checkThreshold(metric.getKey(), nodeId, metric.getValue());
            }
        }
    }
}
