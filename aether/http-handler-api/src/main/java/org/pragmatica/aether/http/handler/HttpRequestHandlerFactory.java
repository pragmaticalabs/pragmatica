// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import org.pragmatica.aether.slice.SliceInvokerFacade;


public interface HttpRequestHandlerFactory {
    HttpRequestHandler create(SliceInvokerFacade invoker);
}
