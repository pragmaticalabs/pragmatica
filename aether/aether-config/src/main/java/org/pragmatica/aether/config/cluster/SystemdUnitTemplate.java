// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

/// The `aether-node.service` systemd unit for JVM-mode hosts, rendered into the cloud-init user-data
/// by [NodeUserDataRenderer].
///
/// #1021 — the unit exists for VISIBILITY, not for restart. A JVM-mode node used to be launched as a
/// bare `java -jar … &`, so `systemctl list-units | grep -i aether` returned nothing: there was no
/// unit, no `systemctl status`, no `journalctl -u`, and a crash was a silent absence with nothing
/// between "healthy" and "the VM was replaced minutes later". The unit closes that gap and changes
/// nothing about who recovers the node.
///
/// **It does NOT address #966** (a node that OOMs but never exits keeps its membership slot). This
/// addresses the opposite case — a clean exit nobody can see. A process that is still running is
/// `active` to systemd no matter what it is failing to do, so no unit can surface #966's shape.
///
/// Lived in `aether/cli` with ZERO production callers until #1021 wired it in; it is in `aether-config`
/// now because that is where [NodeUserDataRenderer] lives and `aether-config` cannot depend on `cli`.
/// Moved rather than copied, so there is still exactly one.
public sealed interface SystemdUnitTemplate {
    record unused() implements SystemdUnitTemplate {}

    String DEFAULT_LAUNCHER_PATH = "/opt/aether/run-node.sh";
    String DEFAULT_ENV_FILE_PATH = "/etc/aether/node.env";
    /// `root`, matching what the JVM already ran as — cloud-init executes as root and the bare
    /// `nohup java` this unit replaces inherited that. The previous, uncalled version of this template
    /// said `aether`, which would have been a real improvement AND an unverified behaviour change on a
    /// path only a paid cloud run exercises: the `aether` account is created only when the operator
    /// supplied SSH keys, the jar is root-owned, and `aether.toml` is chowned to uid 1000 rather than
    /// to that account by name. Dropping privileges is worth doing and is a change of its own, with its
    /// own cloud run; wiring the unit in must not smuggle it.
    String DEFAULT_USER = "root";
    String DEFAULT_GROUP = "root";

    /// `Restart=no` IS THE POINT, and it is pinned by a test that fails if it ever becomes anything
    /// else. Aether uses a terminal-removal membership model: a dead NodeId never returns under the
    /// same identity, and recovery is a brand-new node with a new ULID minted by CTM auto-heal.
    /// Restarting the crashed node under the SAME identity resurrects a NodeId the cluster has already
    /// removed. That is not a theory — `aether/docs/operators/deployment-recovery.md` §1 mandates
    /// `Restart=no` for systemd by name ("not optional … required for cluster correctness"), and §2.1
    /// records the multi-hour Hetzner chaos-test stall it caused: the runtime respawned the node, the
    /// same-id rejoin was rejected, the container respawn-looped, and CTM never observed the failure at
    /// all because the restart beat its detector.
    ///
    /// A future reader who "fixes" this to `on-failure` re-creates that incident. The unit-level
    /// assertion in `SystemdUnitTemplateTest` is what refuses them.
    String RESTART_POLICY = "Restart=no";

    /// `execStart` is a launcher script rather than a `java` command line because the node's `--peers`
    /// argument must be OMITTED entirely when the peer list is empty — the normal state of a
    /// bootstrap node's first boot, before `BootstrapPhaseDeploy` finalizes PEERS. Expressing that in
    /// ExecStart would lean on systemd's empty-variable word-splitting rules; a launcher keeps the
    /// exact conditional the bare `nohup` launch already used, so the unit changes who supervises the
    /// process without changing how it is invoked.
    ///
    /// `EnvironmentFile=-` (leading dash) tolerates an absent file rather than refusing to start, so a
    /// unit that is enabled before the env file is written fails visibly at the node's own startup
    /// rather than as a systemd load error an operator has to decode.
    static String generate(String execStart, String environmentFile, String user, String group) {
        var sb = new StringBuilder();

        sb.append("[Unit]\n");
        sb.append("Description=Aether Node\n");
        sb.append("After=network-online.target\n");
        sb.append("Wants=network-online.target\n");
        sb.append('\n');
        sb.append("[Service]\n");
        sb.append("Type=simple\n");
        sb.append("User=").append(user).append('\n');
        sb.append("Group=").append(group).append('\n');
        sb.append("EnvironmentFile=-").append(environmentFile).append('\n');
        sb.append("ExecStart=").append(execStart).append('\n');
        sb.append(RESTART_POLICY).append('\n');
        sb.append("StandardOutput=journal\n");
        sb.append("StandardError=journal\n");
        sb.append('\n');
        sb.append("[Install]\n");
        sb.append("WantedBy=multi-user.target\n");

        return sb.toString();
    }

    static String generateDefault() {
        return generate(DEFAULT_LAUNCHER_PATH, DEFAULT_ENV_FILE_PATH, DEFAULT_USER, DEFAULT_GROUP);
    }
}
