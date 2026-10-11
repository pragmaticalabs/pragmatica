// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.nio.file.Path;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// #1968: the one rule for a container node's `[backup] path`, however it arrives (a source's `node_config` TOML, an `AETHER_BACKUP_PATH`
/// variable of a compose file or of the provisioning host, a compute map). The path is mounted into the node's container, so it must be
/// absolute: a relative one renders a mount Docker refuses. A Docker node's repository lives on a per-node NAMED volume, which Docker
/// creates root-owned for every mount point except under `/data` (where the image's `aether` user owns it), so there the path must also
/// be `/data` or under it. A bind mount (cloud, SSH) is created and owned by uid 1000 by the renderer, so it accepts any absolute path.
/// Every input yields a refusal or none, never a throw: a value the platform cannot make a path of (a NUL) is a refusal too.
///
/// REACH, stated honestly: the node-load check (`ConfigLoader`) fires only for a config that carries `[cluster] environment` EXPLICITLY.
/// Provider- and compose-provisioned Docker nodes boot from the image's baked TOML and do not carry it, so for them the enforcement is the
/// provisioning side (the bootstrap parser for a docker source, `DockerEnvironmentIntegrationFactory` for the compute map); the node-load
/// rule is defence in depth. `[verified: v-2001 round 3 against 8506d41ec]`
public interface BackupPathRule {
    Path DATA = Path.of("/data");

    /// Why `path` cannot be written by the node, or empty when it can. `namedVolume`: the repository lives on a Docker named volume.
    static Option<String> refusal(String path, boolean namedVolume) {
        var value = path.strip();

        if (!value.startsWith("/")) {
            return Option.some("'" + value
                              + "' must be an absolute path: it is mounted into the node's container or created on its host");
        }
        // Normalised first, then compared by path COMPONENTS: "/data/../etc" is /etc, and "/database" is not under "/data".
        // The platform refuses some strings as paths (a NUL); that is a refusal here, never an exception to the caller.
        return Result.lift(Causes::fromThrowable,
                           () -> Path.of(value).normalize())
                     .fold(cause -> Option.some("'" + value.replace("\0", "\\0")
                                               + "' is not a valid path: " + cause.message()
                                                                                  .replace("\0", "\\0")),
                           normalised -> containment(value, normalised, namedVolume));
    }

    private static Option<String> containment(String value, Path normalised, boolean namedVolume) {
        return namedVolume && !normalised.startsWith(DATA)
               ? Option.some("'" + value
                            + "' must be under /data for a docker node: its repository lives on a named volume, "
                            + "which Docker creates root-owned everywhere except under /data, so the node could not write it")
               : Option.none();
    }
}
