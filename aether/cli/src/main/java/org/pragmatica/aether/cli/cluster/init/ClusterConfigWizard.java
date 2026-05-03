// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.aether.cli.Prompt;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.CloudAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.DatabaseAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.DatabaseAnswers.PasswordSource;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.SecretAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.SshAnswers;
import org.pragmatica.aether.cli.cluster.init.ClusterConfigAnswers.TlsAnswers;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/// Interactive driver for the `aether cluster init` wizard. Walks the operator
/// through the step sequence defined in the cluster-init-wizard spec §2.1 and
/// produces a [ClusterConfigAnswers] record that downstream code feeds to
/// [ClusterConfigGenerator].
///
/// Steps are conditionally pruned based on deployment target (Docker/Forge skip
/// firewall + TLS + cloud + ssh; SSH skips cloud; Cloud skips ssh).
///
/// At any prompt the operator may type:
/// - `back` to revert to the previous applicable step
/// - `abort` to terminate the wizard with [ClusterInitError.Aborted]
///
/// Typing `back` at the very first step (cluster name) aborts as well — there
/// is nothing to go back to.
public class ClusterConfigWizard {

    private static final String DEFAULT_CLUSTER_VERSION = "1.0.0";
    private static final String BACK_KEYWORD = "back";
    private static final String ABORT_KEYWORD = "abort";

    private final Prompt prompt;

    public ClusterConfigWizard() {
        this(new Prompt());
    }

    public ClusterConfigWizard(Prompt prompt) {
        this.prompt = prompt;
    }

    /// Run the wizard. Returns collected answers on success, or
    /// [ClusterInitError.Aborted] if the operator aborted at any point.
    public Result<ClusterConfigAnswers> run() {
        var steps = steps();
        var history = new ArrayDeque<ClusterConfigAnswers>();
        var state = blankState();
        var index = 0;

        while (index < steps.size()) {
            var step = steps.get(index);
            if (!step.applies().test(state)) {
                index++;
                continue;
            }
            var result = step.def().run(state, prompt);
            switch (result) {
                case StepResult.Continue cont -> {
                    history.push(state);
                    state = cont.answers();
                    index++;
                }
                case StepResult.Back _ -> {
                    var previousIndex = previousApplicableIndex(steps, index, history);
                    if (previousIndex < 0) {
                        return new ClusterInitError.Aborted().result();
                    }
                    state = history.pop();
                    index = previousIndex;
                }
                case StepResult.Abort _ -> {
                    return new ClusterInitError.Aborted().result();
                }
            }
        }
        return Result.success(state);
    }

    private static int previousApplicableIndex(List<NamedStep> steps, int currentIndex, Deque<ClusterConfigAnswers> history) {
        if (history.isEmpty()) {
            return -1;
        }
        var previousState = history.peek();
        for (int i = currentIndex - 1; i >= 0; i--) {
            if (steps.get(i).applies().test(previousState)) {
                return i;
            }
        }
        return -1;
    }

    private static ClusterConfigAnswers blankState() {
        return new ClusterConfigAnswers("",
                                         DEFAULT_CLUSTER_VERSION,
                                         SourceType.DOCKER,
                                         Option.none(),
                                         Option.none(),
                                         new CoreWorkerSplit(3, 0),
                                         Option.none(),
                                         FirewallPreset.STANDARD,
                                         Option.none(),
                                         Option.none(),
                                         List.of(),
                                         new TlsAnswers.Skipped(),
                                         new SecretAnswers.Skipped());
    }

    private List<NamedStep> steps() {
        return List.of(new NamedStep("Cluster name", _ -> true, ClusterConfigWizard::stepClusterName),
                        new NamedStep("Deployment target", _ -> true, ClusterConfigWizard::stepDeploymentTarget),
                        new NamedStep("Cloud provider", isTarget(SourceType.CLOUD), ClusterConfigWizard::stepCloud),
                        new NamedStep("SSH hosts", isTarget(SourceType.SSH), ClusterConfigWizard::stepSsh),
                        new NamedStep("Topology", _ -> true, ClusterConfigWizard::stepTopology),
                        new NamedStep("Database", _ -> true, ClusterConfigWizard::stepDatabase),
                        new NamedStep("Firewall", needsFirewall(), ClusterConfigWizard::stepFirewall),
                        new NamedStep("TLS and cluster secret", needsSecurity(), ClusterConfigWizard::stepSecurity),
                        new NamedStep("Review", _ -> true, ClusterConfigWizard::stepReview));
    }

