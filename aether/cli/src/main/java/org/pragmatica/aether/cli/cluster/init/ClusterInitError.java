// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.lang.Cause;


public sealed interface ClusterInitError extends Cause {
    record TooFewNodes(int got) implements ClusterInitError {
        @Override
        public String message() {
            return "Aether requires at least " + TopologyDeriver.MINIMUM_TOTAL_NODES
                 + " nodes (got " + got
                 + "). A smaller cluster has no fault budget during maintenance: a rolling restart "
                 + "takes one node down and any further fault then loses quorum. Use --nodes " + TopologyDeriver.MINIMUM_TOTAL_NODES
                 + ", 7 (recommended) or " + TopologyDeriver.MAXIMUM_CORE_NODES
                 + ". For local single-process dev/test, use --target forge.";
        }
    }

    record InvalidTopology(String detail) implements ClusterInitError {
        @Override
        public String message() {
            return "Invalid topology: " + detail;
        }
    }

    record OutputExists(String path) implements ClusterInitError {
        @Override
        public String message() {
            return "Output file already exists: " + path + ". Re-run with --force to overwrite.";
        }
    }

    record MissingField(String name) implements ClusterInitError {
        @Override
        public String message() {
            return "Required field missing or invalid: " + name;
        }
    }

    /// A cloud target reached config generation with no instance type, and there is deliberately
    /// NO default to fall back on.
    ///
    /// Cloud providers RETIRE instance types. `ClusterConfigWizard.defaultInstanceFor` used to
    /// answer this with `cx21` (Hetzner), `t3.medium` (AWS), `e2-medium` (GCP) and `Standard_B2s`
    /// (Azure), and Hetzner has since deleted `cx21` from its catalogue — so pressing Enter through
    /// the wizard produced a config that could not provision in ANY region.
    ///
    /// The defect is LATENT, not observed. An earlier draft of this comment blamed the `cx21`
    /// default for three `422 (invalid_input): unsupported location for server type` failures on
    /// 2026-09-10; that attribution was wrong and is corrected here. Those runs passed `cpx11`
    /// explicitly, and their cause remains undiagnosed. Only the interactive wizard could ever
    /// reach the default — the `--non-interactive` path already refused a missing
    /// `--instance-type` — so no batch invocation was ever exposed to it.
    ///
    /// A baked-in default is a snapshot of someone else's catalogue and rots with no signal on
    /// this side, so the operator is required to supply the value. The message names the flag and
    /// the provider because the catalogue that has to be consulted is the provider's, not ours.
    record InstanceTypeRequired(String provider) implements ClusterInitError {
        @Override
        public String message() {
            return "No instance type given for cloud provider '" + provider
                 + "', and Aether ships no default on purpose: supply --instance-type. "
                 + "Providers retire instance types and vary availability by location, so the value "
                 + "must come from " + provider
                 + "'s CURRENT catalogue for the chosen region.";
        }
    }

    /// A cloud target reached config generation with no region, and there is deliberately NO
    /// default to fall back on.
    ///
    /// The reason is NOT catalogue rot — provider location sets are comparatively stable. It is
    /// that a defaulted region silently decides WHERE THE OPERATOR'S DATA PHYSICALLY LIVES, which
    /// is a data-residency and jurisdiction question (GDPR, sovereignty, latency, egress cost) that
    /// a tool must not answer on someone's behalf by falling through to a literal.
    ///
    /// Note the failure modes differ, and the safer-looking one is the dangerous one: a wrong
    /// instance type FAILS LOUD at the provider API, whereas a wrong region PROVISIONS PERFECTLY
    /// and is discovered by an auditor. "We put your cluster in Helsinki because you did not say"
    /// is the worse outcome precisely because it succeeds.
    record RegionRequired(String provider) implements ClusterInitError {
        @Override
        public String message() {
            return "No region given for cloud provider '" + provider
                 + "', and Aether ships no default on purpose: supply --region. "
                 + "The region decides which jurisdiction your cluster's data physically resides in, "
                 + "so it must be chosen deliberately for your residency, latency and egress "
                 + "requirements — never inherited from a default.";
        }
    }

