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

import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Cause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.pragmatica.cloud.hetzner.api.SshKey.CreateSshKeyRequest.createSshKeyRequest;

/// Manages ephemeral SSH key lifecycle for cloud integration tests.
/// Generates a local ed25519 key pair, uploads the public key to Hetzner,
/// and cleans up both on close.
public final class SshKeyManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SshKeyManager.class);

    private final HetznerClient client;
    private final Path tempDir;
    private final Path privateKeyPath;
    private final Path publicKeyPath;
    private long hetznerKeyId;

    private SshKeyManager(HetznerClient client, Path tempDir) {
        this.client = client;
        this.tempDir = tempDir;
        this.privateKeyPath = tempDir.resolve("id_ed25519");
        this.publicKeyPath = tempDir.resolve("id_ed25519.pub");
    }

    /// Creates and initializes an SSH key manager.
    /// Generates a local key pair and uploads the public key to Hetzner.
    public static SshKeyManager sshKeyManager(HetznerClient client, String keyName) {
        var manager = createManager(client);
        manager.generateKeyPair();
        manager.uploadToHetzner(keyName);
        return manager;
    }

    /// Returns the Hetzner SSH key ID for use in server creation.
    public long hetznerKeyId() {
        return hetznerKeyId;
    }

    /// Returns the path to the local private key file.
    public Path privateKeyPath() {
        return privateKeyPath;
    }

    @Override
    public void close() {
        deleteFromHetzner();
        deleteLocalFiles();
    }

    // --- Leaf: create manager with temp directory ---

    private static SshKeyManager createManager(HetznerClient client) {
        try {
            var tempDir = Files.createTempDirectory("aether-cloud-test-ssh-");
            return new SshKeyManager(client, tempDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create temp directory for SSH keys", e);
        }
    }

    // --- Leaf: generate local key pair ---

    private void generateKeyPair() {
        try {
            var process = new ProcessBuilder(
                "ssh-keygen", "-t", "ed25519", "-f", privateKeyPath.toString(), "-N", "", "-q")
                .redirectErrorStream(true)
                .start();

            var completed = process.waitFor(30, TimeUnit.SECONDS);

            if (!completed || process.exitValue() != 0) {
                throw new IllegalStateException("ssh-keygen failed with exit code: " + process.exitValue());
            }

            LOG.info("Generated SSH key pair at {}", privateKeyPath);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to generate SSH key pair", e);
        }
    }

    // --- Leaf: upload public key to Hetzner ---

    private void uploadToHetzner(String keyName) {
        try {
            var publicKey = Files.readString(publicKeyPath).trim();
            var sshKey = client.createSshKey(createSshKeyRequest(keyName, publicKey))
                               .await()
                               .fold(SshKeyManager::failUpload, key -> key);

            this.hetznerKeyId = sshKey.id();
            LOG.info("Uploaded SSH key to Hetzner with ID {}", hetznerKeyId);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read public key file", e);
        }
    }

    // --- Leaf: delete SSH key from Hetzner ---

    private void deleteFromHetzner() {
        try {
            client.deleteSshKey(hetznerKeyId).await();
            LOG.info("Deleted SSH key {} from Hetzner", hetznerKeyId);
        } catch (Exception e) {
            LOG.warn("Failed to delete SSH key {} from Hetzner: {}", hetznerKeyId, e.getMessage());
        }
    }

    // --- Leaf: delete local key files ---

    private void deleteLocalFiles() {
        deleteFileSilently(privateKeyPath);
        deleteFileSilently(publicKeyPath);
        deleteFileSilently(tempDir);
        LOG.info("Cleaned up local SSH key files");
    }

    private static <T> T failUpload(Cause cause) {
        throw new IllegalStateException("Failed to upload SSH key: " + cause.message());
    }

    private static void deleteFileSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Failed to delete {}: {}", path, e.getMessage());
        }
    }
}
