// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.Prompt;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.CloudAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.DatabaseAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.DatabaseAnswers.PasswordSource;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.SecretAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.SshAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.TlsAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigGenerator;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigWizard;
import org.pragmatica.aether.cli.cluster.init.ClusterInitError;
import org.pragmatica.aether.cli.cluster.init.FirewallPreset;
import org.pragmatica.aether.cli.cluster.init.FirewallPresets;
import org.pragmatica.aether.cli.cluster.init.InputValidators;
import org.pragmatica.aether.cli.cluster.init.CoreWorkerSplit;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


@Command(name = "init", description = "Generate a cluster-config.toml interactively or from flags")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-EX-01"})
class ClusterInitCommand implements Callable<Integer> {
    private static final String DEFAULT_OUTPUT = "cluster-config.toml";

    @Option(names = "--output", description = "Output path", defaultValue = DEFAULT_OUTPUT)
    private Path output;

    @Option(names = "--force", description = "Overwrite existing output file")
    private boolean force;

    /// P-NEW-G (2026-05-21): Forces non-interactive (batch) mode and disables all prompts.
    /// When set, `--target` defaults to `docker` if not provided, and any missing required
    /// flag fails fast with a `MissingField` cause rather than prompting interactively.
    /// Enables CI / integration-test usage (TC-07-J3) — see
    /// `aether/docs/internal/production-readiness-followup-2026-05-21.md` P-NEW-G.
    @Option(names = "--non-interactive", description = "Force non-interactive mode; fail if required flags are missing")
    private boolean nonInteractive;

    @Option(names = "--name", description = "Cluster name")
    private String name;

    @Option(names = "--target", description = "Deployment target: docker | ssh | cloud | forge")
    private String target;

    @Option(names = "--provider", description = "Cloud provider (cloud target only): hetzner | aws | gcp | azure")
    private String provider;

    @Option(names = "--region", description = "Cloud region (cloud target only)")
    private String region;

    @Option(names = "--instance-type", description = "Cloud instance type (cloud target only)")
    private String instanceType;

    @Option(names = "--credential-env", description = "Env var name carrying provider credentials")
    private String credentialEnv;

    @Option(names = "--hosts", description = "SSH hosts (ssh target only), comma-separated", split = ",")
    private List<String> hosts;

    @Option(names = "--ssh-user", description = "SSH user (ssh target only)")
    private String sshUser;

    @Option(names = "--ssh-key", description = "SSH private key path (ssh target only)")
    private String sshKey;

    @Option(names = "--ssh-public-key", description = "SSH public key path (cloud target only) — injected into provisioned VMs "
                                                    + "and written to [infrastructure.ssh] public_key_file. Required for cloud; "
                                                    + "bootstrap refuses a cloud cluster without one.")
    private String sshPublicKey;

    @Option(names = "--ssh-port", description = "SSH port (ssh target only)", defaultValue = "22")
    private Integer sshPort;

    /// #1019 — the config models the two tiers as independent quantities (`[cluster.core]`,
    /// `[source.X.core]`, `[source.X.worker]`); a single `--nodes` collapsed them into a total and the
    /// round trip lost information. They are now stated, never inferred.
    @Option(names = "--core-nodes", description = "Consensus tier size: 5 (minimum), 7 (recommended) or 9 (maximum). Must be odd.")
    private Integer coreNodes;

    @Option(names = "--worker-nodes", description = "Worker tier size (default 0). Not bounded by the consensus-tier maximum. "
                                                  + "For an ssh target this is the remainder of --hosts after --core-nodes and must not be given.")
    private Integer workerNodes;

    @Option(names = "--db-host", description = "Database host (optional)")
    private String dbHost;

    @Option(names = "--db-port", description = "Database port (optional)", defaultValue = "5432")
    private Integer dbPort;

    @Option(names = "--db-name", description = "Database name (optional)")
    private String dbName;

    @Option(names = "--db-user", description = "Database user (optional)")
    private String dbUser;

    @Option(names = "--db-password-env", description = "Env var name carrying database password")
    private String dbPasswordEnv;

    @Option(names = "--firewall", description = "Firewall preset: standard | restrictive | open | custom")
    private String firewall;

