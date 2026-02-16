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
