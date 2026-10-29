// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormat;
import org.pragmatica.lang.Cause;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_GOSSIP_KEY_ROTATE;


/// #683 — emergency, in-place gossip-key rotation. The leader generates fresh key material and
/// publishes it through consensus; running nodes adopt it live. ADMIN-only. The response carries key
/// ids, never the key.
///
/// Limits an operator must know before invoking this, stated in SECURITY.md. The FIRST
/// rotation carries no decrypt overlap — there is no prior record to carry — so boot-key ciphertext
/// is rejected the moment it lands; later rotations carry the previous key. A node booting
/// afterwards holds only the `cluster_secret`-derived key, so its gossip is rejected both ways until
/// the consensus replay of the rotation record hands it the cluster key; it dials its configured
/// peers, so this normally takes seconds (#1200), and the seeds raise `gossip-key-unknown-from-peer`
/// meanwhile. And rotation protects only against a leaked gossip key: a holder of `cluster_secret`
/// is still admitted and replays the record (#2474).
@Command(name = "rotate-gossip-key", description = "Rotate the SWIM gossip encryption key in place (ADMIN; after a suspected gossip-key leak)")
@SuppressWarnings("JBCT-RET-01")
class ClusterRotateGossipKeyCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.post(CLUSTER_GOSSIP_KEY_ROTATE, "{}"))
                            .fold(ClusterRotateGossipKeyCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(String json) {
        if (parent.outputOptions().format() == OutputFormat.JSON) {
            System.out.println(json);

            return ExitCode.SUCCESS;
        }

        System.out.println("Gossip key rotated (response: " + json + ")");

        return ExitCode.SUCCESS;
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
