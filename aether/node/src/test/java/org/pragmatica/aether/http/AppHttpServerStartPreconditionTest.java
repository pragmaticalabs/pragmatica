// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.fsm.AppHttpContext;
import org.pragmatica.aether.http.fsm.AppHttpState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.statemachine.Fsm;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Theme M / M2 — verifies the [`AppHttpServer#verifyRouteTableSupplierWired`] precondition
/// rejects unwired contexts (null route registry, sentinel route-table supplier) so production
/// wiring failures surface immediately at `start()` instead of silently serving an empty
/// [`RouteTable`].
class AppHttpServerStartPreconditionTest {
    private static final NodeId SELF = NodeId.nodeId("test-self").unwrap();

    @Test
    void verify_throws_whenRouteRegistryIsNull() {
        var context = wiredContext();
        assertThatThrownBy(() -> AppHttpServerAdapter.verifyRouteTableSupplierWired(null, context))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("routeRegistry");
    }

    @Test
    void verify_throws_whenContextIsNull() {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        assertThatThrownBy(() -> AppHttpServerAdapter.verifyRouteTableSupplierWired(registry, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("AppHttpContext");
    }

    @Test
    void verify_throws_whenSupplierIsDefaultSentinel() {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        var context = sentinelContext();
        assertThatThrownBy(() -> AppHttpServerAdapter.verifyRouteTableSupplierWired(registry, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("routeTableSupplier")
                .hasMessageContaining("RouteRegistry wiring missed");
    }

    @Test
    void verify_passes_whenSupplierIsCustomWiring() {
        var registry = HttpRouteRegistry.httpRouteRegistry();
        var context = wiredContext();
        AppHttpServerAdapter.verifyRouteTableSupplierWired(registry, context);
        assertThat(context.routeTableSupplier()).isNotSameAs(AppHttpContext.EMPTY_ROUTE_TABLE_SENTINEL);
    }

    /// Builds an [`AppHttpContext`] using the default (no-arg) constructor — that path wires the
    /// [`AppHttpContext#EMPTY_ROUTE_TABLE_SENTINEL`] supplier, which the precondition must reject.
    private static AppHttpContext sentinelContext() {
        var ctxHolder = new AtomicReference<AppHttpContext>();
        Function<Fsm<AppHttpState, ClusterFsmEvent>, AppHttpState> factory = fsm -> {
            var ctx = new AppHttpContext(fsm);
            ctxHolder.set(ctx);
            return ctx.stopped();
        };
        Fsm.fsm("app-http-precond-sentinel", SELF.id(), factory);
        return ctxHolder.get();
    }

    /// Builds an [`AppHttpContext`] with a custom (non-sentinel) route-table supplier — the
    /// production-shape wiring that the precondition must accept.
    private static AppHttpContext wiredContext() {
        var ctxHolder = new AtomicReference<AppHttpContext>();
        Function<Fsm<AppHttpState, ClusterFsmEvent>, AppHttpState> factory = fsm -> {
            var ctx = new AppHttpContext(fsm, RouteTable::empty, () -> false);
            ctxHolder.set(ctx);
            return ctx.stopped();
        };
        Fsm.fsm("app-http-precond-wired", SELF.id(), factory);
        return ctxHolder.get();
    }
}
