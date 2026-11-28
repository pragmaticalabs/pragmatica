// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// A host-owned handler tree under one base path (#3651), served beside the slice routes. With no `listener` it shares
/// the slice listener and reserves its base path there (a slice route under it is refused at startup); with a
/// `listener` it gets a listener of its own, bound and stopped with the host. A mount request bypasses slice admission,
/// as the `/__terra` probes do, so it still answers while the host drains; it is authenticated by the host's
/// authenticator under the policy `policy` picks for that request, and the handler receives the validated security
/// context together with the host's status snapshot.
public record TerraHttpMount(String basePath,
                             Option<HttpServerConfig> listener,
                             Fn1<SecurityPolicy, HttpRequestContext> policy,
                             Fn2<Promise<HttpResponseData>, HttpRequestContext, TerraHttpServer.Status> handler) {
    private static final String SEGMENT = "/[A-Za-z0-9._~-]+";

    /// `basePath` is `/` or `/segment[/segment...]` without a trailing slash; `/` needs its own listener, and no base
    /// path may sit in the reserved `/__terra` namespace.
    public static Result<TerraHttpMount> terraHttpMount(String basePath,
                                                        Option<HttpServerConfig> listener,
                                                        Fn1<SecurityPolicy, HttpRequestContext> policy,
                                                        Fn2<Promise<HttpResponseData>, HttpRequestContext, TerraHttpServer.Status> handler) {
        if (! (basePath.equals("/") || basePath.matches("(" + SEGMENT + ")+"))) {
            return new TerraHttpError.InvalidConfiguration("Invalid mount base path: " + basePath).result();
        }

        if (basePath.equals("/") && listener.isEmpty()) {
            return new TerraHttpError.InvalidConfiguration("Mount base path / requires its own listener").result();
        }

        if (basePath.equals("/__terra") || basePath.startsWith("/__terra/")) {
            return new TerraHttpError.InvalidConfiguration("Mount base path overlaps reserved /__terra namespace: " + basePath).result();
        }

        return Result.success(new TerraHttpMount(basePath, listener, policy, handler));
    }

    boolean shared() {
        return listener.isEmpty();
    }

    boolean owns(String path) {
        return basePath.equals("/") || path.equals(basePath) || path.startsWith(basePath + "/");
    }

    /// The reserved route prefix on the shared listener, in the trailing-slash form slice route paths are checked in.
    String reservedPrefix() {
        return basePath + "/";
    }
}
