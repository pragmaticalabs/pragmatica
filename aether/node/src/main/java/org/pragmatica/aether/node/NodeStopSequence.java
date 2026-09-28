// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.function.Supplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// The ordered, promise-chained tail of `AetherNode.stop()`, extracted so the ordering and failure handling
/// can be tested without assembling a node (#1612).
///
/// Each step is a `Supplier`, so no step starts before the previous one has settled.
sealed interface NodeStopSequence {
    Logger LOG = LoggerFactory.getLogger(NodeStopSequence.class);

    /// The steps, in order. `storage` is infallible by design (see `AetherNode.shutdownStorage`).
    record Steps(Supplier<Promise<Unit>> deactivateDeployment,
                 Supplier<Promise<Unit>> managementServer,
                 Supplier<Promise<Unit>> appHttpServer,
                 Supplier<Promise<Unit>> sliceInvoker,
                 Supplier<Unit> storage,
                 Supplier<Promise<Unit>> clusterNode) {
        static Steps steps(Supplier<Promise<Unit>> deactivateDeployment,
                           Supplier<Promise<Unit>> managementServer,
                           Supplier<Promise<Unit>> appHttpServer,
                           Supplier<Promise<Unit>> sliceInvoker,
                           Supplier<Unit> storage,
                           Supplier<Promise<Unit>> clusterNode) {
            return new Steps(deactivateDeployment, managementServer, appHttpServer, sliceInvoker, storage, clusterNode);
        }
    }

    /// Runs the steps in order. An HTTP listener that fails to stop does not end the sequence: see
    /// [#continuingPast].
    static Promise<Unit> run(Steps steps) {
        return steps.deactivateDeployment()
                    .get()
                    .flatMap(_ -> continuingPast("Management server",
                                                 steps.managementServer().get()))
                    .flatMap(_ -> continuingPast("App HTTP server",
                                                 steps.appHttpServer().get()))
                    .flatMap(_ -> steps.sliceInvoker()
                                       .get())
                    .map(_ -> steps.storage()
                                   .get())
                    .flatMap(_ -> steps.clusterNode()
                                       .get());
    }

    /// #1612: an HTTP listener's `stop()` reports a timed-out channel close or event-loop termination as a
    /// FAILURE. Before #1612 it never failed; a wedged loop hung it instead. In this chain a failure would skip
    /// every later step through the `flatMap` short-circuit, so storage would not drain and the cluster node
    /// would keep running. So the failure is logged and shutdown continues. This is forward recovery (FER): the
    /// listener was asked to stop either way, and what is lost is only the confirmation that its loops
    /// terminated.
    static Promise<Unit> continuingPast(String listener, Promise<Unit> stop) {
        return stop.onFailure(cause -> LOG.warn("{} did not stop cleanly; continuing node shutdown: {}",
                                                listener,
                                                cause.message()))
                   .recover(_ -> unit());
    }

    record unused() implements NodeStopSequence {}
}