    @Option(names = "--admin-cidr", description = "Admin source CIDR — the network allowed to reach bootstrap SSH (22) and "
                                                + "the management API. Required for a cloud target on the STANDARD or "
                                                + "RESTRICTIVE preset; never auto-detected.")
    private String adminCidr;

    @Option(names = "--internal-cidr", description = "Internal cluster CIDR (RESTRICTIVE preset)")
    private String internalCidr;

    @Option(names = "--tls", description = "TLS mode: auto | env (env requires --tls-cert-env / --tls-key-env)")
    private String tls;

    @Option(names = "--tls-cert-env", description = "Env var name carrying TLS cert path")
    private String tlsCertEnv;

    @Option(names = "--tls-key-env", description = "Env var name carrying TLS key path")
    private String tlsKeyEnv;

    @Option(names = "--secret", description = "Cluster secret mode: auto | env")
    private String secret;

    @Option(names = "--secret-env", description = "Env var name carrying cluster secret")
    private String secretEnv;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Override
    public Integer call() {
        return collectAnswers().flatMap(this::writeOutput)
                             .fold(this::onFailure, this::onSuccess);
    }

    private Result<ClusterConfigAnswers> collectAnswers() {
        return isBatchMode()
               ? buildFromFlags()
               : new ClusterConfigWizard().run();
    }

    private boolean isBatchMode() {
        return target != null || nonInteractive;
    }

    private Result<ClusterConfigAnswers> buildFromFlags() {
        var effectiveTarget = target != null
                              ? target
                              : "docker";

        return parseTarget(effectiveTarget).flatMap(t -> InputValidators.validateClusterName(name == null
                                                                                             ? ""
                                                                                             : name).flatMap(validName -> buildAnswersForTarget(validName,
                                                                                                                                                t)));
    }

    private Result<ClusterConfigAnswers> buildAnswersForTarget(String clusterName, SourceType t) {
        return switch (t) {
            case CLOUD -> buildCloudAnswers(clusterName);
            case SSH -> buildSshAnswers(clusterName);
            case DOCKER, FORGE -> buildLocalAnswers(clusterName, t);
        };
    }

    private Result<ClusterConfigAnswers> buildCloudAnswers(String clusterName) {
        if (provider == null) return new ClusterInitError.MissingField("--provider").result();

        if (!Verify.Is.present(region)) return new ClusterInitError.RegionRequired(provider).result();

        if (!Verify.Is.present(instanceType)) return new ClusterInitError.InstanceTypeRequired(provider).result();

        if (credentialEnv == null) return new ClusterInitError.MissingField("--credential-env").result();

        if (!Verify.Is.present(sshPublicKey)) return new ClusterInitError.SshPublicKeyRequired().result();
        // A flag that cannot affect a cloud target is refused, not silently swallowed: --ssh-key is
        // the PRIVATE key for reaching existing hosts and was accepted-and-ignored here.
        if (Verify.Is.present(sshKey)) {
            return new ClusterInitError.FlagNotApplicable("--ssh-key",
                                                          "cloud",
                                                          "cloud VMs are provisioned with a PUBLIC key — use --ssh-public-key").result();
        }

        return parseCloudProvider(provider).flatMap(p -> InputValidators.validateEnvVarName(credentialEnv).flatMap(envOk -> requestedSplit().flatMap(split -> assembleAnswers(clusterName,
                                                                                                                                                                              SourceType.CLOUD,
                                                                                                                                                                              org.pragmatica.lang.Option.some(new CloudAnswers(p,
                                                                                                                                                                                                                               region,
                                                                                                                                                                                                                               instanceType,
                                                                                                                                                                                                                               envOk,
                                                                                                                                                                                                                               sshPublicKey.trim())),
                                                                                                                                                                              org.pragmatica.lang.Option.none(),
                                                                                                                                                                              split))));
    }

