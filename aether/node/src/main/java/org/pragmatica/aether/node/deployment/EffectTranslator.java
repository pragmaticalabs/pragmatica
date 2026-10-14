// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.pragmatica.aether.deployment.AuditLog;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect;
import org.pragmatica.aether.deployment.cluster.fsm2.RoutedEvent;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.schema.SchemaEvent;
import org.pragmatica.aether.deployment.wiring.LocalOpExecutor;
import org.pragmatica.aether.deployment.wiring.StepObserver;
import org.pragmatica.aether.metrics.deployment.DeploymentEvent;
import org.pragmatica.aether.metrics.deployment.ReconciliationAdjustment;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Turns the effects the two deployment FSMs emit for the outside world into what the rest of the node already consumes (phase-3 design 4.2,
/// 5.2): deployment events on the node's router, operator warnings, audit lines, metrics and log lines. It decides nothing: every effect has
/// exactly one translation, and an effect nobody can translate is reported, never dropped silently.
///
/// | Effect | Translation |
/// |---|---|
/// | node `Route` | `DeploymentEvent.StateTransition` / `DeploymentFailed` / `DeploymentCompleted`, with this node's id; `SliceObservedState` is mapped to `SliceState` |
/// | cluster `Route` | `DeploymentStarted`; `DeploymentFailed` (state `FAILED`); the neutral `ReconciliationAdjustment`; `SchemaEvent.ActivationBlocked` (every field the indexed `SchemaVersionValue` knows) |
/// | `Warn`, `WarnResolved` | `OperatorWarnings.raise` for a code in `OperatorWarningCode`; an uncatalogued code is logged and counted (see below) |
/// | `Audit` | the typed `AuditLog` method with its line format (`reconciliationScaleUp/Down`, `schemaActivationBlocked`); never a free-text line |
/// | `Metric` / `Gauge` | the Micrometer counter / gauge of that name and tags |
/// | `Log` | the logger of that FSM, at the level the FSM chose |
/// | `DispatchMigration` | the schema orchestrator's `migrateIfNeeded` |
///
/// **An uncatalogued warning code.** The FSMs raise codes (`stray-instance`, `join-unmatched`, ...) that are not constants of
/// `OperatorWarningCode`, and a warning needs a catalogued code (subsystem and level) to reach the cluster event log. Adding them is a
/// decision about levels the spec does not make. Until it is made, such a warning is written to the log at WARN with its code, subject and
/// message, and counted in `aether_deployment_fsm_warnings_unmapped_total{code}`: visible to an operator reading logs and to the expected-WARN
/// check, but not published as an operator event. `[guess: log-and-count rather than inventing levels; settled by the CTO adding the constants]`
public final class EffectTranslator {
    private static final Logger nodeLog = LoggerFactory.getLogger("org.pragmatica.aether.deployment.fsm.node");
    private static final Logger clusterLog = LoggerFactory.getLogger("org.pragmatica.aether.deployment.fsm.cluster");
    private static final Logger log = LoggerFactory.getLogger(EffectTranslator.class);
    private static final Pattern SCALE_DETAIL = Pattern.compile("^(\\S+) (\\d+) -> (\\d+)$");
    private static final Pattern BLOCKED_DETAIL = Pattern.compile("^(\\S+) owner=(.+)$");

    /// What the translator writes to.
    public record Targets(NodeId self,
                          Consumer<Message> events,
                          Supplier<OperatorWarningSink> warnings,
                          MeterRegistry registry,
                          Consumer<String> migrations) {}

    private final Targets targets;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final Map<String, List<String>> blockedSlices = new ConcurrentHashMap<>();

    private EffectTranslator(Targets targets) {
        this.targets = targets;
    }

    public static EffectTranslator effectTranslator(Targets targets) {
        return new EffectTranslator(targets);
    }

    /// The sink of the node interpreter.
    public Consumer<NodeFsmEffect> nodeSink() {
        return this::translate;
    }

    /// The sink of the cluster interpreter.
    public Consumer<ClusterEffect> clusterSink() {
        return this::translate;
    }

    /// `aether_deployment_fsm_step_seconds{fsm, input}`.
    public StepObserver stepObserver() {
        return (fsm, input, nanos) -> targets.registry()
                                             .timer("aether_deployment_fsm_step_seconds", "fsm", fsm, "input", input)
                                             .record(nanos, TimeUnit.NANOSECONDS);
    }

    /// `aether_deployment_fsm_localops_total{step, result}`.
    public LocalOpExecutor.Counter localOpCounter() {
        return (step, result) -> targets.registry()
                                        .counter("aether_deployment_fsm_localops_total", "step", step, "result", result)
                                        .increment();
    }

    /// `aether_deployment_fsm_mailbox_depth{fsm}`, read from the interpreter's mailbox when scraped.
    public Unit bindMailboxDepth(String fsm, IntSupplier depth) {
        Gauge.builder("aether_deployment_fsm_mailbox_depth", depth::getAsInt)
             .tag("fsm", fsm)
             .register(targets.registry());

        return Unit.unit();
    }

    // -- node effects -----------------------------------------------------------------------------------------------
    private Unit translate(NodeFsmEffect effect) {
        switch (effect) {
            case NodeFsmEffect.Route route -> targets.events().accept(nodeEvent(route.event()));
            case NodeFsmEffect.Warn warn -> raise(warn.code(), warn.subject(), warn.message());
            case NodeFsmEffect.WarnResolved resolved -> raise(resolved.code(), resolved.subject(), resolved.message());
            case NodeFsmEffect.Log line -> logged(line.level(), line.message());
            case NodeFsmEffect.Metric metric -> counted(metric.name(), metric.labels(), metric.delta());
            case NodeFsmEffect.SelfReady _, NodeFsmEffect.Submit _, NodeFsmEffect.Schedule _, NodeFsmEffect.Cancel _, NodeFsmEffect.LocalOp _ -> log.error("The interpreter handed the translator an effect it executes itself: {}",
                                                                                                                                                           effect);
        }

        return Unit.unit();
    }

    private DeploymentEvent nodeEvent(NodeFsmEffect.RoutedEvent event) {
        var self = targets.self();

        return switch (event) {
            case NodeFsmEffect.RoutedEvent.StateTransitioned moved -> DeploymentEvent.StateTransition.stateTransition(moved.artifact(),
                                                                                                                      self,
                                                                                                                      state(moved.from()),
                                                                                                                      state(moved.to()),
                                                                                                                      moved.timestamp());
            case NodeFsmEffect.RoutedEvent.DeploymentFailed failed -> DeploymentEvent.DeploymentFailed.deploymentFailed(failed.artifact(),
                                                                                                                        self,
                                                                                                                        state(failed.failedAt()),
                                                                                                                        failed.reason(),
                                                                                                                        failed.timestamp());
            case NodeFsmEffect.RoutedEvent.DeploymentCompleted completed -> DeploymentEvent.DeploymentCompleted.deploymentCompleted(completed.artifact(),
                                                                                                                                    self,
                                                                                                                                    completed.timestamp());
        };
    }

    /// The observed vocabulary has no ROUTING and has UNLOADED; the event stream's has the reverse. `UNLOADED` is reported as `UNLOADING`, the last
    /// state the event stream knows for a slice that is leaving `[guess: nothing in the design maps it; tracer T and E-01 never unload]`.
    static SliceState state(SliceObservedState observed) {
        return switch (observed) {
            case LOADING -> SliceState.LOADING;
            case LOADED -> SliceState.LOADED;
            case ACTIVATING -> SliceState.ACTIVATING;
            case ACTIVE -> SliceState.ACTIVE;
            case DEACTIVATING -> SliceState.DEACTIVATING;
            case FAILED -> SliceState.FAILED;
            case UNLOADING, UNLOADED -> SliceState.UNLOADING;
            case UNKNOWN -> SliceState.UNKNOWN;
        };
    }

    // -- cluster effects --------------------------------------------------------------------------------------------
    private Unit translate(ClusterEffect effect) {
        switch (effect) {
            case ClusterEffect.Route route -> targets.events().accept(clusterEvent(route.event()));
            case ClusterEffect.Warn warn -> raise(warn.code(), warn.subject(), warn.message());
            case ClusterEffect.Audit audit -> audited(audit.action(), audit.detail());
            case ClusterEffect.Log line -> clusterLog.info("{}", line.message());
            case ClusterEffect.Metric metric -> counted(metric.name(), metric.tags(), metric.delta());
            case ClusterEffect.Gauge gauge -> gauged(gauge.name(), gauge.tags(), gauge.value());
            case ClusterEffect.DispatchMigration migration -> targets.migrations().accept(migration.datasource());
            case ClusterEffect.Submit _, ClusterEffect.RequestRebuild _, ClusterEffect.Enqueue _, ClusterEffect.Schedule _, ClusterEffect.Cancel _ -> log.error("The interpreter handed the translator an effect it executes itself: {}",
                                                                                                                                                                effect);
        }

        return Unit.unit();
    }

    private Message clusterEvent(RoutedEvent event) {
        return switch (event) {
            case RoutedEvent.DeploymentStarted started -> DeploymentEvent.DeploymentStarted.deploymentStarted(started.artifact(),
                                                                                                              started.node(),
                                                                                                              started.atMs());
            case RoutedEvent.DeploymentFailed failed -> DeploymentEvent.DeploymentFailed.deploymentFailed(failed.artifact(),
                                                                                                          failed.node(),
                                                                                                          SliceState.FAILED,
                                                                                                          failed.reason(),
                                                                                                          failed.atMs());
            case RoutedEvent.ReconciliationAdjustment adjustment -> ReconciliationAdjustment.reconciliationAdjustment(adjustment.artifact(),
                                                                                                                      adjustment.current(),
                                                                                                                      adjustment.desired());
            case RoutedEvent.ActivationBlocked blocked -> activationBlocked(blocked);
        };
    }

    private SchemaEvent.ActivationBlocked activationBlocked(RoutedEvent.ActivationBlocked blocked) {
        blockedSlices.put(blocked.datasource(), blocked.slices());

        return SchemaEvent.ActivationBlocked.activationBlocked(blocked.datasource(),
                                                               blocked.owningBlueprint(),
                                                               blocked.slices(),
                                                               blocked.artifactCoords(),
                                                               blocked.attemptCount());
    }

    /// The `Audit` effect carries `(action, detail)` as text; the line is written by the typed `AuditLog` method of that action, with the arguments
    /// the detail encodes. The blocked slices are not in the `schema-activation-blocked` detail: they are the ones the immediately preceding
    /// `ActivationBlocked` route of the same datasource carried `[guess: the FSM emits the route and the audit in one step, in that order]`.
    private void audited(String action, String detail) {
        switch (action) {
            case "reconciliation-scale-up" -> scaled(detail, true);
            case "reconciliation-scale-down" -> scaled(detail, false);
            case "schema-activation-blocked" -> blocked(detail);
            default -> log.error("Audit action '{}' has no typed AuditLog method; nothing was written (detail: {})",
                                 action,
                                 detail);
        }
    }

    private void scaled(String detail, boolean up) {
        var match = SCALE_DETAIL.matcher(detail);

        if (match.matches() && up) {
            AuditLog.reconciliationScaleUp(match.group(1),
                                           Integer.parseInt(match.group(2)),
                                           Integer.parseInt(match.group(3)));
        } else if (match.matches()) {
            AuditLog.reconciliationScaleDown(match.group(1),
                                             Integer.parseInt(match.group(2)),
                                             Integer.parseInt(match.group(3)));
        } else {
            log.error("Audit detail '{}' does not read as '<artifact> <current> -> <desired>'; nothing was written",
                      detail);
        }
    }

    private void blocked(String detail) {
        var match = BLOCKED_DETAIL.matcher(detail);

        if (match.matches()) {
            AuditLog.schemaActivationBlocked(match.group(1),
                                             match.group(2),
                                             Option.option(blockedSlices.remove(match.group(1))).or(List.of()));
        } else {
            log.error("Audit detail '{}' does not read as '<datasource> owner=<blueprint>'; nothing was written", detail);
        }
    }

    // -- shared -----------------------------------------------------------------------------------------------------
    private void raise(String code, String subject, String message) {
        Arrays.stream(OperatorWarningCode.values())
              .filter(known -> known.code()
                                    .equals(code))
              .findFirst()
              .ifPresentOrElse(known -> OperatorWarnings.raise(log,
                                                               targets.warnings().get(),
                                                               known,
                                                               subject,
                                                               "{}",
                                                               message),
                               () -> unmapped(code, subject, message));
    }

    private void unmapped(String code, String subject, String message) {
        log.warn("{} {}: {} (not an OperatorWarningCode: logged and counted, not published)", code, subject, message);
        targets.registry().counter("aether_deployment_fsm_warnings_unmapped_total", "code", code).increment();
    }

    private void logged(NodeFsmEffect.Level level, String message) {
        switch (level) {
            case DEBUG -> nodeLog.debug("{}", message);
            case INFO -> nodeLog.info("{}", message);
            case WARN -> nodeLog.warn("{}", message);
            case ERROR -> nodeLog.error("{}", message);
            case CRITICAL -> nodeLog.error("CRITICAL: {}", message);
        }
    }

    private void counted(String name, Map<String, String> labels, long delta) {
        targets.registry().counter(name, tagsOf(labels)).increment(delta);
    }

    private void gauged(String name, Map<String, String> labels, long value) {
        var key = name + labels;

        gauges.computeIfAbsent(key, _ -> registered(name, labels)).set(value);
    }

    private AtomicLong registered(String name, Map<String, String> labels) {
        var holder = new AtomicLong();

        Gauge.builder(name, holder, AtomicLong::get).tags(tagsOf(labels)).register(targets.registry());

        return holder;
    }

    private static Tags tagsOf(Map<String, String> labels) {
        return Tags.of(labels.entrySet()
                             .stream()
                             .map(entry -> io.micrometer.core.instrument.Tag.of(entry.getKey(),
                                                                                entry.getValue()))
                             .toList());
    }
}
