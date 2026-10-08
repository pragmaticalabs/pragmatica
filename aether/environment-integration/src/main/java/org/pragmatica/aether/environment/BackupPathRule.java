// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;


/// #1968: the one rule for a container node's `[backup] path`, however it arrives (a source's `node_config` TOML, an `AETHER_BACKUP_PATH`
/// variable of a compose file or of the provisioning host, a compute map). The path is mounted into the node's container, so it must be
/// absolute: a relative one renders a mount Docker refuses. A Docker node's repository lives on a per-node NAMED volume, which Docker
/// creates root-owned for every mount point except under `/data` (where the image's `aether` user owns it), so there the path must also
/// be `/data` or under it. A bind mount (cloud, SSH) is created and owned by uid 1000 by the renderer, so it accepts any absolute path.
public interface BackupPathRule {
    /// Why `path` cannot be written by the node, or empty when it can. `namedVolume`: the repository lives on a Docker named volume.
    static Option<String> refusal(String path, boolean namedVolume) {
        var value = path.strip();

        if (!value.startsWith("/")) {
            return Option.some("'" + value + "' must be an absolute path: it is mounted into the node's container or created on its host");
        }

        if (namedVolume && !value.equals("/data") && !value.startsWith("/data/")) {
            return Option.some("'" + value + "' must be under /data for a docker node: its repository lives on a named volume, "
                              + "which Docker creates root-owned everywhere except under /data, so the node could not write it");
        }

        return Option.none();
    }
}