    private Result<ClusterConfigAnswers> buildSshAnswers(String clusterName) {
        if (hosts == null || hosts.isEmpty()) return new ClusterInitError.MissingField("--hosts").result();

        if (sshUser == null) return new ClusterInitError.MissingField("--ssh-user").result();

        if (sshKey == null) return new ClusterInitError.MissingField("--ssh-key").result();

        for (var host : hosts) {
            var validation = InputValidators.validateHostnameOrIp(host);

            if (validation.isFailure()) {
                var cause = validation.fold(c -> c, _ -> null);

                return Result.<ClusterConfigAnswers> failure(cause);
            }
        }

        return sshSplit().flatMap(split -> assembleAnswers(clusterName,
                                                           SourceType.SSH,
                                                           org.pragmatica.lang.Option.none(),
                                                           org.pragmatica.lang.Option.some(new SshAnswers(hosts,
                                                                                                          sshUser,
                                                                                                          Path.of(sshKey),
                                                                                                          sshPort)),
                                                           split));
    }

    private Result<ClusterConfigAnswers> buildLocalAnswers(String clusterName, SourceType t) {
        return requestedSplit().flatMap(split -> assembleAnswers(clusterName,
                                                                 t,
                                                                 org.pragmatica.lang.Option.none(),
                                                                 org.pragmatica.lang.Option.none(),
                                                                 split));
    }

    /// Both tiers as given. An absent `--worker-nodes` means zero workers — the honest default for an
    /// unstated tier, and the reason nothing needs to derive a split any more.
    private Result<CoreWorkerSplit> requestedSplit() {
        if (coreNodes == null) {
            return new ClusterInitError.MissingField("--core-nodes").result();
        }

        return CoreWorkerSplit.coreWorkerSplit(coreNodes,
                                               workerNodes == null
                                               ? 0
                                               : workerNodes);
    }

    /// An ssh target's fleet size is the host list, so the worker tier is its remainder rather than a
    /// separate answer. `--worker-nodes` is refused rather than silently ignored — the same treatment
    /// `--ssh-key` gets on a cloud target — because accepting a value that cannot take effect only
    /// looks like it worked.
    private Result<CoreWorkerSplit> sshSplit() {
        if (coreNodes == null) {
            return new ClusterInitError.MissingField("--core-nodes").result();
        }

        if (workerNodes != null) {
            return new ClusterInitError.FlagNotApplicable("--worker-nodes",
                                                          "ssh",
                                                          "the worker tier is whatever --hosts holds beyond --core-nodes").result();
        }

        if (hosts.size() < coreNodes) {
            return new ClusterInitError.InvalidTopology("--core-nodes " + coreNodes
                                                       + " exceeds the " + hosts.size()
                                                       + " host(s) given in --hosts").result();
        }

        return CoreWorkerSplit.coreWorkerSplit(coreNodes, hosts.size() - coreNodes);
    }

    private Result<ClusterConfigAnswers> assembleAnswers(String clusterName,
                                                         SourceType t,
                                                         org.pragmatica.lang.Option<CloudAnswers> cloud,
                                                         org.pragmatica.lang.Option<SshAnswers> ssh,
                                                         org.pragmatica.aether.cli.cluster.init.CoreWorkerSplit split) {
        return buildDatabase().flatMap(db -> buildFirewall(t).flatMap(fw -> buildTls(t).flatMap(tlsAns -> buildSecret(t).map(secretAns -> new ClusterConfigAnswers(clusterName,
                                                                                                                                                                   "1.0.0",
                                                                                                                                                                   t,
                                                                                                                                                                   cloud,
                                                                                                                                                                   ssh,
                                                                                                                                                                   split,
                                                                                                                                                                   db,
                                                                                                                                                                   fw.preset(),
                                                                                                                                                                   fw.adminCidr(),
                                                                                                                                                                   fw.internalCidr(),
                                                                                                                                                                   List.of(),
                                                                                                                                                                   tlsAns,
                                                                                                                                                                   secretAns)))));
    }

    private Result<org.pragmatica.lang.Option<DatabaseAnswers>> buildDatabase() {
        if (dbHost == null) return Result.success(org.pragmatica.lang.Option.none());

        if (dbName == null) return new ClusterInitError.MissingField("--db-name (--db-host given)").result();

        if (dbUser == null) return new ClusterInitError.MissingField("--db-user (--db-host given)").result();

        if (dbPasswordEnv == null) return new ClusterInitError.MissingField("--db-password-env (--db-host given)").result();

        return InputValidators.validateHostnameOrIp(dbHost).flatMap(host -> InputValidators.validateEnvVarName(dbPasswordEnv).map(envVar -> org.pragmatica.lang.Option.some(new DatabaseAnswers(host,
                                                                                                                                                                                                dbPort,
                                                                                                                                                                                                dbName,
                                                                                                                                                                                                dbUser,
                                                                                                                                                                                                new PasswordSource.FromEnv(envVar)))));
    }

