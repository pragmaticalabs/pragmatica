// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.config.cluster.CloudCredentialSchema;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.lang.Option;


public record ClusterConfigAnswers(String clusterName,
                                   String clusterVersion,
                                   SourceType target,
                                   Option<CloudAnswers> cloud,
                                   Option<SshAnswers> ssh,
                                   CoreWorkerSplit topology,
                                   Option<DatabaseAnswers> database,
                                   FirewallPreset firewallPreset,
                                   Option<String> adminCidr,
                                   Option<String> internalCidr,
                                   List<FirewallRule> customFirewallRules,
                                   TlsAnswers tls,
                                   SecretAnswers secret) {
    public ClusterConfigAnswers {
        customFirewallRules = customFirewallRules == null
                              ? List.of()
                              : List.copyOf(customFirewallRules);
    }

    /// `sshPublicKeyPath` is the operator-local path to a PUBLIC key, written to the generated
    /// config as `[infrastructure.ssh] public_key_file`. It is a cloud-target concern: provisioned
    /// VMs get the key injected at create time, and `SshKeyResolver.resolveOrFailIfCloud` refuses
    /// to bootstrap a cloud cluster without one. Distinct from [SshAnswers#keyPath], which is the
    /// PRIVATE key used to reach already-existing SSH hosts.
    ///
    /// `zone` is the gcp zone (its factory requires one, and no default exists: a defaulted zone can name
    /// one that does not exist); empty for every other provider. `credentialEnvVars` maps each credential key
    /// the operator supplies from the environment (see [#credentialKeys]) to the env var that holds it.
    public record CloudAnswers(CloudProviderName provider,
                               String region,
                               String zone,
                               String instanceType,
                               Map<String, String> credentialEnvVars,
                               String sshPublicKeyPath) {
        public CloudAnswers {
            credentialEnvVars = Collections.unmodifiableMap(new LinkedHashMap<>(credentialEnvVars));
        }

        /// The `[cloud.credentials]` keys the operator supplies as secrets: the provider's required keys less
        /// the ones the source's own region/zone fields fill in.
        public static List<String> credentialKeys(CloudProviderName provider) {
            return CloudCredentialSchema.requiredKeys(provider.value())
                                        .stream()
                                        .filter(key -> !LOCATION_KEYS.contains(key))
                                        .toList();
        }

        /// The conventional env var for a credential key: `HCLOUD_TOKEN` for hetzner, else `<PROVIDER>_<KEY>`.
        public static String defaultEnvVar(CloudProviderName provider, String key) {
            return provider == CloudProviderName.HETZNER
                   ? "HCLOUD_TOKEN"
                   : (provider.value() + "_" + key).toUpperCase(Locale.ROOT);
        }

        public static Map<String, String> defaultEnvVars(CloudProviderName provider) {
            var defaults = new LinkedHashMap<String, String>();

            credentialKeys(provider).forEach(key -> defaults.put(key, defaultEnvVar(provider, key)));

            return defaults;
        }

        private static final Set<String> LOCATION_KEYS = Set.of("region", "zone", "location");
    }

    public record SshAnswers(List<String> hosts, String user, Path keyPath, int port) {
        public SshAnswers {
            hosts = List.copyOf(hosts);
        }
    }

    public record DatabaseAnswers(String host, int port, String name, String user, PasswordSource password) {
        public sealed interface PasswordSource {
            record FromEnv(String envVar) implements PasswordSource {}

            record Plaintext(String value) implements PasswordSource {}
        }
    }

    public sealed interface TlsAnswers {
        record AutoGenerate() implements TlsAnswers {}

        record Manual(String certPathEnvVar, String keyPathEnvVar) implements TlsAnswers {}

        record Skipped() implements TlsAnswers {}
    }

    public sealed interface SecretAnswers {
        record AutoGenerate() implements SecretAnswers {}

        record FromEnv(String envVar) implements SecretAnswers {}

        record Skipped() implements SecretAnswers {}
    }
}