    private static Predicate<ClusterConfigAnswers> isTarget(SourceType type) {
        return state -> state.target() == type;
    }

    private static Predicate<ClusterConfigAnswers> needsFirewall() {
        return state -> state.target() == SourceType.CLOUD || state.target() == SourceType.SSH;
    }

    private static Predicate<ClusterConfigAnswers> needsSecurity() {
        return state -> state.target() == SourceType.CLOUD || state.target() == SourceType.SSH;
    }

    // ---------------------------------------------------------------------------
    // Step 1: Cluster name

    private static StepResult stepClusterName(ClusterConfigAnswers state, Prompt prompt) {
        return guardedPrompt(prompt, "Cluster name", state.clusterName(),
                              raw -> validatedContinue(raw, InputValidators::validateClusterName,
                                                        name -> stepClusterName(state, prompt),
                                                        name -> new StepResult.Continue(stateWithClusterName(state, name))));
    }

    private static ClusterConfigAnswers stateWithClusterName(ClusterConfigAnswers state, String name) {
        return new ClusterConfigAnswers(name,
                                         state.clusterVersion(),
                                         state.target(),
                                         state.cloud(),
                                         state.ssh(),
                                         state.topology(),
                                         state.database(),
                                         state.firewallPreset(),
                                         state.adminCidr(),
                                         state.internalCidr(),
                                         state.customFirewallRules(),
                                         state.tls(),
                                         state.secret());
    }

    // ---------------------------------------------------------------------------
    // Step 2: Deployment target

    private static StepResult stepDeploymentTarget(ClusterConfigAnswers state, Prompt prompt) {
        var target = prompt.choice("Deployment target", Arrays.asList(SourceType.values()), state.target());
        return new StepResult.Continue(stateWithTarget(state, target));
    }

    private static ClusterConfigAnswers stateWithTarget(ClusterConfigAnswers state, SourceType target) {
        var cloud = target == SourceType.CLOUD ? state.cloud() : Option.<CloudAnswers>none();
        var ssh = target == SourceType.SSH ? state.ssh() : Option.<SshAnswers>none();
        var firewallApplies = target == SourceType.CLOUD || target == SourceType.SSH;
        var firewallPreset = firewallApplies ? state.firewallPreset() : FirewallPreset.STANDARD;
        var adminCidr = firewallApplies ? state.adminCidr() : Option.<String>none();
        var internalCidr = firewallApplies ? state.internalCidr() : Option.<String>none();
        var customRules = firewallApplies ? state.customFirewallRules() : List.<FirewallRule>of();
        var tls = firewallApplies ? state.tls() : (TlsAnswers) new TlsAnswers.Skipped();
        var secret = firewallApplies ? state.secret() : (SecretAnswers) new SecretAnswers.Skipped();
        return new ClusterConfigAnswers(state.clusterName(),
                                         state.clusterVersion(),
                                         target,
                                         cloud,
                                         ssh,
                                         state.topology(),
                                         state.database(),
                                         firewallPreset,
                                         adminCidr,
                                         internalCidr,
                                         customRules,
                                         tls,
                                         secret);
    }

    // ---------------------------------------------------------------------------
    // Step 3: Cloud

    private static StepResult stepCloud(ClusterConfigAnswers state, Prompt prompt) {
        var existing = state.cloud().or((CloudAnswers) null);
        var defaultProvider = existing == null ? CloudProviderName.HETZNER : existing.provider();
        var provider = prompt.choice("Cloud provider", Arrays.asList(CloudProviderName.values()), defaultProvider);
        var defaultRegion = existing != null && existing.provider() == provider ? existing.region() : defaultRegionFor(provider);
        return guardedPrompt(prompt, "Region", defaultRegion,
                              regionRaw -> validatedContinue(regionRaw, ClusterConfigWizard::nonEmpty,
                                                              _ -> stepCloud(state, prompt),
                                                              region -> cloudInstancePrompt(state, provider, region, existing, prompt)));
    }

    private static StepResult cloudInstancePrompt(ClusterConfigAnswers state, CloudProviderName provider, String region, CloudAnswers existing, Prompt prompt) {
        var defaultInstance = existing != null && existing.provider() == provider ? existing.instanceType() : defaultInstanceFor(provider);
        return guardedPrompt(prompt, "Instance type", defaultInstance,
                              instanceRaw -> validatedContinue(instanceRaw, ClusterConfigWizard::nonEmpty,
                                                                _ -> cloudInstancePrompt(state, provider, region, existing, prompt),
                                                                instance -> cloudCredentialPrompt(state, provider, region, instance, existing, prompt)));
    }

