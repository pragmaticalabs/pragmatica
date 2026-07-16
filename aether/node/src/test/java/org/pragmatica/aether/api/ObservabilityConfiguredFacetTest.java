// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationContext;
import org.pragmatica.aether.invoke.InvocationNode;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.slice.ObservabilityStrategyCell;
import org.pragmatica.aether.slice.ObservabilityStrategyCell.InvocationStrategy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;


/// #277 increment 5b: the CONFIGURED per-injection-point path composes the SAME facet bodies the baseline
/// uses, selected by the config's toggles (ObservabilityBaseline.compose). Proves through the real
/// registry + cell wiring that: a full config (logging+metrics+tracing) behaves like the baseline (records
/// into the SAME InvocationTraceStore, logs on the `org.pragmatica.aether.trace` logger, counts); a
/// tracing-only config records but does not log or count; a logging-only config logs but does not record
/// or count; a metrics-only config counts but does not record or log; an explicit all-off config is
/// identity (nothing). Also the setDepth regression: materializing a depth pin on an UNCONFIGURED method
/// must not darken it — tracing still records, logging still fires, counting continues, only the depth
/// threshold changes. Log output is captured with a programmatic log4j2 appender on the trace logger.
class ObservabilityConfiguredFacetTest {
    private static final String ARTIFACT_BASE = "com.example:my-slice";
    private static final String METHOD = "echo";
    private static final String CALLEE = ARTIFACT_BASE + "/" + METHOD;
    private static final String NODE_ID = "node-1";
    private static final int DEFAULT_DEPTH = 1;
    private static final String TRACE_LOGGER = "org.pragmatica.aether.trace";

    private InvocationTraceStore traceStore;
    private ObservabilityConfigRegistry registry;

    @BeforeEach
    void setUp() {
        traceStore = InvocationTraceStore.invocationTraceStore();
        var sampler = AdaptiveSampler.adaptiveSampler(1_000_000);

        registry = ObservabilityConfigRegistry.observabilityConfigRegistry(clusterNodeStub(),
                                                                           kvStoreStub(),
                                                                           ObservabilityBaseline.fleet(sampler,
                                                                                                       traceStore,
                                                                                                       NODE_ID,
                                                                                                       DEFAULT_DEPTH));
    }

    @Test
    void configuredFullConfig_recordsLogsAndCounts_likeBaseline() {
        var cell = registeredCell();

        setConfig(cell, true, true, false, true, 1);
        var lines = captureTraceLines(() -> invoke(cell, 0, false, "req-full", () -> Promise.success("ok")));

        assertThat(onlyNode().outcome()).isEqualTo(InvocationNode.Outcome.SUCCESS);
        assertThat(loggedCallee(lines)).isTrue();
        assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void configuredTracingOnly_records_butDoesNotLogOrCount() {
        var cell = registeredCell();

        setConfig(cell, false, false, false, true, 1);
        var lines = captureTraceLines(() -> invoke(cell, 0, false, "req-trace", () -> Promise.success("ok")));

        assertThat(onlyNode().callee()).isEqualTo(CALLEE);
        assertThat(loggedCallee(lines)).isFalse();
        // Metrics off -> no counting facet planted; the cell's storage stays empty, read as Some(0).
        assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(0L));
    }