    private record FirewallChoice(FirewallPreset preset,
                                  org.pragmatica.lang.Option<String> adminCidr,
                                  org.pragmatica.lang.Option<String> internalCidr) {}

    private Result<FirewallChoice> buildFirewall(SourceType t) {
        if (t == SourceType.DOCKER || t == SourceType.FORGE) {
            return Result.success(new FirewallChoice(FirewallPreset.OPEN,
                                                     org.pragmatica.lang.Option.none(),
                                                     org.pragmatica.lang.Option.none()));
        }

        return parseFirewallPreset(firewall == null
                                   ? "standard"
                                   : firewall).flatMap(p -> firewallChoiceFor(p, t));
    }

    /// STANDARD and RESTRICTIVE both route through `FirewallPresets.addAdminScoped`, so both need
    /// the operator CIDR; OPEN and CUSTOM emit no admin-scoped rules and need none. Only
    /// RESTRICTIVE used to receive it — see [ClusterInitError.AdminCidrRequired] for what that cost.
    private Result<FirewallChoice> firewallChoiceFor(FirewallPreset preset, SourceType t) {
        return switch (preset) {
            case OPEN, CUSTOM -> Result.success(new FirewallChoice(preset,
                                                                   org.pragmatica.lang.Option.none(),
                                                                   org.pragmatica.lang.Option.none()));
            case STANDARD, RESTRICTIVE -> adminScopedChoice(preset, t);
        };
    }

    private Result<FirewallChoice> adminScopedChoice(FirewallPreset preset, SourceType t) {
        return Verify.Is.present(adminCidr)
               ? validatedAdminScopedChoice(preset)
               : missingAdminCidr(preset, t);
    }

    /// A CLOUD target refuses, because a provider firewall really is applied and bootstrap really
    /// does fail against it. Other targets are still PERMITTED to omit the CIDR — there is no
    /// provider firewall to misconfigure — so they keep generating rather than newly failing.
    ///
    /// Their OUTPUT does change on one preset, and an earlier version of this comment wrongly denied
    /// it: a non-cloud `--firewall restrictive` with no `--admin-cidr` used to fall back to
    /// `IpDetector.suggestAdminCidr()` and emit admin-scoped rules from the detected address; it now
    /// emits none. The direction is FAIL-CLOSED — `FirewallPresets.addAdminScoped` omits those rules
    /// rather than widening them to `0.0.0.0/0` — and removing the auto-detection is precisely the
    /// point of [ClusterInitError.AdminCidrRequired], so the change is intended. Only the denial was
    /// wrong.
    private Result<FirewallChoice> missingAdminCidr(FirewallPreset preset, SourceType t) {
        return t == SourceType.CLOUD
               ? new ClusterInitError.AdminCidrRequired(preset.name()).result()
               : Result.success(new FirewallChoice(preset,
                                                   org.pragmatica.lang.Option.none(),
                                                   org.pragmatica.lang.Option.none()));
    }

    /// The internal CIDR is only consulted by `restrictiveRules`; STANDARD carries it harmlessly so
    /// both arms validate the same pair rather than branching twice.
    private Result<FirewallChoice> validatedAdminScopedChoice(FirewallPreset preset) {
        var internal = Verify.Is.present(internalCidr)
                       ? internalCidr
                       : FirewallPresets.DEFAULT_INTERNAL_CIDR;

        return InputValidators.validateCidr(adminCidr).flatMap(admin -> InputValidators.validateCidr(internal).map(ok -> new FirewallChoice(preset,
                                                                                                                                            org.pragmatica.lang.Option.some(admin),
                                                                                                                                            org.pragmatica.lang.Option.some(ok))));
    }

    private Result<TlsAnswers> buildTls(SourceType t) {
        if (t == SourceType.DOCKER || t == SourceType.FORGE) {
            return Result.success(new TlsAnswers.Skipped());
        }

        var mode = tls == null
                   ? "auto"
                   : tls.toLowerCase();

        return switch (mode) {
            case "auto" -> Result.success(new TlsAnswers.AutoGenerate());
            case "env" -> buildTlsFromEnv();
            default -> new ClusterInitError.InvalidValue("--tls", mode, "expected 'auto' or 'env'").result();
        };
    }

