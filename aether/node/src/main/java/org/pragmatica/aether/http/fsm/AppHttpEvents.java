// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.fsm;

import org.pragmatica.aether.http.RouteTable;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.security.CertificateBundle;

/// Domain event vocabulary for the app HTTP server FSM. Records implement [`ClusterFsmEvent`] so
/// the FSM uses a single event channel covering both domain events (start/stop/cert rotation) and
/// cluster-lifecycle events ([`ClusterFsmEvent.QuorumEstablished`], [`ClusterFsmEvent.Shutdown`]).
///
/// Event → call-site mapping:
/// - [`StartRequested`] — [`org.pragmatica.aether.http.AppHttpServer#start`].
/// - [`H1Ready`] — internal completion of HTTP/1.1 bind.
/// - [`H3Ready`] — internal completion of HTTP/3 QUIC bind.
/// - [`RouteTablePublished`] — `onRoutePut` / `onRouteRemove` / `onNodeRoutesPut` /
///   `onNodeRoutesRemove` / `rebuildRouter` after computing the fresh [`RouteTable`].
/// - [`CertRotationRequested`] — [`org.pragmatica.aether.http.AppHttpServer#rotateCertificate`].
/// - [`CertRotationApplied`] — internal completion of the rotate I/O pipeline.
/// - [`StopRequested`] — [`org.pragmatica.aether.http.AppHttpServer#stop`].
///
/// Plus [`ClusterFsmEvent.QuorumEstablished`] and [`ClusterFsmEvent.Shutdown`] are dispatched
/// directly to the FSM — no wrapper record.
public interface AppHttpEvents extends ClusterFsmEvent {

    /// Entry signal from the public `start()` call. Moves FSM from `Stopped` → `Starting`.
    record StartRequested() implements AppHttpEvents {}

    /// HTTP/1.1 bind completion. Carries the bound [`HttpServer`] instance — it becomes a field on
    /// the `H1Only` / `Dual` state record so handlers read the server by reference rather than via
    /// an atomic holder.
    record H1Ready(HttpServer server) implements AppHttpEvents {}

    /// HTTP/3 QUIC bind completion. Carries the bound [`HttpServer`] instance (QUIC wrapper).
    record H3Ready(HttpServer server) implements AppHttpEvents {}

    /// A new [`RouteTable`] was computed by the adapter (first publish or later update). Causes
    /// transition into `RouteReady` with the carried table, or a `RouteReady → RouteReady`
    /// fresh-record swap if the FSM is already there.
    record RouteTablePublished(RouteTable routes) implements AppHttpEvents {}

    /// The public `rotateCertificate(bundle)` call was invoked. Carries the new bundle so the
    /// adapter can orchestrate the stop-old/start-new I/O while the FSM sits in `CertRotating`.
    record CertRotationRequested(CertificateBundle bundle) implements AppHttpEvents {}

    /// Completion of the rotate I/O pipeline. Carries the optional fresh H1 / H3 server refs and
    /// the current [`RouteTable`] so the FSM can return to `RouteReady` with updated collaborators.
    record CertRotationApplied(Option<HttpServer> newServer,
                               Option<HttpServer> newH3,
                               RouteTable routes) implements AppHttpEvents {}

    /// Public `stop()` entry point. Drives FSM to `Stopped` from any state.
    record StopRequested() implements AppHttpEvents {}
}
