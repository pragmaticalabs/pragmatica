/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.aether.cloud;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/// Wraps one Hetzner cloud server for integration testing.
/// Provides operations for JAR deployment, node startup, and health checks.
public record CloudNode(String nodeId, String publicIp, long serverId, Path privateKeyPath) {

    private static final Logger log = LoggerFactory.getLogger(CloudNode.class);
    private static final int MANAGEMENT_PORT = 8080;
    private static final int CLUSTER_PORT = 8090;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /// Factory method for creating a CloudNode.
    public static CloudNode cloudNode(String nodeId, String publicIp, long serverId, Path privateKeyPath) {
        return new CloudNode(nodeId, publicIp, serverId, privateKeyPath);
    }

    /// Uploads the aether-node JAR to the remote server.
    public Result<Unit> uploadJar(Path jarPath) {
        log.info("[{}] Uploading JAR to {}", nodeId, publicIp);
        var mkdirResult = RemoteCommandRunner.ssh(publicIp, "mkdir -p /opt/aether", privateKeyPath);

        if (mkdirResult.isFailure()) {
            return mkdirResult.mapToUnit();
        }

        return RemoteCommandRunner.scp(jarPath, publicIp, "/opt/aether/aether-node.jar", privateKeyPath);
    }

    /// Starts the aether node with the given peer list.
    public Result<Unit> startNode(String peerList) {
        log.info("[{}] Starting node on {} with peers: {}", nodeId, publicIp, peerList);
        var command = "nohup java -Xmx256m -XX:+UseZGC"
                      + " -jar /opt/aether/aether-node.jar"
                      + " --node-id=" + nodeId
                      + " --port=" + CLUSTER_PORT
                      + " --management-port=" + MANAGEMENT_PORT
                      + " --peers=" + peerList
                      + " > /opt/aether/node.log 2>&1 &";

        return RemoteCommandRunner.ssh(publicIp, command, privateKeyPath).mapToUnit();
    }

    /// Queries the health endpoint of this node.
    public Result<String> getHealth() {
        return httpGet("/api/health");
    }

    /// Queries the status endpoint of this node.
    public Result<String> getStatus() {
        return httpGet("/api/status");
    }

    /// Queries the nodes endpoint of this node.
    public Result<String> getNodes() {
        return httpGet("/api/nodes");
    }

    /// Performs an HTTP POST to the management API.
    public Result<String> httpPost(String path, String body, String contentType) {
        var url = "http://" + publicIp + ":" + MANAGEMENT_PORT + path;

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build();

            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Result.success(response.body());
            }

            return new CloudTestError.HttpRequestFailed(url, "HTTP " + response.statusCode() + ": " + response.body()).result();
        } catch (Exception e) {
            return new CloudTestError.HttpRequestFailed(url, e.getMessage()).result();
        }
    }

    /// Performs an HTTP PUT with binary body to the management API.
    public Result<String> httpPut(String path, byte[] body) {
        var url = "http://" + publicIp + ":" + MANAGEMENT_PORT + path;

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(60))
                .build();

            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Result.success(response.body());
            }

            return new CloudTestError.HttpRequestFailed(url, "HTTP " + response.statusCode() + ": " + response.body()).result();
        } catch (Exception e) {
            return new CloudTestError.HttpRequestFailed(url, e.getMessage()).result();
        }
    }

    /// Deploys a blueprint via TOML to the management API.
    public Result<String> deploy(String artifact, int instances) {
        var blueprintId = "cloud.test:deploy:1.0.0";
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(blueprintId, artifact, instances);

        return httpPost("/api/blueprint", blueprint, "application/toml");
    }

    /// Uploads an artifact JAR to the DHT repository via management API.
    public Result<String> uploadArtifact(String groupPath, String artifactId, String version, byte[] jarContent) {
        var path = "/repository/" + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
        log.info("[{}] Uploading artifact to {} ({} bytes)", nodeId, path, jarContent.length);

        return httpPut(path, jarContent);
    }

    /// Fetches slice status from the management API.
    public Result<String> getSlicesStatus() {
        return httpGet("/api/slices/status");
    }

    /// Fetches registered routes from the management API.
    public Result<String> getRoutes() {
        return httpGet("/api/routes");
    }

    /// Invokes a slice endpoint via HTTP GET on the APP port (same as management for now).
    public Result<String> invokeGet(String path) {
        return httpGet(path);
    }

    /// Undeploys a blueprint by ID.
    public Result<String> undeploy(String blueprintId) {
        return httpDelete("/api/blueprint/" + blueprintId);
    }

    /// Scales a deployed slice.
    public Result<String> scale(String artifact, int instances) {
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";

        return httpPost("/api/scale", body, "application/json");
    }

    // --- Leaf: perform HTTP GET to management API ---

    private Result<String> httpGet(String path) {
        var url = "http://" + publicIp + ":" + MANAGEMENT_PORT + path;

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Result.success(response.body());
            }

            return new CloudTestError.HttpRequestFailed(url, "HTTP " + response.statusCode()).result();
        } catch (Exception e) {
            return new CloudTestError.HttpRequestFailed(url, e.getMessage()).result();
        }
    }

    // --- Leaf: perform HTTP DELETE to management API ---

    private Result<String> httpDelete(String path) {
        var url = "http://" + publicIp + ":" + MANAGEMENT_PORT + path;

        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();

            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Result.success(response.body());
            }

            return new CloudTestError.HttpRequestFailed(url, "HTTP " + response.statusCode()).result();
        } catch (Exception e) {
            return new CloudTestError.HttpRequestFailed(url, e.getMessage()).result();
        }
    }
}
