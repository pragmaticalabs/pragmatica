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


public interface AppHttpEvents extends ClusterFsmEvent {
    record StartRequested() implements AppHttpEvents {}

    record H1Ready(HttpServer server) implements AppHttpEvents {}

    record H3Ready(HttpServer server) implements AppHttpEvents {}

    record RouteTablePublished(RouteTable routes) implements AppHttpEvents {}

    record CertRotationRequested(CertificateBundle bundle) implements AppHttpEvents {}

    record CertRotationApplied(Option<HttpServer> newServer, Option<HttpServer> newH3, RouteTable routes) implements AppHttpEvents {}

    record StopRequested() implements AppHttpEvents {}
}
