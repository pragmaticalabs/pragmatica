// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
/// Two limits an operator must know before invoking this, both stated in SECURITY.md. The FIRST
/// rotation carries no decrypt overlap — there is no prior record to carry — so boot-key ciphertext
/// is rejected the moment it lands; later rotations carry the previous key. And a rotated cluster
/// **cannot grow until an operator acts**: a node booting afterwards holds only the
/// `cluster_secret`-derived key, cannot complete SWIM in either direction, and therefore never
/// reaches the consensus replay that would deliver the cluster key. Auto-heal replacements and
/// scale-up nodes are included. Existing running nodes are unaffected.
@Command(name = "rotate-gossip-key", description = "Rotate the SWIM gossip encryption key in place (ADMIN; after a suspected cluster_secret or gossip-key leak)")
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