    @Test
    void configuredLoggingOnly_logs_butDoesNotRecordOrCount() {
        var cell = registeredCell();

        setConfig(cell, true, false, false, false, 1);
        var lines = captureTraceLines(() -> invoke(cell, 0, false, "req-log", () -> Promise.success("ok")));

        assertThat(allNodes()).isEmpty();
        assertThat(loggedCallee(lines)).isTrue();
        assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(0L));
    }

    @Test
    void configuredMetricsOnly_counts_butDoesNotRecordOrLog() {
        var cell = registeredCell();

        setConfig(cell, false, true, false, false, 1);
        var lines = captureTraceLines(() -> invoke(cell, 0, false, "req-metrics", () -> Promise.success("ok")));

        assertThat(allNodes()).isEmpty();
        assertThat(loggedCallee(lines)).isFalse();
        assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(1L));
    }

    @Test
    void explicitAllOff_isIdentity_noRecordNoLogNoCount() {
        var cell = registeredCell();

        setConfig(cell, false, false, false, false, 0);
        assertThat(cell.strategy()).isSameAs(InvocationStrategy.IDENTITY);
        var lines = captureTraceLines(() -> invoke(cell, 0, false, "req-off", () -> Promise.success("ok")));

        assertThat(allNodes()).isEmpty();
        assertThat(loggedCallee(lines)).isFalse();
        assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(0L));
    }

    @Test
    void setDepth_onUnconfiguredMethod_doesNotDarken_tracingLoggingCountingContinue() {
        var cell = registeredCell();

        // Materialize a depth pin on an unconfigured method: pins the baseline-equivalent toggles
        // (logging+metrics+tracing on, spans off) with the new depth -> must stay behaviourally identical.
        registry.setDepth(ARTIFACT_BASE, METHOD, 3).await().onFailure(cause -> Assertions.fail(cause.message()));
        var stored = registry.getConfig(ARTIFACT_BASE, METHOD);

        assertThat(stored.logging()).isTrue();
        assertThat(stored.metrics()).isTrue();
        assertThat(stored.spans()).isFalse();
        assertThat(stored.tracing()).isTrue();
        assertThat(stored.depth()).isEqualTo(3);

        var lines = captureTraceLines(() -> invoke(cell, 0, false, "req-depth", () -> Promise.success("ok")));

        // Tracing still records, logging still fires, counting continues — only the depth changed.
        assertThat(onlyNode().outcome()).isEqualTo(InvocationNode.Outcome.SUCCESS);
        assertThat(loggedCallee(lines)).isTrue();
        assertThat(registry.invocationCount(ARTIFACT_BASE, METHOD)).isEqualTo(Option.some(1L));
    }

    private void setConfig(ObservabilityStrategyCell cell,
                           boolean logging,
                           boolean metrics,
                           boolean spans,
                           boolean tracing,
                           int depth) {
        registry.setConfig(cell.key().substring(0, cell.key().indexOf('/')),
                           cell.key().substring(cell.key().indexOf('/') + 1),
                           logging,
                           metrics,
                           spans,
                           tracing,
                           depth).await().onFailure(cause -> Assertions.fail(cause.message()));
    }

    private ObservabilityStrategyCell registeredCell() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT_BASE, METHOD);

        registry.register(cell);

        return cell;
    }

    private static void invoke(ObservabilityStrategyCell cell,
                               int depth,
                               boolean sampled,
                               String requestId,
                               Fn0<Promise<String>> body) {
        InvocationContext.runWithContext(requestId,
                                         null,
                                         null,
                                         depth,
                                         sampled,
                                         () -> cell.around(body)
                                                   .await());
    }

    private static boolean loggedCallee(List<String> lines) {
        return lines.stream()
                    .anyMatch(line -> line.contains(CALLEE));
    }

    private InvocationNode onlyNode() {
        var nodes = allNodes();

        assertThat(nodes).hasSize(1);

        return nodes.getFirst();
    }

    private List<InvocationNode> allNodes() {
        return traceStore.all()
                         .await()
                         .unwrap();
    }

    // Attaches a capturing log4j2 appender to the trace logger, runs the action, then detaches — returning
    // every trace line emitted during the action so a test can assert the logging facet fired (or did not).
    private static List<String> captureTraceLines(Runnable action) {
        var attachment = attachTraceAppender();

        try {
            action.run();

            return List.copyOf(attachment.appender()
                                         .messages());
        } finally {
            detachTraceAppender(attachment);
        }
    }

    private static Attachment attachTraceAppender() {
        var ctx = (LoggerContext) LogManager.getContext(false);
        var config = ctx.getConfiguration();
        var appender = new CapturingAppender();

        appender.start();
        config.addAppender(appender);
        var loggerConfig = config.getLoggerConfig(TRACE_LOGGER);
        var previousLevel = loggerConfig.getLevel();

        loggerConfig.addAppender(appender, Level.ALL, null);
        loggerConfig.setLevel(Level.TRACE);
        ctx.updateLoggers();

        return new Attachment(ctx, appender, loggerConfig, previousLevel);
    }

    private static void detachTraceAppender(Attachment attachment) {
        attachment.loggerConfig()
                  .removeAppender(attachment.appender()
                                            .getName());
        attachment.loggerConfig()
                  .setLevel(attachment.previousLevel());
        attachment.ctx()
                  .updateLoggers();
        attachment.appender()
                  .stop();
    }

    private record Attachment(LoggerContext ctx,
                              CapturingAppender appender,
                              LoggerConfig loggerConfig,
                              Level previousLevel) {}

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("obs-test-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage()
                              .getFormattedMessage());
        }

        List<String> messages() {
            return messages;
        }
    }

    @SuppressWarnings("unchecked")
    private static KVStore<AetherKey, AetherValue> kvStoreStub() {
        return Mockito.mock(KVStore.class);
    }

    @SuppressWarnings("unchecked")
    private static RabiaNode<KVCommand<AetherKey>> clusterNodeStub() {
        RabiaNode<KVCommand<AetherKey>> node = Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList()))
               .thenAnswer(_ -> Promise.success(List.of(Unit.unit())));

        return node;
    }
}