    private static StepResult cloudCredentialPrompt(ClusterConfigAnswers state, CloudProviderName provider, String region, String instance, CloudAnswers existing, Prompt prompt) {
        var defaultEnvVar = existing != null && existing.provider() == provider ? existing.credentialEnvVar() : defaultCredentialEnvVarFor(provider);
        return guardedPrompt(prompt, "Credential env var name", defaultEnvVar,
                              envRaw -> validatedContinue(envRaw, InputValidators::validateEnvVarName,
                                                           _ -> cloudCredentialPrompt(state, provider, region, instance, existing, prompt),
                                                           envVar -> new StepResult.Continue(stateWithCloud(state, new CloudAnswers(provider, region, instance, envVar)))));
    }

    private static ClusterConfigAnswers stateWithCloud(ClusterConfigAnswers state, CloudAnswers cloud) {
        return new ClusterConfigAnswers(state.clusterName(),
                                         state.clusterVersion(),
                                         state.target(),
                                         Option.some(cloud),
                                         state.ssh(),
                                         state.topology(),
                                         state.database(),
                                         state.firewallPreset(),
                                         state.adminCidr(),
                                         state.internalCidr(),
                                         state.customFirewallRules(),
                                         state.tls(),
                                         state.secret());
    }

    private static String defaultRegionFor(CloudProviderName provider) {
        return switch (provider) {
            case HETZNER -> "hel1";
            case AWS -> "us-east-1";
            case GCP -> "us-central1";
            case AZURE -> "eastus";
        };
    }

    private static String defaultInstanceFor(CloudProviderName provider) {
        return switch (provider) {
            case HETZNER -> "cx21";
            case AWS -> "t3.medium";
            case GCP -> "e2-medium";
            case AZURE -> "Standard_B2s";
        };
    }

    private static String defaultCredentialEnvVarFor(CloudProviderName provider) {
        return switch (provider) {
            case HETZNER -> "HCLOUD_TOKEN";
            case AWS -> "AWS_ACCESS_KEY_ID";
            case GCP -> "GOOGLE_APPLICATION_CREDENTIALS";
            case AZURE -> "AZURE_CLIENT_ID";
        };
    }

    // ---------------------------------------------------------------------------
    // Step 4: SSH

    private static StepResult stepSsh(ClusterConfigAnswers state, Prompt prompt) {
        var existing = state.ssh().or((SshAnswers) null);
        var defaultHosts = existing == null ? "" : String.join(",", existing.hosts());
        return guardedPrompt(prompt, "Hosts (comma-separated)", defaultHosts,
                              hostsRaw -> sshHostsContinue(state, hostsRaw, existing, prompt));
    }

    private static StepResult sshHostsContinue(ClusterConfigAnswers state, String hostsRaw, SshAnswers existing, Prompt prompt) {
        return parseAndValidateHosts(hostsRaw)
                .fold(cause -> reportAndRetryStep(cause, () -> stepSsh(state, prompt)),
                       hosts -> sshUserPrompt(state, hosts, existing, prompt));
    }

    private static StepResult reportAndRetryStep(Cause cause, Supplier<StepResult> retry) {
        System.out.println("  ✗ " + cause.message());
        return retry.get();
    }

    private static StepResult sshUserPrompt(ClusterConfigAnswers state, List<String> hosts, SshAnswers existing, Prompt prompt) {
        var defaultUser = existing == null ? "aether" : existing.user();
        return guardedPrompt(prompt, "SSH user", defaultUser,
                              userRaw -> validatedContinue(userRaw, ClusterConfigWizard::nonEmpty,
                                                            _ -> sshUserPrompt(state, hosts, existing, prompt),
                                                            user -> sshKeyPrompt(state, hosts, user, existing, prompt)));
    }

    private static StepResult sshKeyPrompt(ClusterConfigAnswers state, List<String> hosts, String user, SshAnswers existing, Prompt prompt) {
        var defaultKey = existing == null ? "~/.ssh/id_ed25519" : existing.keyPath().toString();
        return guardedPrompt(prompt, "SSH private key path", defaultKey,
                              keyRaw -> sshPortPrompt(state, hosts, user, expandHome(keyRaw), existing, prompt));
    }

