// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.cloud.hetzner.api.SshKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.cli.cluster.BootstrapPhase.UPLOAD_SSH_KEYS;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public sealed interface BootstrapPhaseSshKey {
    record unused() implements BootstrapPhaseSshKey {}

    /// Base (cluster-less) Hetzner ssh-key name prefix. RFC-0016 W3 §3.3: the ACTUAL upload
    /// name is cluster-scoped — `aether-bootstrap-<cluster>-<blob8>` (see [#hetznerKeyPrefix]) —
    /// so keys never collide across clusters on one account and a leader resolves ONLY its own
    /// cluster's keys (no account-wide guessing, no dual-accept of the old bare name; Q1: no
    /// pre-rc3 clusters). Mirrored provider-side by `HetznerComputeProvider.BOOTSTRAP_KEY_NAME_PREFIX`.
    String HETZNER_KEY_NAME_PREFIX = "aether-bootstrap";

    static Result<BootstrapContext> execute(BootstrapContext ctx) {
        return execute(ctx, BootstrapPhaseSshKey::defaultHetznerClientForSource);
    }

    static Result<BootstrapContext> execute(BootstrapContext ctx,
                                            Fn1<Result<HetznerClient>, SourceProfile> hetznerClientFactory) {
        ClusterBootstrapOrchestrator.logPhase(UPLOAD_SSH_KEYS,
                                              "Uploading %d operator SSH key(s) to cloud providers",
                                              ctx.sshPublicKeys().size());
        if (ctx.sshPublicKeys().isEmpty()) {
            return success(ctx);
        }

        return uploadForAllSources(ctx, hetznerClientFactory);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Result<BootstrapContext> uploadForAllSources(BootstrapContext ctx,
                                                                Fn1<Result<HetznerClient>, SourceProfile> hetznerClientFactory) {
        var nextCtx = ctx;

        for (var entry : ctx.config().sources().entrySet()) {
            var source = entry.getValue();

            if (source.type() != SourceType.CLOUD) {
                continue;
            }

            var providerName = source.provider().map(p -> p.value()).or("");

            if (!"hetzner".equals(providerName)) {
                continue;
            }

            var result = uploadForHetzner(nextCtx, source, hetznerClientFactory);

            if (result.isFailure()) {
                return result;
            }

            nextCtx = result.unwrap();
        }

        return success(nextCtx);
    }

    private static Result<BootstrapContext> uploadForHetzner(BootstrapContext ctx,
                                                             SourceProfile source,
                                                             Fn1<Result<HetznerClient>, SourceProfile> hetznerClientFactory) {
        return hetznerClientFactory.apply(source)
                                   .flatMap(client -> uploadAllKeys(ctx, client));
    }

    private static Result<BootstrapContext> uploadAllKeys(BootstrapContext ctx, HetznerClient client) {
        var ids = new ArrayList<Long>();
        var nextCtx = ctx;

        for (var key : ctx.sshPublicKeys()) {
            var currentCtx = nextCtx;
            var result = uploadOrReuse(client, key, currentCtx);

            if (result.isFailure()) {
                return result.map(_ -> currentCtx);
            }

            var uploaded = result.unwrap();

            ids.add(uploaded.sshKeyId());
            nextCtx = uploaded.contextAfter();
        }

        return success(nextCtx.withSshKeyIds("hetzner", List.copyOf(ids)));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<UploadOutcome> uploadOrReuse(HetznerClient client, SshPublicKey key, BootstrapContext ctx) {
        var fingerprint = computeMd5Fingerprint(key);

        if (fingerprint.isFailure()) {
            return fingerprint.map(_ -> new UploadOutcome(0L, ctx));
        }

        var existing = lookupExisting(client, fingerprint.unwrap());

        if (existing.isFailure()) {
            return existing.map(_ -> new UploadOutcome(0L, ctx));
        }

        var existingOpt = existing.unwrap();

        if (existingOpt.isPresent()) {
            var existingKey = existingOpt.unwrap();

            System.out.printf("  Reusing existing Hetzner SSH key '%s' (id=%d)%n", existingKey.name(), existingKey.id());

            return success(new UploadOutcome(existingKey.id(), ctx));
        }

        return createNewKey(client, key, ctx);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<UploadOutcome> createNewKey(HetznerClient client, SshPublicKey key, BootstrapContext ctx) {
        var name = key.hetznerKeyName(hetznerKeyPrefix(ctx.config().cluster().name()));
        var request = SshKey.CreateSshKeyRequest.createSshKeyRequest(name, key.value());
        var promise = client.createSshKey(request);
        var result = promise.await();

        return result.map(uploaded -> recordUpload(uploaded, ctx));
    }

    /// Cluster-scoped Hetzner ssh-key name prefix (RFC-0016 W3 §3.3): `aether-bootstrap-<cluster>`.
    /// Blank-defensive — a null/blank cluster name collapses to the bare [#HETZNER_KEY_NAME_PREFIX]
    /// (no trailing dash, no empty cluster segment), mirroring how the provider's
    /// `HetznerComputeProvider.bootstrapKeyPrefix` defends. Uploaded keys carry this prefix so the
    /// provider resolves ONLY the cluster's own keys at replacement time.
    static String hetznerKeyPrefix(String cluster) {
        return Option.option(cluster)
                     .map(String::trim)
                     .filter(name -> !name.isEmpty())
                     .map(name -> HETZNER_KEY_NAME_PREFIX + "-" + name)
                     .or(HETZNER_KEY_NAME_PREFIX);
    }

    private static UploadOutcome recordUpload(SshKey uploaded, BootstrapContext ctx) {
        System.out.printf("  Uploaded new Hetzner SSH key '%s' (id=%d)%n", uploaded.name(), uploaded.id());
        var resource = CreatedResource.SshKeyResource.sshKeyResource("hetzner", uploaded.id(), uploaded.name());
        var newCtx = ctx.withState(ctx.state().withResource(resource));

        return new UploadOutcome(uploaded.id(), newCtx);
    }

    private static Result<Option<SshKey>> lookupExisting(HetznerClient client, String fingerprint) {
        return client.listSshKeys()
                     .await()
                     .map(keys -> findByFingerprint(keys, fingerprint));
    }

    private static Option<SshKey> findByFingerprint(List<SshKey> keys, String fingerprint) {
        return Option.option(keys.stream()
                                 .filter(k -> fingerprint.equalsIgnoreCase(k.fingerprint()))
                                 .findFirst()
                                 .orElse(null));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<String> computeMd5Fingerprint(SshPublicKey key) {
        return Result.lift(FingerprintFailed::new, () -> doComputeFingerprint(key));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String doComputeFingerprint(SshPublicKey key) throws Exception {
        var blob = Base64.getDecoder().decode(key.blob());
        var md5 = MessageDigest.getInstance("MD5").digest(blob);
        var hex = HexFormat.of().formatHex(md5);
        var sb = new StringBuilder(hex.length() + (hex.length() / 2));

        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                sb.append(':');
            }

            sb.append(hex, i, i + 2);
        }

        return sb.toString();
    }

    private static Result<HetznerClient> defaultHetznerClientForSource(SourceProfile source) {
        var token = source.credentials().or(System.getenv("HCLOUD_TOKEN"));

        if (token == null || token.isBlank()) {
            return new HetznerCredentialsMissing().result();
        }

        return success(HetznerClient.hetznerClient(HetznerConfig.hetznerConfig(token)));
    }

    record UploadOutcome(long sshKeyId, BootstrapContext contextAfter) {}

    record FingerprintFailed(Throwable cause) implements Cause {
        @Override
        public String message() {
            return "Failed to compute SSH key MD5 fingerprint: " + cause.getMessage();
        }
    }

    record HetznerCredentialsMissing() implements Cause {
        @Override
        public String message() {
            return "Hetzner credentials missing: set HCLOUD_TOKEN env var or source.credentials";
        }
    }
}
