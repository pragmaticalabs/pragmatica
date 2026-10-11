// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.http.routing.Route;


// Registry-agnostic per-route handler rewrap hook for the north-south (HTTP entry) observability seam
// (#277 increment 2). The publisher supplies a decorator that mints a cell per route, registers it with
// the write-side registry, and closes the route's handler over `cell.around(...)`; the SliceRouter
// applies it ONCE at construction so the per-call path stays the existing route lookup plus one wrapped
// handler — no per-call cell lookup. The adapter itself stays cell- and registry-agnostic: it only knows
// this `Route -> Route` transform. IDENTITY leaves routes untouched (the default, zero-cost wiring).
@FunctionalInterface
public interface RouteDecorator {
    Route<?> decorate(Route<?> route);
    RouteDecorator IDENTITY = route -> route;
}
