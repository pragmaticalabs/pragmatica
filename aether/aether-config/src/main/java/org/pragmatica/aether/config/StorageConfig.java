/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
 */

package org.pragmatica.aether.config;

/// Configuration for a single named hierarchical storage instance.
///
/// Presence in the storage map implies the instance is enabled.
///
/// TOML section (one per named instance):
/// ```toml
/// [storage.artifacts]
/// memory_max_bytes = 268435456     # 256 MB
/// disk_max_bytes = 10737418240     # 10 GB
/// disk_path = "/data/aether/storage/artifacts"
/// snapshot_path = "/data/aether/metadata-snapshots/artifacts"
/// snapshot_mutation_threshold = 1000
/// snapshot_max_interval = "60s"
/// snapshot_retention_count = 5
/// ```
///
/// @param memoryMaxBytes maximum bytes for in-memory tier
/// @param diskMaxBytes maximum bytes for local disk tier
/// @param diskPath base directory for disk tier block files
/// @param snapshotPath directory for metadata snapshots
/// @param snapshotMutationThreshold mutations before auto-snapshot
/// @param snapshotMaxInterval max time between snapshots (e.g., "60s")
/// @param snapshotRetentionCount number of old snapshots to retain
public record StorageConfig(long memoryMaxBytes,
                            long diskMaxBytes,
                            String diskPath,
                            String snapshotPath,
                            int snapshotMutationThreshold,
                            String snapshotMaxInterval,
                            int snapshotRetentionCount) {

    /// Default storage instance config with generic paths.
    public static StorageConfig storageConfig() {
        return new StorageConfig(256 * 1024 * 1024, 10L * 1024 * 1024 * 1024,
                                 "/data/aether/storage", "/data/aether/metadata-snapshots",
                                 1000, "60s", 5);
    }

    /// Full factory.
    public static StorageConfig storageConfig(long memoryMaxBytes, long diskMaxBytes,
                                               String diskPath, String snapshotPath,
                                               int snapshotMutationThreshold, String snapshotMaxInterval,
                                               int snapshotRetentionCount) {
        return new StorageConfig(memoryMaxBytes, diskMaxBytes, diskPath, snapshotPath,
                                 snapshotMutationThreshold, snapshotMaxInterval, snapshotRetentionCount);
    }
}