    private static StepResult sshPortPrompt(ClusterConfigAnswers state, List<String> hosts, String user, Path keyPath, SshAnswers existing, Prompt prompt) {
        var defaultPort = existing == null ? "22" : String.valueOf(existing.port());
        return guardedPrompt(prompt, "SSH port", defaultPort,
                              portRaw -> validatedContinue(portRaw, InputValidators::validatePort,
                                                            _ -> sshPortPrompt(state, hosts, user, keyPath, existing, prompt),
                                                            port -> new StepResult.Continue(stateWithSsh(state, new SshAnswers(hosts, user, keyPath, port)))));
    }

    private static ClusterConfigAnswers stateWithSsh(ClusterConfigAnswers state, SshAnswers ssh) {
        return new ClusterConfigAnswers(state.clusterName(),
                                         state.clusterVersion(),
                                         state.target(),
                                         state.cloud(),
                                         Option.some(ssh),
                                         state.topology(),
                                         state.database(),
                                         state.firewallPreset(),
                                         state.adminCidr(),
                                         state.internalCidr(),
                                         state.customFirewallRules(),
                                         state.tls(),
                                         state.secret());
    }

    private static Result<List<String>> parseAndValidateHosts(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ClusterInitError.InvalidValue("hosts", "", "must not be empty").result();
        }
        var parts = raw.split(",");
        var validated = new ArrayList<String>(parts.length);
        for (var part : parts) {
            var trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var validation = InputValidators.validateHostnameOrIp(trimmed);
            if (!validation.isSuccess()) {
                return validation.fold(cause -> ((ClusterInitError) cause).result(), _ -> Result.success(List.of()));
            }
            validation.onSuccess(validated::add);
        }
        if (validated.isEmpty()) {
            return new ClusterInitError.InvalidValue("hosts", raw, "must contain at least one host").result();
        }
        return Result.success(List.copyOf(validated));
    }

    private static Path expandHome(String raw) {
        if (raw.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), raw.substring(2));
        }
        if ("~".equals(raw)) {
            return Path.of(System.getProperty("user.home"));
        }
        return Path.of(raw);
    }

    // ---------------------------------------------------------------------------
    // Step 5: Topology

    private static StepResult stepTopology(ClusterConfigAnswers state, Prompt prompt) {
        if (state.target() == SourceType.SSH) {
            return sshDerivedTopology(state, prompt);
        }
        return promptedTopology(state, prompt);
    }

    private static StepResult sshDerivedTopology(ClusterConfigAnswers state, Prompt prompt) {
        return state.ssh()
                     .map(ssh -> deriveOrFail(state, ssh.hosts().size(), prompt, true))
                     .or(ClusterConfigWizard::sshHostsMissing);
    }

    private static StepResult sshHostsMissing() {
        System.out.println("  ✗ SSH hosts not yet collected");
        return new StepResult.Back();
    }

    private static StepResult promptedTopology(ClusterConfigAnswers state, Prompt prompt) {
        var defaultCount = state.topology().total() >= 3 ? String.valueOf(state.topology().total()) : "3";
        return guardedPrompt(prompt, "Total node count", defaultCount,
                              raw -> parseNodeCount(raw, prompt, state));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static StepResult parseNodeCount(String raw, Prompt prompt, ClusterConfigAnswers state) {
        int count;
        try {
            count = Integer.parseInt(raw.trim());
        } catch (NumberFormatException _) {
            System.out.println("  ✗ expected integer >= 3");
            return promptedTopology(state, prompt);
        }
        return deriveOrFail(state, count, prompt, false);
    }

    private static StepResult deriveOrFail(ClusterConfigAnswers state, int count, Prompt prompt, boolean fromSsh) {
        var derived = TopologyDeriver.derive(count);
        return derived.fold(cause -> reportTopologyFailure(state, prompt, cause, fromSsh),
                              split -> announceTopology(state, split));
    }

    private static StepResult reportTopologyFailure(ClusterConfigAnswers state, Prompt prompt, Cause cause, boolean fromSsh) {
        System.out.println("  ✗ " + cause.message());
        if (fromSsh) {
            return new StepResult.Back();
        }
        return promptedTopology(state, prompt);
    }

    private static StepResult announceTopology(ClusterConfigAnswers state, CoreWorkerSplit split) {
        System.out.println("  → derived topology: " + split.core() + " core + " + split.worker() + " worker");
        return new StepResult.Continue(new ClusterConfigAnswers(state.clusterName(),
                                                                  state.clusterVersion(),
                                                                  state.target(),
                                                                  state.cloud(),
                                                                  state.ssh(),
                                                                  split,
                                                                  state.database(),
                                                                  state.firewallPreset(),
                                                                  state.adminCidr(),
                                                                  state.internalCidr(),
                                                                  state.customFirewallRules(),
                                                                  state.tls(),
                                                                  state.secret()));
    }

    // ---------------------------------------------------------------------------
    // Step 6: Database

    private static StepResult stepDatabase(ClusterConfigAnswers state, Prompt prompt) {
        var configure = prompt.confirm("Configure database?", state.database().isPresent());
        if (!configure) {
            return new StepResult.Continue(stateWithDatabase(state, Option.none()));
        }
        var existing = state.database().or((DatabaseAnswers) null);
        var defaultHost = existing == null ? "localhost" : existing.host();
        return guardedPrompt(prompt, "Database host", defaultHost,
                              hostRaw -> validatedContinue(hostRaw, InputValidators::validateHostnameOrIp,
                                                            _ -> stepDatabase(state, prompt),
                                                            host -> dbPortPrompt(state, host, existing, prompt)));
    }

    private static StepResult dbPortPrompt(ClusterConfigAnswers state, String host, DatabaseAnswers existing, Prompt prompt) {
        var defaultPort = existing == null ? "5432" : String.valueOf(existing.port());
        return guardedPrompt(prompt, "Database port", defaultPort,
                              portRaw -> validatedContinue(portRaw, InputValidators::validatePort,
                                                            _ -> dbPortPrompt(state, host, existing, prompt),
                                                            port -> dbNamePrompt(state, host, port, existing, prompt)));
    }

    private static StepResult dbNamePrompt(ClusterConfigAnswers state, String host, int port, DatabaseAnswers existing, Prompt prompt) {
        var defaultName = existing == null ? "aether_app" : existing.name();
        return guardedPrompt(prompt, "Database name", defaultName,
                              nameRaw -> validatedContinue(nameRaw, ClusterConfigWizard::nonEmpty,
                                                            _ -> dbNamePrompt(state, host, port, existing, prompt),
                                                            name -> dbUserPrompt(state, host, port, name, existing, prompt)));
    }

    private static StepResult dbUserPrompt(ClusterConfigAnswers state, String host, int port, String name, DatabaseAnswers existing, Prompt prompt) {
        var defaultUser = existing == null ? "aether" : existing.user();
        return guardedPrompt(prompt, "Database user", defaultUser,
                              userRaw -> validatedContinue(userRaw, ClusterConfigWizard::nonEmpty,
                                                            _ -> dbUserPrompt(state, host, port, name, existing, prompt),
                                                            user -> dbPasswordPrompt(state, host, port, name, user, existing, prompt)));
    }

    private static StepResult dbPasswordPrompt(ClusterConfigAnswers state, String host, int port, String name, String user, DatabaseAnswers existing, Prompt prompt) {
        var defaultEnv = defaultPasswordIsEnv(existing);
        var useEnv = prompt.confirm("Use environment variable for password?", defaultEnv);
        if (useEnv) {
            var defaultEnvVar = existing == null ? "DB_PASSWORD" : extractEnvVar(existing.password()).or("DB_PASSWORD");
            return guardedPrompt(prompt, "Password env var name", defaultEnvVar,
                                  envRaw -> validatedContinue(envRaw, InputValidators::validateEnvVarName,
                                                               _ -> dbPasswordPrompt(state, host, port, name, user, existing, prompt),
                                                               envVar -> new StepResult.Continue(stateWithDatabase(state,
                                                                                                                    Option.some(new DatabaseAnswers(host, port, name, user,
                                                                                                                                                      new PasswordSource.FromEnv(envVar)))))));
        }
        System.out.println("  ⚠  WARNING: plaintext passwords are stored in the generated TOML file.");
        System.out.println("              For production, prefer the environment-variable option.");
        var defaultPlain = existing == null ? "" : extractPlaintext(existing.password()).or("");
        return guardedPrompt(prompt, "Password (plaintext)", defaultPlain,
                              passRaw -> new StepResult.Continue(stateWithDatabase(state,
                                                                                     Option.some(new DatabaseAnswers(host, port, name, user,
                                                                                                                       new PasswordSource.Plaintext(passRaw))))));
    }

    private static boolean defaultPasswordIsEnv(DatabaseAnswers existing) {
        if (existing == null) {
            return true;
        }
        return existing.password() instanceof PasswordSource.FromEnv;
    }

    private static Option<String> extractEnvVar(PasswordSource source) {
        return switch (source) {
            case PasswordSource.FromEnv fromEnv -> Option.some(fromEnv.envVar());
            case PasswordSource.Plaintext _ -> Option.none();
        };
    }

    private static Option<String> extractPlaintext(PasswordSource source) {
        return switch (source) {
            case PasswordSource.FromEnv _ -> Option.none();
            case PasswordSource.Plaintext plain -> Option.some(plain.value());
        };
    }

    private static ClusterConfigAnswers stateWithDatabase(ClusterConfigAnswers state, Option<DatabaseAnswers> database) {
        return new ClusterConfigAnswers(state.clusterName(),
                                         state.clusterVersion(),
                                         state.target(),
                                         state.cloud(),
                                         state.ssh(),
                                         state.topology(),
                                         database,
                                         state.firewallPreset(),
                                         state.adminCidr(),
                                         state.internalCidr(),
                                         state.customFirewallRules(),
                                         state.tls(),
                                         state.secret());
    }

    // ---------------------------------------------------------------------------
    // Step 7: Firewall

    private static StepResult stepFirewall(ClusterConfigAnswers state, Prompt prompt) {
        var preset = prompt.choice("Firewall preset", Arrays.asList(FirewallPreset.values()), state.firewallPreset());
        return switch (preset) {
            case STANDARD -> new StepResult.Continue(stateWithFirewall(state, FirewallPreset.STANDARD,
                                                                         Option.none(), Option.none(), List.of()));
            case RESTRICTIVE -> firewallRestrictive(state, prompt);
            case CUSTOM -> firewallCustom(state, prompt);
            case OPEN -> firewallOpen(state, prompt);
        };
    }

    private static StepResult firewallRestrictive(ClusterConfigAnswers state, Prompt prompt) {
        var detected = IpDetector.suggestAdminCidr();
        var defaultAdmin = state.adminCidr().or(detected.isEmpty() ? "" : detected);
        return guardedPrompt(prompt, "Admin CIDR", defaultAdmin,
                              adminRaw -> validatedContinue(adminRaw, InputValidators::validateCidr,
                                                             _ -> firewallRestrictive(state, prompt),
                                                             admin -> firewallInternalCidr(state, admin, prompt)));
    }

    private static StepResult firewallInternalCidr(ClusterConfigAnswers state, String admin, Prompt prompt) {
        var defaultInternal = state.internalCidr().or(FirewallPresets.DEFAULT_INTERNAL_CIDR);
        return guardedPrompt(prompt, "Internal CIDR", defaultInternal,
                              internalRaw -> validatedContinue(internalRaw, InputValidators::validateCidr,
                                                                _ -> firewallInternalCidr(state, admin, prompt),
                                                                internal -> new StepResult.Continue(stateWithFirewall(state,
                                                                                                                       FirewallPreset.RESTRICTIVE,
                                                                                                                       Option.some(admin),
                                                                                                                       Option.some(internal),
                                                                                                                       List.of()))));
    }

    private static StepResult firewallCustom(ClusterConfigAnswers state, Prompt prompt) {
        var rules = collectCustomRules(prompt);
        return new StepResult.Continue(stateWithFirewall(state, FirewallPreset.CUSTOM,
                                                          Option.none(), Option.none(), rules));
    }

    private static List<FirewallRule> collectCustomRules(Prompt prompt) {
        var rules = new ArrayList<FirewallRule>();
        do {
            rules.add(readSingleRule(prompt));
        } while (prompt.confirm("Add another rule?", false));
        return List.copyOf(rules);
    }

    private static FirewallRule readSingleRule(Prompt prompt) {
        var port = prompt.promptValidated("Rule port", "8080", InputValidators::validatePort);
        var protocolRaw = prompt.prompt("Rule protocol (tcp/udp)", "tcp").trim().toLowerCase();
        var protocol = "udp".equals(protocolRaw) ? "udp" : "tcp";
        var cidr = prompt.promptValidated("Source CIDR", "0.0.0.0/0", InputValidators::validateCidr);
        var description = prompt.prompt("Description (optional)", "");
        var descOption = description.isEmpty() ? Option.<String>none() : Option.some(description);
        return FirewallRule.firewallRule(port, protocol, cidr, descOption);
    }

    private static StepResult firewallOpen(ClusterConfigAnswers state, Prompt prompt) {
        System.out.println("  ⚠  WARNING: OPEN preset emits no firewall rules at all. Network-level security is your responsibility.");
        var confirmed = prompt.confirm("Are you sure? OPEN means no firewall rules at all.", false);
        if (!confirmed) {
            return stepFirewall(state, prompt);
        }
        return new StepResult.Continue(stateWithFirewall(state, FirewallPreset.OPEN, Option.none(), Option.none(), List.of()));
    }

    private static ClusterConfigAnswers stateWithFirewall(ClusterConfigAnswers state,
                                                            FirewallPreset preset,
                                                            Option<String> adminCidr,
                                                            Option<String> internalCidr,
                                                            List<FirewallRule> customRules) {
        return new ClusterConfigAnswers(state.clusterName(),
                                         state.clusterVersion(),
                                         state.target(),
                                         state.cloud(),
                                         state.ssh(),
                                         state.topology(),
                                         state.database(),
                                         preset,
                                         adminCidr,
                                         internalCidr,
                                         customRules,
                                         state.tls(),
                                         state.secret());
    }

    // ---------------------------------------------------------------------------
    // Step 8: TLS + secret

    private static StepResult stepSecurity(ClusterConfigAnswers state, Prompt prompt) {
        var tlsChoices = List.of("Auto-generate at bootstrap (recommended)", "Provide cert/key paths via env vars");
        var tlsChoice = prompt.choice("TLS configuration", tlsChoices, tlsChoices.getFirst());
        var tls = tlsChoice.equals(tlsChoices.getFirst()) ? autoGenTls() : manualTls(prompt);
        return secretChoice(state, tls, prompt);
    }

    private static TlsAnswers autoGenTls() {
        return new TlsAnswers.AutoGenerate();
    }

    private static TlsAnswers manualTls(Prompt prompt) {
        var certEnv = prompt.promptValidated("TLS cert path env var", "TLS_CERT_PATH", InputValidators::validateEnvVarName);
        var keyEnv = prompt.promptValidated("TLS key path env var", "TLS_KEY_PATH", InputValidators::validateEnvVarName);
        return new TlsAnswers.Manual(certEnv, keyEnv);
    }

    private static StepResult secretChoice(ClusterConfigAnswers state, TlsAnswers tls, Prompt prompt) {
        var secretChoices = List.of("Auto-generate at bootstrap (recommended)", "Read from environment variable");
        var secretChoice = prompt.choice("Cluster secret source", secretChoices, secretChoices.getFirst());
        SecretAnswers secret;
        if (secretChoice.equals(secretChoices.getFirst())) {
            secret = new SecretAnswers.AutoGenerate();
        } else {
            var envVar = prompt.promptValidated("Cluster secret env var name", "AETHER_CLUSTER_SECRET", InputValidators::validateEnvVarName);
            secret = new SecretAnswers.FromEnv(envVar);
        }
        return new StepResult.Continue(stateWithSecurity(state, tls, secret));
    }

    private static ClusterConfigAnswers stateWithSecurity(ClusterConfigAnswers state, TlsAnswers tls, SecretAnswers secret) {
        return new ClusterConfigAnswers(state.clusterName(),
                                         state.clusterVersion(),
                                         state.target(),
                                         state.cloud(),
                                         state.ssh(),
                                         state.topology(),
                                         state.database(),
                                         state.firewallPreset(),
                                         state.adminCidr(),
                                         state.internalCidr(),
                                         state.customFirewallRules(),
                                         tls,
                                         secret);
    }

    // ---------------------------------------------------------------------------
    // Step 9: Review

    private static StepResult stepReview(ClusterConfigAnswers state, Prompt prompt) {
        printSummary(state);
        var generate = prompt.confirm("Generate config?", true);
        if (!generate) {
            return new StepResult.Abort();
        }
        return new StepResult.Continue(state);
    }

    private static void printSummary(ClusterConfigAnswers state) {
        System.out.println("");
        System.out.println("--- Review ---");
        System.out.println("  Cluster:    " + state.clusterName() + " v" + state.clusterVersion());
        System.out.println("  Target:     " + state.target().value());
        state.cloud().onPresent(ClusterConfigWizard::printCloudSummary);
        state.ssh().onPresent(ClusterConfigWizard::printSshSummary);
        System.out.println("  Topology:   " + state.topology().core() + " core + " + state.topology().worker() + " worker");
        state.database().onPresent(ClusterConfigWizard::printDatabaseSummary);
        if (state.target() == SourceType.CLOUD || state.target() == SourceType.SSH) {
            printSecuritySummary(state);
        }
        System.out.println("");
    }

    private static void printCloudSummary(CloudAnswers cloud) {
        System.out.println("  Provider:   " + cloud.provider().value());
        System.out.println("  Region:     " + cloud.region());
        System.out.println("  Instance:   " + cloud.instanceType());
        System.out.println("  Credential: ${env:" + cloud.credentialEnvVar() + "}");
    }

    private static void printSshSummary(SshAnswers ssh) {
        System.out.println("  Hosts:      " + String.join(", ", ssh.hosts()));
        System.out.println("  SSH user:   " + ssh.user());
        System.out.println("  SSH key:    " + ssh.keyPath());
        System.out.println("  SSH port:   " + ssh.port());
    }

    private static void printDatabaseSummary(DatabaseAnswers db) {
        System.out.println("  Database:   " + db.user() + "@" + db.host() + ":" + db.port() + "/" + db.name());
        System.out.println("  Password:   " + summarizePassword(db.password()));
    }

    private static void printSecuritySummary(ClusterConfigAnswers state) {
        System.out.println("  Firewall:   " + state.firewallPreset());
        state.adminCidr().onPresent(c -> System.out.println("    admin:    " + c));
        state.internalCidr().onPresent(c -> System.out.println("    internal: " + c));
        if (state.firewallPreset() == FirewallPreset.CUSTOM) {
            System.out.println("    custom rules: " + state.customFirewallRules().size());
        }
        System.out.println("  TLS:        " + summarizeTls(state.tls()));
        System.out.println("  Secret:     " + summarizeSecret(state.secret()));
    }

    private static String summarizePassword(PasswordSource source) {
        return switch (source) {
            case PasswordSource.FromEnv fromEnv -> "${env:" + fromEnv.envVar() + "}";
            case PasswordSource.Plaintext _ -> "<plaintext, hidden>";
        };
    }

    private static String summarizeTls(TlsAnswers tls) {
        return switch (tls) {
            case TlsAnswers.AutoGenerate _ -> "auto-generate";
            case TlsAnswers.Manual manual -> "env: " + manual.certPathEnvVar() + " / " + manual.keyPathEnvVar();
            case TlsAnswers.Skipped _ -> "skipped";
        };
    }

    private static String summarizeSecret(SecretAnswers secret) {
        return switch (secret) {
            case SecretAnswers.AutoGenerate _ -> "auto-generate";
            case SecretAnswers.FromEnv fromEnv -> "${env:" + fromEnv.envVar() + "}";
            case SecretAnswers.Skipped _ -> "skipped";
        };
    }

    // ---------------------------------------------------------------------------
    // Guarded prompt + helpers

    private static StepResult guardedPrompt(Prompt prompt, String question, String defaultValue,
                                              Function<String, StepResult> onValue) {
        var raw = prompt.prompt(question, defaultValue);
        if (BACK_KEYWORD.equalsIgnoreCase(raw)) {
            return new StepResult.Back();
        }
        if (ABORT_KEYWORD.equalsIgnoreCase(raw)) {
            return new StepResult.Abort();
        }
        return onValue.apply(raw);
    }

    private static <T> StepResult validatedContinue(String raw, Function<String, Result<T>> validator,
                                                      Function<Cause, StepResult> onFailure,
                                                      Function<T, StepResult> onSuccess) {
        var result = validator.apply(raw);
        return result.fold(cause -> reportAndRetry(cause, onFailure),
                              onSuccess::apply);
    }

    private static StepResult reportAndRetry(Cause cause, Function<Cause, StepResult> onFailure) {
        System.out.println("  ✗ " + cause.message());
        return onFailure.apply(cause);
    }

    private static Result<String> nonEmpty(String raw) {
        var trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return new ClusterInitError.InvalidValue("value", "", "must not be empty").result();
        }
        return Result.success(trimmed);
    }

    // ---------------------------------------------------------------------------
    // Step descriptor + result types

    sealed interface StepResult {
        record Continue(ClusterConfigAnswers answers) implements StepResult {}
        record Back() implements StepResult {}
        record Abort() implements StepResult {}
    }

    @FunctionalInterface
    interface StepDef {
        StepResult run(ClusterConfigAnswers state, Prompt prompt);
    }

    private record NamedStep(String name, Predicate<ClusterConfigAnswers> applies, StepDef def) {}
}
