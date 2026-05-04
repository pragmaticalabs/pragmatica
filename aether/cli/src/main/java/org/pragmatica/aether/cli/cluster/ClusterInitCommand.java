// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

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
import org.pragmatica.aether.cli.cluster.init.InputValidators;
import org.pragmatica.aether.cli.cluster.init.IpDetector;
import org.pragmatica.aether.cli.cluster.init.TopologyDeriver;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


@Command(name = "init", description = "Generate a cluster-config.toml interactively or from flags") @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-EX-01"}) class ClusterInitCommand implements Callable<Integer> {
    private static final String DEFAULT_OUTPUT = "cluster-config.toml";

    @Option(names = "--output", description = "Output path", defaultValue = DEFAULT_OUTPUT) private Path output;

    @Option(names = "--force", description = "Overwrite existing output file") private boolean force;

    @Option(names = "--name", description = "Cluster name") private String name;

    @Option(names = "--target", description = "Deployment target: docker | ssh | cloud | forge") private String target;

    @Option(names = "--provider", description = "Cloud provider (cloud target only): hetzner | aws | gcp | azure") private String provider;

    @Option(names = "--region", description = "Cloud region (cloud target only)") private String region;

    @Option(names = "--instance-type", description = "Cloud instance type (cloud target only)") private String instanceType;

    @Option(names = "--credential-env", description = "Env var name carrying provider credentials") private String credentialEnv;

    @Option(names = "--hosts", description = "SSH hosts (ssh target only), comma-separated", split = ",") private List<String> hosts;

    @Option(names = "--ssh-user", description = "SSH user (ssh target only)") private String sshUser;

    @Option(names = "--ssh-key", description = "SSH private key path (ssh target only)") private String sshKey;

    @Option(names = "--ssh-port", description = "SSH port (ssh target only)", defaultValue = "22") private Integer sshPort;

    @Option(names = "--nodes", description = "Total node count (>= 3 required for non-SSH targets)") private Integer nodes;

    @Option(names = "--db-host", description = "Database host (optional)") private String dbHost;

    @Option(names = "--db-port", description = "Database port (optional)", defaultValue = "5432") private Integer dbPort;

    @Option(names = "--db-name", description = "Database name (optional)") private String dbName;

    @Option(names = "--db-user", description = "Database user (optional)") private String dbUser;

    @Option(names = "--db-password-env", description = "Env var name carrying database password") private String dbPasswordEnv;

    @Option(names = "--firewall", description = "Firewall preset: standard | restrictive | open | custom") private String firewall;

    @Option(names = "--admin-cidr", description = "Admin source CIDR (RESTRICTIVE preset)") private String adminCidr;

    @Option(names = "--internal-cidr", description = "Internal cluster CIDR (RESTRICTIVE preset)") private String internalCidr;

    @Option(names = "--tls", description = "TLS mode: auto | env (env requires --tls-cert-env / --tls-key-env)") private String tls;

    @Option(names = "--tls-cert-env", description = "Env var name carrying TLS cert path") private String tlsCertEnv;

    @Option(names = "--tls-key-env", description = "Env var name carrying TLS key path") private String tlsKeyEnv;

    @Option(names = "--secret", description = "Cluster secret mode: auto | env") private String secret;

    @Option(names = "--secret-env", description = "Env var name carrying cluster secret") private String secretEnv;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return collectAnswers().flatMap(this::writeOutput).fold(this::onFailure, this::onSuccess);
    }

    private Result<ClusterConfigAnswers> collectAnswers() {
        return isBatchMode()
              ? buildFromFlags()
              : new ClusterConfigWizard().run();
    }

    private boolean isBatchMode() {
        return target != null;
    }

    private Result<ClusterConfigAnswers> buildFromFlags() {
        return parseTarget(target).flatMap(t -> InputValidators.validateClusterName(name == null
                                                                                    ? ""
                                                                                    : name)
        .flatMap(validName -> buildAnswersForTarget(validName, t)));
    }

    private Result<ClusterConfigAnswers> buildAnswersForTarget(String clusterName, SourceType t) {
        return switch (t){
            case CLOUD -> buildCloudAnswers(clusterName);
            case SSH -> buildSshAnswers(clusterName);
            case DOCKER, FORGE -> buildLocalAnswers(clusterName, t);
        };
    }

    private Result<ClusterConfigAnswers> buildCloudAnswers(String clusterName) {
        if (provider == null) return new ClusterInitError.MissingField("--provider").result();
        if (region == null) return new ClusterInitError.MissingField("--region").result();
        if (instanceType == null) return new ClusterInitError.MissingField("--instance-type").result();
        if (credentialEnv == null) return new ClusterInitError.MissingField("--credential-env").result();
        if (nodes == null) return new ClusterInitError.MissingField("--nodes").result();
        return parseCloudProvider(provider).flatMap(p -> InputValidators.validateEnvVarName(credentialEnv)
                                                                                           .flatMap(envOk -> TopologyDeriver.derive(nodes)
                                                                                                                                   .flatMap(split -> assembleAnswers(clusterName,
                                                                                                                                                                     SourceType.CLOUD,
                                                                                                                                                                     org.pragmatica.lang.Option.some(new CloudAnswers(p,
                                                                                                                                                                                                                      region,
                                                                                                                                                                                                                      instanceType,
                                                                                                                                                                                                                      envOk)),
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
                return Result.<ClusterConfigAnswers>failure(cause);
            }
        }
        return TopologyDeriver.derive(hosts.size())
                                     .flatMap(split -> assembleAnswers(clusterName,
                                                                       SourceType.SSH,
                                                                       org.pragmatica.lang.Option.none(),
                                                                       org.pragmatica.lang.Option.some(new SshAnswers(hosts,
                                                                                                                      sshUser,
                                                                                                                      Path.of(sshKey),
                                                                                                                      sshPort)),
                                                                       split));
    }

    private Result<ClusterConfigAnswers> buildLocalAnswers(String clusterName, SourceType t) {
        if (nodes == null) return new ClusterInitError.MissingField("--nodes").result();
        return TopologyDeriver.derive(nodes)
                                     .flatMap(split -> assembleAnswers(clusterName,
                                                                       t,
                                                                       org.pragmatica.lang.Option.none(),
                                                                       org.pragmatica.lang.Option.none(),
                                                                       split));
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
        return InputValidators.validateHostnameOrIp(dbHost)
                                                   .flatMap(host -> InputValidators.validateEnvVarName(dbPasswordEnv)
                                                                                                      .map(envVar -> org.pragmatica.lang.Option.some(new DatabaseAnswers(host,
                                                                                                                                                                         dbPort,
                                                                                                                                                                         dbName,
                                                                                                                                                                         dbUser,
                                                                                                                                                                         new PasswordSource.FromEnv(envVar)))));
    }

    private record FirewallChoice(FirewallPreset preset,
                                  org.pragmatica.lang.Option<String> adminCidr,
                                  org.pragmatica.lang.Option<String> internalCidr){}

    private Result<FirewallChoice> buildFirewall(SourceType t) {
        if (t == SourceType.DOCKER || t == SourceType.FORGE) {return Result.success(new FirewallChoice(FirewallPreset.OPEN,
                                                                                                       org.pragmatica.lang.Option.none(),
                                                                                                       org.pragmatica.lang.Option.none()));}
        var preset = parseFirewallPreset(firewall == null
                                         ? "standard"
                                         : firewall);
        return preset.flatMap(p -> {
                                  if (p == FirewallPreset.RESTRICTIVE) {
                                      var admin = adminCidr != null
                                                 ? adminCidr
                                                 : IpDetector.suggestAdminCidr();
                                      var internal = internalCidr != null
                                                    ? internalCidr
                                                    : "10.0.0.0/8";
                                      return InputValidators.validateCidr(admin).flatMap(_ -> InputValidators.validateCidr(internal))
                                                                         .map(_ -> new FirewallChoice(p,
                                                                                                      org.pragmatica.lang.Option.some(admin),
                                                                                                      org.pragmatica.lang.Option.some(internal)));
                                  }
                                  return Result.success(new FirewallChoice(p,
                                                                           org.pragmatica.lang.Option.none(),
                                                                           org.pragmatica.lang.Option.none()));
                              });
    }

    private Result<TlsAnswers> buildTls(SourceType t) {
        if (t == SourceType.DOCKER || t == SourceType.FORGE) {return Result.success(new TlsAnswers.Skipped());}
        var mode = tls == null
                  ? "auto"
                  : tls.toLowerCase();
        return switch (mode){
            case "auto" -> Result.success(new TlsAnswers.AutoGenerate());
            case "env" -> {
                if (tlsCertEnv == null) {yield new ClusterInitError.MissingField("--tls-cert-env (--tls=env)").result();}
                if (tlsKeyEnv == null) {yield new ClusterInitError.MissingField("--tls-key-env (--tls=env)").result();}
                yield InputValidators.validateEnvVarName(tlsCertEnv)
                                                        .flatMap(certOk -> InputValidators.validateEnvVarName(tlsKeyEnv)
                                                                                                             .map(keyOk -> new TlsAnswers.Manual(certOk,
                                                                                                                                                 keyOk)));
            }
            default -> new ClusterInitError.InvalidValue("--tls", mode, "expected 'auto' or 'env'").result();
        };
    }

    private Result<SecretAnswers> buildSecret(SourceType t) {
        if (t == SourceType.DOCKER || t == SourceType.FORGE) {return Result.success(new SecretAnswers.Skipped());}
        var mode = secret == null
                  ? "auto"
                  : secret.toLowerCase();
        return switch (mode){
            case "auto" -> Result.success(new SecretAnswers.AutoGenerate());
            case "env" -> {
                if (secretEnv == null) {yield new ClusterInitError.MissingField("--secret-env (--secret=env)").result();}
                yield InputValidators.validateEnvVarName(secretEnv).map(SecretAnswers.FromEnv::new);
            }
            default -> new ClusterInitError.InvalidValue("--secret", mode, "expected 'auto' or 'env'").result();
        };
    }

    private static Result<SourceType> parseTarget(String raw) {
        var match = Arrays.stream(SourceType.values()).filter(t -> t.name().equalsIgnoreCase(raw))
                                 .findFirst();
        return match.map(Result::success)
                        .orElseGet(() -> new ClusterInitError.InvalidValue("--target",
                                                                           raw,
                                                                           "one of: docker, ssh, cloud, forge").result());
    }

    private static Result<CloudProviderName> parseCloudProvider(String raw) {
        var match = Arrays.stream(CloudProviderName.values()).filter(p -> p.name().equalsIgnoreCase(raw))
                                 .findFirst();
        return match.map(Result::success)
                        .orElseGet(() -> new ClusterInitError.InvalidValue("--provider",
                                                                           raw,
                                                                           "one of: hetzner, aws, gcp, azure").result());
    }

    private static Result<FirewallPreset> parseFirewallPreset(String raw) {
        var match = Arrays.stream(FirewallPreset.values()).filter(p -> p.name().equalsIgnoreCase(raw))
                                 .findFirst();
        return match.map(Result::success)
                        .orElseGet(() -> new ClusterInitError.InvalidValue("--firewall",
                                                                           raw,
                                                                           "one of: standard, restrictive, open, custom").result());
    }

    private Result<Path> writeOutput(ClusterConfigAnswers answers) {
        if (Files.exists(output) && !force) {if (!isBatchMode()) {
            var prompt = new Prompt();
            if (!prompt.confirm("Output file " + output + " exists. Overwrite?", false)) {return new ClusterInitError.OutputExists(output.toString()).result();}
        } else {return new ClusterInitError.OutputExists(output.toString()).result();}}
        try {
            var toml = ClusterConfigGenerator.generate(answers);
            Files.writeString(output, toml);
            return Result.success(output);
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
