package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.pragmatica.lang.Option.option;


/// Shared HTTP client for cluster CLI commands that call the management API.
public sealed interface ClusterHttpClient {
    record unused() implements ClusterHttpClient{}

    HttpOperations HTTP_OPS = JdkHttpOperations.jdkHttpOperations();

    static Result<String> fetch(ManagementRoute route, List<String> params) {
        return route.assemble(params).flatMap(ClusterHttpClient::fetchPath);
    }

    static Result<String> fetch(ManagementRoute route) {
        return fetch(route, List.of());
    }

    static Result<String> fetch(ManagementRoute route, List<String> params, String queryString) {
        return route.assemble(params).map(path -> queryString == null || queryString.isEmpty()
                                                 ? path
                                                 : path + "?" + queryString)
                             .flatMap(ClusterHttpClient::fetchPath);
    }

    static Result<String> post(ManagementRoute route, List<String> params, String jsonBody) {
        return route.assemble(params).flatMap(path -> postPath(path, jsonBody));
    }

    static Result<String> post(ManagementRoute route, String jsonBody) {
        return post(route, List.of(), jsonBody);
    }

    static Result<String> put(ManagementRoute route, List<String> params, String jsonBody) {
        return route.assemble(params).flatMap(path -> putPath(path, jsonBody));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> fetchPath(String path) {
        return resolveEndpoint().flatMap(endpoint -> doGet(endpoint, path));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> postPath(String path,
                                                                                              String jsonBody) {
        return resolveEndpoint().flatMap(endpoint -> doPost(endpoint, path, jsonBody));
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> putPath(String path,
                                                                                             String jsonBody) {
        return resolveEndpoint().flatMap(endpoint -> doPut(endpoint, path, jsonBody));
    }

    static Result<String> resolveEndpoint() {
        return ClusterRegistry.load().flatMap(ClusterHttpClient::extractEndpoint);
    }

    private static Result<String> extractEndpoint(ClusterRegistry registry) {
        return registry.current().map(ClusterRegistry.ClusterEntry::endpoint)
                               .toResult(HttpError.NO_ACTIVE_CLUSTER);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> doGet(String endpoint,
                                                                                           String path) {
        var uri = URI.create(endpoint + path);
        var apiKey = resolveApiKey();
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .GET();
        apiKey.onPresent(key -> builder.header("X-API-Key", key));
        return HTTP_OPS.sendString(builder.build()).await()
                                  .flatMap(ClusterHttpClient::extractBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> doPost(String endpoint,
                                                                                            String path,
                                                                                            String jsonBody) {
        var uri = URI.create(endpoint + path);
        var apiKey = resolveApiKey();
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        apiKey.onPresent(key -> builder.header("X-API-Key", key));
        return HTTP_OPS.sendString(builder.build()).await()
                                  .flatMap(ClusterHttpClient::extractBody);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-SEQ-01"}) private static Result<String> doPut(String endpoint,
                                                                                           String path,
                                                                                           String jsonBody) {
        var uri = URI.create(endpoint + path);
        var apiKey = resolveApiKey();
        var builder = HttpRequest.newBuilder().uri(uri)
                                            .header("Content-Type", "application/json")
                                            .PUT(HttpRequest.BodyPublishers.ofString(jsonBody));
        apiKey.onPresent(key -> builder.header("X-API-Key", key));
        return HTTP_OPS.sendString(builder.build()).await()
                                  .flatMap(ClusterHttpClient::extractBody);
    }

    private static Result<String> extractBody(HttpResult<String> response) {
        return response.statusCode() >= 200 && response.statusCode() <300
              ? Result.success(response.body())
              : new HttpError.ApiError(response.statusCode(), response.body()).result();
    }

    private static Option<String> resolveApiKey() {
        return ClusterRegistry.load().option()
                                   .flatMap(ClusterRegistry::current)
                                   .flatMap(ClusterRegistry.ClusterEntry::apiKeyEnv)
                                   .flatMap(envName -> option(System.getenv(envName)));
    }

    sealed interface HttpError extends Cause {
        HttpError NO_ACTIVE_CLUSTER = new SimpleError("No active cluster context. Use 'aether cluster use <name>' to select one.");

        record SimpleError(String message) implements HttpError{}

        record ApiError(int statusCode, String body) implements HttpError {
            @Override public String message() {
                return "HTTP " + statusCode + ": " + body;
            }
        }
    }
}