    private Result<TlsAnswers> buildTlsFromEnv() {
        if (tlsCertEnv == null) {
            return new ClusterInitError.MissingField("--tls-cert-env (--tls=env)").result();
        }

        if (tlsKeyEnv == null) {
            return new ClusterInitError.MissingField("--tls-key-env (--tls=env)").result();
        }

        return InputValidators.validateEnvVarName(tlsCertEnv).flatMap(certOk -> InputValidators.validateEnvVarName(tlsKeyEnv).map(keyOk -> new TlsAnswers.Manual(certOk,
                                                                                                                                                                 keyOk)));
    }

    private Result<SecretAnswers> buildSecret(SourceType t) {
        if (t == SourceType.DOCKER || t == SourceType.FORGE) {
            return Result.success(new SecretAnswers.Skipped());
        }

        var mode = secret == null
                   ? "auto"
                   : secret.toLowerCase();

        return switch (mode) {
            case "auto" -> Result.success(new SecretAnswers.AutoGenerate());
            case "env" -> buildSecretFromEnv();
            default -> new ClusterInitError.InvalidValue("--secret", mode, "expected 'auto' or 'env'").result();
        };
    }

    private Result<SecretAnswers> buildSecretFromEnv() {
        if (secretEnv == null) {
            return new ClusterInitError.MissingField("--secret-env (--secret=env)").result();
        }

        return InputValidators.validateEnvVarName(secretEnv).map(SecretAnswers.FromEnv::new);
    }

    private static Result<SourceType> parseTarget(String raw) {
        var match = Arrays.stream(SourceType.values()).filter(t -> t.name()
                                                                    .equalsIgnoreCase(raw)).findFirst();

        return match.map(Result::success)
                    .orElseGet(() -> new ClusterInitError.InvalidValue("--target",
                                                                       raw,
                                                                       "one of: docker, ssh, cloud, forge").result());
    }

    private static Result<CloudProviderName> parseCloudProvider(String raw) {
        var match = Arrays.stream(CloudProviderName.values()).filter(p -> p.name()
                                                                           .equalsIgnoreCase(raw)).findFirst();

        return match.map(Result::success)
                    .orElseGet(() -> new ClusterInitError.InvalidValue("--provider",
                                                                       raw,
                                                                       "one of: hetzner, aws, gcp, azure").result());
    }

    private static Result<FirewallPreset> parseFirewallPreset(String raw) {
        var match = Arrays.stream(FirewallPreset.values()).filter(p -> p.name()
                                                                        .equalsIgnoreCase(raw)).findFirst();

        return match.map(Result::success)
                    .orElseGet(() -> new ClusterInitError.InvalidValue("--firewall",
                                                                       raw,
                                                                       "one of: standard, restrictive, open, custom").result());
    }

    private Result<Path> writeOutput(ClusterConfigAnswers answers) {
        if (Files.exists(output) && !force) {
            if (!isBatchMode()) {
                var prompt = new Prompt();

                if (!prompt.confirm("Output file " + output + " exists. Overwrite?", false)) {
                    return new ClusterInitError.OutputExists(output.toString()).result();
                }
            } else {
                return new ClusterInitError.OutputExists(output.toString()).result();
            }
        }

        try {
            var toml = ClusterConfigGenerator.generate(answers);
            // #287: aether.toml carries cluster_secret — write it owner-only (0600), not 0644.
            Files.writeString(output, toml);

            return SecureFiles.restrictToOwner(output)
                              .map(_ -> output)
                              .mapError(cause -> new ClusterInitError.IoFailure(output.toString(),
                                                                                cause.message()));
        } catch (IOException e) {
            return new ClusterInitError.IoFailure(output.toString(), e.getMessage()).result();
        }
    }

    private int onSuccess(Path written) {
        System.out.println("Wrote " + written);
        System.out.println("Next: review the file, then run `aether cluster bootstrap " + written + "`.");

        return ExitCode.SUCCESS;
    }

    private int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}
