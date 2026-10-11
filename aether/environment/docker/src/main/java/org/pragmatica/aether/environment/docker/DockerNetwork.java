// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The docker network a docker-source cluster's containers join (#2131). Every container is started with `--network <name>`, which fails with
/// "network not found" when nothing created it. Bootstrap therefore ensures it: reuse it when present, create it when absent.
///
/// OWNERSHIP is a docker label, not a memory: a network bootstrap creates carries `aether.created-by-cluster=<cluster>`. A bootstrap that finds an
/// existing network carrying ITS OWN cluster's label owns it (an earlier run left it in place), so a re-run over a kept network does not leak it. One
/// with no label, or another cluster's, is the user's or someone else's: reused, never owned, never removed.
public interface DockerNetwork {
    String OWNER_LABEL = "aether.created-by-cluster";

    enum Ensured {
        /// The network is the user's (or another cluster's): reused, never ours to remove.
        FOREIGN,
        /// Bootstrap created it.
        CREATED,
        /// It already existed carrying THIS cluster's label: an earlier run of this cluster made it, so it is ours again.
        OWN;
        public boolean owned() {
            return this != FOREIGN;
        }
    }

    enum Removal {
        REMOVED,
        /// Containers (ours or the user's) are still attached: the network stays.
        IN_USE,
        /// The network does not carry this cluster's label: not ours, left alone.
        NOT_OURS,
        ABSENT
    }

    /// Absence is any failure of `docker network inspect`: the runner reports a non-zero exit without a reason that could be told apart from a
    /// daemon error, so a create that then fails is re-checked once before it is reported (a concurrent creator is a reuse, not a failure).
    static Promise<Ensured> ensure(DockerCommandRunner runner, String name, String clusterName) {
        return ownerOf(runner, name, clusterName).orElse(() -> create(runner, name, clusterName));
    }

    private static Promise<Ensured> create(DockerCommandRunner runner, String name, String clusterName) {
        return runner.execute(List.of("docker", "network", "create", "--label", OWNER_LABEL + "=" + clusterName, name))
                     .map(_ -> Ensured.CREATED)
                     .orElse(() -> ownerOf(runner, name, clusterName));
    }

    private static Promise<Ensured> ownerOf(DockerCommandRunner runner, String name, String clusterName) {
        return runner.execute(List.of("docker",
                                      "network",
                                      "inspect",
                                      "--format",
                                      "{{index .Labels \"" + OWNER_LABEL + "\"}}",
                                      name))
                     .map(label -> clusterName.equals(label.strip())
                                   ? Ensured.OWN
                                   : Ensured.FOREIGN);
    }

    /// Removes exactly the network named, only if it carries `clusterName`'s label and no container is attached. Never forces.
    ///
    /// ABSENT means the network really is not there (the inspect failed). A `docker network rm` that fails is NOT absent: it comes back as a failed
    /// promise carrying docker's own cause, so the caller reports it and counts the cleanup as unclean instead of believing a network that is
    /// still there has gone.
    static Promise<Removal> removeIfOwnedAndEmpty(DockerCommandRunner runner, String name, String clusterName) {
        return runner.execute(List.of("docker",
                                      "network",
                                      "inspect",
                                      "--format",
                                      "{{index .Labels \"" + OWNER_LABEL + "\"}}|{{len .Containers}}",
                                      name))
                     .map(Option::some)
                     .orElse(() -> Promise.success(Option.<String> none()))
                     .flatMap(facts -> facts.fold(() -> Promise.success(Removal.ABSENT),
                                                  present -> removeWhenOwnedAndEmpty(runner, name, clusterName, present.strip())));
    }

    private static Promise<Removal> removeWhenOwnedAndEmpty(DockerCommandRunner runner,
                                                            String name,
                                                            String clusterName,
                                                            String facts) {
        var bar = facts.lastIndexOf('|');
        var label = bar < 0
                    ? ""
                    : facts.substring(0, bar);
        var attached = bar < 0
                       ? facts
                       : facts.substring(bar + 1);

        if (!clusterName.equals(label)) {
            return Promise.success(Removal.NOT_OURS);
        }

        return "0".equals(attached)
               ? runner.execute(List.of("docker", "network", "rm", name))
                       .map(_ -> Removal.REMOVED)
               : Promise.success(Removal.IN_USE);
    }
}
