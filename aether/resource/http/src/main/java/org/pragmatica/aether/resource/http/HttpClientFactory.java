// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.http;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.lang.Promise;


public final class HttpClientFactory implements ResourceFactory<HttpClient, HttpClientConfig> {
    @Override
    public Class<HttpClient> resourceType() {
        return HttpClient.class;
    }

    @Override
    public Class<HttpClientConfig> configType() {
        return HttpClientConfig.class;
    }

    @Override
    public Promise<HttpClient> provision(HttpClientConfig config) {
        return Promise.success(JdkHttpClient.jdkHttpClient(config));
    }
}
