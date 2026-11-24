package org.pragmatica.jbct.init;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.pragmatica.http.HttpClientError;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Promise;

/// Test double for the HTTP seam of the AI-tools installer/updater (#2363): serves canned GitHub answers by exact URL
/// and records every request, so no test reaches the network. An unknown URL answers 404, like an unknown ref.
public final class FakeGitHub implements HttpOperations {
    private static final String API = "https://api.github.com/repos/siy/coding-technology/";
    private static final String RAW = "https://raw.githubusercontent.com/siy/coding-technology/";

    private final Map<String, HttpResult<String>> answers = new TreeMap<>();
    private final List<String> requests = new ArrayList<>();
    private final boolean offline;

    private FakeGitHub(boolean offline) {
        this.offline = offline;
    }

    public static FakeGitHub fakeGitHub() {
        return new FakeGitHub(false);
    }

    /// A host that cannot be reached: every request fails with a connection error.
    public static FakeGitHub offlineGitHub() {
        return new FakeGitHub(true);
    }

    /// `ref` (a tag) resolves to `sha`; the commit `sha` carries `files` (path under `ai-tools/` to content).
    public FakeGitHub withRef(String ref, String sha, Map<String, String> files) {
        answers.put(API + "commits/" + ref, ok("{\"sha\":\"" + sha + "\"}"));
        answers.put(API + "git/trees/" + sha + "?recursive=1", ok(treeOf(files)));
        files.forEach((path, content) -> answers.put(RAW + sha + "/ai-tools/" + path, ok(content)));
        return this;
    }

    /// `ref` answers with the given status (e.g. 404, 422, 500).
    public FakeGitHub withRefStatus(String ref, int status) {
        answers.put(API + "commits/" + ref, new HttpResult<>(status, HttpHeaders.of(Map.of(), (_, _) -> true), ""));
        return this;
    }

    public List<String> requests() {
        return List.copyOf(requests);
    }

    public boolean requested(String urlPart) {
        return requests.stream().anyMatch(url -> url.contains(urlPart));
    }

    @Override
    public Promise<HttpResult<String>> sendString(HttpRequest request) {
        var url = request.uri().toString();

        requests.add(url);
        return offline
               ? HttpClientError.ConnectionFailed.connectionFailed("offline (test stub)").<HttpResult<String>>promise()
               : Promise.success(answers.getOrDefault(url, status(404)));
    }

    @Override
    public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
        return HttpClientError.ConnectionFailed.connectionFailed("only sendString is served (test stub)").promise();
    }

    private static HttpResult<String> ok(String body) {
        return new HttpResult<>(200, HttpHeaders.of(Map.of(), (_, _) -> true), body);
    }

    private static HttpResult<String> status(int code) {
        return new HttpResult<>(code, HttpHeaders.of(Map.of(), (_, _) -> true), "");
    }

    private static String treeOf(Map<String, String> files) {
        var entries = files.keySet()
                           .stream()
                           .map(path -> "{\"path\":\"ai-tools/" + path + "\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"x\"}")
                           .toList();

        return "{\"tree\":[" + String.join(",", entries) + "]}";
    }
}
