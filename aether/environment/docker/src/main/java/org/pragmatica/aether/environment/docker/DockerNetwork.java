// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.List;

import org.pragmatica.lang.Promise;


/// The docker network a docker-source cluster's containers join (#2131). Every container is started with `--network <name>`, which fails with
/// "network not found" when nothing created it. Bootstrap therefore ensures it: reuse it when present, create it when absent. Only a network
/// bootstrap CREATED is ever removed (by destroy, and only while empty), so a network the user provided is never touched.
public interface DockerNetwork {
    enum Ensured {
        /// The network already existed: a user-provided (or earlier-run) network, reused and never removed by us.
        EXISTING,
        /// Bootstrap created it, so teardown may remove it once empty.
        CREATED
    }

    enum Removal {
        REMOVED,
        /// Containers (ours or the user's) are still attached: the network stays.
        IN_USE,
        ABSENT
    }

    /// Absence is any failure of `docker network inspect`: the runner reports a non-zero exit without a reason that could be told apart from a
    /// daemon error, so a create that then fails is re-checked once before it is reported (a concurrent creator is a reuse, not a failure).
    static Promise<Ensured> ensure(DockerCommandRunner runner, String name) {
        return runner.execute(inspect(name))
                     .map(_ -> Ensured.EXISTING)
                     .orElse(() -> create(runner, name));
    }

    private static Promise<Ensured> create(DockerCommandRunner runner, String name) {
        return runner.execute(List.of("docker", "network", "create", name))
                     .map(_ -> Ensured.CREATED)
                     .orElse(() -> runner.execute(inspect(name)).map(_ -> Ensured.EXISTING));
    }

    /// Removes the network only when no container is attached. Never forces: a network with members is left in place.
    static Promise<Removal> removeIfEmpty(DockerCommandRunner runner, String name) {
        return runner.execute(List.of("docker", "network", "inspect", "--format", "{{len .Containers}}", name))
                     .flatMap(attached -> removeWhenEmpty(runner, name, attached.strip()))
                     .orElse(() -> Promise.success(Removal.ABSENT));
    }

    private static Promise<Removal> removeWhenEmpty(DockerCommandRunner runner, String name, String attached) {
        return "0".equals(attached)
               ? runner.execute(List.of("docker", "network", "rm", name)).map(_ -> Removal.REMOVED)
               : Promise.success(Removal.IN_USE);
    }

    private static List<String> inspect(String name) {
        return List.of("docker", "network", "inspect", name);
    }
}
