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
}
