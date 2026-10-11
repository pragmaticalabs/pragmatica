// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.List;

import org.pragmatica.lang.Promise;


public interface HttpRequestHandler {
    Promise<HttpResponseData> handle(HttpRequestContext ctx);
    List<HttpRouteDefinition> routes();
}