    /// A cloud target selected a preset that carries admin-scoped rules but supplied no admin CIDR.
    ///
    /// `FirewallPresets.addAdminScoped` opens port 22 (bootstrap SSH) and the management port to
    /// the operator's network, and is reached from BOTH `standardRules` and `restrictiveRules`.
    /// Only RESTRICTIVE was ever given the CIDR, so `--firewall=standard` — THE DEFAULT — generated
    /// a config with neither rule. Bootstrap deploys the runtime over SSH and its Phase 7 readiness
    /// gate polls the management API on each node's public address, so that config cannot bootstrap
    /// even against perfectly healthy nodes.
    ///
    /// The CIDR is REQUIRED rather than auto-detected. Detecting the operator's current address and
    /// baking it in silently is the same class of failure as a defaulted region: it succeeds, and
    /// the wrong network is discovered later — behind NAT, on a dynamic address, or when the
    /// cluster is administered from somewhere else entirely, the detected value is simply wrong.
    record AdminCidrRequired(String preset) implements ClusterInitError {
        @Override
        public String message() {
            return "Firewall preset " + preset
                 + " scopes bootstrap SSH (port 22) and the management API to an admin network, "
                 + "but no admin CIDR was given: supply --admin-cidr. "
                 + "Without it neither rule is emitted and `aether cluster bootstrap` cannot reach "
                 + "the nodes it provisions. It is not auto-detected on purpose — a detected address "
                 + "is silently wrong behind NAT, on a dynamic IP, or when you administer the "
                 + "cluster from elsewhere. Use --firewall=open only if you secure the network yourself.";
        }
    }

    /// A cloud target reached config generation with no SSH public key.
    ///
    /// `aether cluster init --target cloud` used to write NO SSH reference anywhere, while
    /// `SshKeyResolver.resolveOrFailIfCloud` refuses any cloud cluster that cannot resolve one. So
    /// `init` printed "Next: run bootstrap" and `bootstrap` then rejected the very file `init` had
    /// just produced. The two halves were each correct and their composition was not.
    ///
    /// Nothing is inferred here: a key could have been guessed from `~/.ssh/id_*.pub`, but picking
    /// which identity may administer a cluster is the operator's decision, not a default.
    record SshPublicKeyRequired() implements ClusterInitError {
        @Override
        public String message() {
            return "A cloud target needs an SSH public key and none was given: supply "
                 + "--ssh-public-key <path>. Provisioned VMs get this key injected at create time, "
                 + "and `aether cluster bootstrap` refuses a cloud cluster whose key it cannot "
                 + "resolve — without it the config this command writes cannot be bootstrapped. "
                 + "It is not inferred from ~/.ssh on purpose: which identity may administer the "
                 + "cluster is your decision.";
        }
    }

    /// A flag that does nothing for the chosen target is refused rather than silently swallowed.
    /// `--ssh-key` names the PRIVATE key used to reach existing SSH hosts; a cloud target provisions
    /// new VMs and needs the PUBLIC key instead, so accepting it here only looked like it worked.
    record FlagNotApplicable(String flag, String target, String instead) implements ClusterInitError {
        @Override
        public String message() {
            return flag + " does not apply to a " + target + " target and would be ignored: " + instead;
        }
    }

    record InvalidValue(String field, String got, String expected) implements ClusterInitError {
        @Override
        public String message() {
            return "Invalid " + field + " '" + got + "': " + expected;
        }
    }

    /// Standard input ran out while a required question was still unanswered.
    ///
    /// Distinct from [Aborted]: nobody typed `abort`, the input simply ended. Reported rather than
    /// silently completing from defaults — assembling the unanswered half of a cluster config from
    /// fallbacks is the failure this whole change removes.
    enum InputExhausted implements ClusterInitError {
        INSTANCE;
        @Override
        public String message() {
            return "Input ended before every required answer was given. "
                 + "Run `aether cluster init` on a terminal, or use --non-interactive with the "
                 + "required flags (--region, --instance-type, --ssh-public-key, --admin-cidr).";
        }
    }

    enum Aborted implements ClusterInitError {
        INSTANCE;
        @Override
        public String message() {
            return "Wizard aborted by operator.";
        }
    }

    record ConfigValidationFailed(String detail) implements ClusterInitError {
        @Override
        public String message() {
            return "Generated configuration failed validation: " + detail;
        }
    }

    record IoFailure(String path, String reason) implements ClusterInitError {
        @Override
        public String message() {
            return "Failed to write " + path + ": " + reason;
        }
    }
}
