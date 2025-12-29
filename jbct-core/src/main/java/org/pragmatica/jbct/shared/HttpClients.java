package org.pragmatica.jbct.shared;

import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.JdkHttpOperations;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared HttpClient instances for JBCT operations.
 */
public sealed interface HttpClients permits HttpClients.unused {
    record unused() implements HttpClients {}

    HttpClient SHARED_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    HttpOperations SHARED_HTTP_OPS = JdkHttpOperations.jdkHttpOperations(SHARED_CLIENT);

    /**
     * Get the shared HttpClient instance.
     */
    static HttpClient client() {
        return SHARED_CLIENT;
    }

    /**
     * Get shared HttpOperations instance.
     */
    static HttpOperations httpOperations() {
        return SHARED_HTTP_OPS;
    }
}
