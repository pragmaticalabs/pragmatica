// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.http;

import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.lang.Result;


/// HTTP/1.1 transport (optionally TLS), API version mounting, and inherited route security.
public record TerraHttpConfig(HttpServerConfig transport, RouteMountMode mountMode, SecurityPolicy defaultPolicy) {
    public static Result<TerraHttpConfig> terraHttpConfig(HttpServerConfig transport,
                                                          RouteMountMode mountMode,
                                                          SecurityPolicy defaultPolicy) {
        if (defaultPolicy instanceof SecurityPolicy.Unspecified || defaultPolicy instanceof SecurityPolicy.unused) {
            return new TerraHttpError.InvalidConfiguration("Default HTTP security must be explicit").result();
        }

        if (!transport.webSocketEndpoints().isEmpty() || transport.chunkedWriteEnabled()) {
            return new TerraHttpError.InvalidConfiguration("Slice HTTP hosting requires buffered responses; configure WebSocket/streaming hosts separately").result();
        }

        if (transport.port() < 0 || transport.port() > 65535 || transport.maxContentLength() <= 0) {
            return new TerraHttpError.InvalidConfiguration("Invalid HTTP port or maximum body size").result();
        }

        if (mountMode.isHeaderMode() && !mountMode.headerName().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            return new TerraHttpError.InvalidConfiguration("Invalid API version header name").result();
        }

        return Result.success(new TerraHttpConfig(transport, mountMode, defaultPolicy));
    }
}
