// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.pragmatica.aether.config.SharedTierConfig;
import org.pragmatica.cloud.aws.AwsCredentialsProvider;
import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.cloud.aws.s3.S3Config;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.EncryptingStorageTier;
import org.pragmatica.storage.EncryptionError;
import org.pragmatica.storage.EncryptionKeyring;
import org.pragmatica.storage.RemoteTier;
import org.pragmatica.storage.StorageTier;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// #249: builds the S3-compatible shared tier of `[storage.<instance>.shared] kind = "s3"` and admits it at boot.
///
/// Admission runs during storage construction, before the node object exists, because an object store needs
/// no cluster (unlike the DHT marker, which waits for formation). Its guards are READ-ONLY, and any refusal
/// fails the boot with [SharedTierUnavailable] naming the instance, endpoint and bucket:
/// 1. the probe ([RemoteTier#probe]): PUT, GET and DELETE of `{prefix}.aether-probe/{nodeId}`. A configured
///    durable tier that is unreachable, refuses this node's credentials, or lacks read-after-write is a
///    misconfiguration, not a degraded mode (the probe's own object is the one write admission makes, and it
///    deletes it);
/// 2. the encryption marker `{prefix}.encryption-enabled`, as for the local disk: without a keyring a present
///    marker refuses with [EncryptionError.EncryptedTierRequiresKeyring]; with a keyring and no marker, a prefix
///    that already holds objects refuses with [EncryptionError.EnablingOverExistingPlaintext] (they would be
///    stranded as legacy plaintext), and an empty prefix ARMS the marker write.
///
/// The armed write is NOT performed here. It is returned as a [PendingMarker] that `StorageFactory.createAll`
/// commits only after every instance's guards have passed (#852): a boot refused by any instance or tier stamps
/// no bucket, so backing the keyring out afterwards boots.
///
/// The admitted tier is wrapped in [EncryptingStorageTier] under the instance's keyring, exactly as the DHT is.
/// Credentials are resolved through [AwsCredentialsProvider] (there is no AWS SDK in the tree) and are never
/// logged: no cause or log line here carries a secret.
sealed interface S3SharedTier {
    /// Bounds the whole admission (credential fetch, probe, marker read, listing) on top of each request's own timeout.
    int ADMISSION_REQUESTS = 6;
    /// How many existing keys the plaintext check lists at most; one is enough to refuse, more makes the count useful.
    int EXISTING_OBJECTS_LIMIT = 1000;

    /// An admitted S3 tier, wrapped under the keyring when there is one, and its marker write still pending.
    record AdmittedTier(StorageTier tier, Option<PendingMarker> pendingMarker) {}

    /// The `{prefix}.encryption-enabled` write an admitted, encrypted, still-unmarked prefix needs; committed by
    /// `StorageFactory` once every guard of the boot has passed.
    record PendingMarker(String instance, SharedTierConfig.S3 config, RemoteTier tier, String keyId) {
        @TerminalOperation
        Result<Unit> commit() {
            return tier.writeMarker(EncryptingStorageTier.MARKER_FILE_NAME,
                                    keyId.getBytes(StandardCharsets.UTF_8))
                       .await(admissionBound(config))
                       .mapError(cause -> SharedTierUnavailable.FACTORY.apply(instance,
                                                                              config.endpoint(),
                                                                              config.bucket(),
                                                                              cause));
        }
    }

    /// The tier for `config`, admitted, wrapped under `keyring` when present, with its marker write still pending.
    static Result<AdmittedTier> s3SharedTier(String instance,
                                             SharedTierConfig.S3 config,
                                             String nodeId,
                                             Option<EncryptionKeyring> keyring) {
        var http = httpOperations(config.requestTimeout());

        return credentialsProvider(config.credentials(),
                                   http).flatMap(provider -> remoteTier(config, http, provider))
                                  .flatMap(tier -> admit(instance, config, nodeId, keyring, tier))
                                  .mapError(cause -> SharedTierUnavailable.FACTORY.apply(instance,
                                                                                         config.endpoint(),
                                                                                         config.bucket(),
                                                                                         cause));
    }

    /// The configured credentials source as a provider. `env` and `file:` are read now, so a missing variable
    /// or unreadable file fails the boot here; `instance` is fetched on first use, which the probe forces.
    private static Result<AwsCredentialsProvider> credentialsProvider(SharedTierConfig.Credentials credentials,
                                                                      HttpOperations http) {
        return switch (credentials) {
            case SharedTierConfig.Credentials.Environment _ -> AwsCredentialsProvider.environment();
            case SharedTierConfig.Credentials.File file -> AwsCredentialsProvider.profileFile(file.path());
            case SharedTierConfig.Credentials.InstanceProfile _ -> success(AwsCredentialsProvider.instanceProfile(http));
        };
    }

    /// One HTTP client per configured tier, for the node's lifetime: storage tiers have no close hook, and the
    /// client is shared by every request of the tier. No redirects: S3 answers a wrong region with a 301 that
    /// must surface as a failure, not be followed unsigned.
    private static HttpOperations httpOperations(TimeSpan requestTimeout) {
        return JdkHttpOperations.jdkHttpOperations(requestTimeout.duration(), HttpClient.Redirect.NEVER, none());
    }

    private static Result<RemoteTier> remoteTier(SharedTierConfig.S3 config,
                                                 HttpOperations http,
                                                 AwsCredentialsProvider credentials) {
        var location = S3Config.s3Endpoint(config.endpoint(), config.bucket(), config.region(), config.pathStyle());
        var client = S3Client.s3Client(location,
                                       http,
                                       credentials,
                                       some(config.requestTimeout().duration()));

        return RemoteTier.durableRemoteTier(client, config.prefix(), config.maxInflight(), config.requestTimeout());
    }

    private static TimeSpan admissionBound(SharedTierConfig.S3 config) {
        return TimeSpan.fromDuration(config.requestTimeout().duration().multipliedBy(ADMISSION_REQUESTS));
    }

    /// Boot-time admission: blocks storage construction (itself synchronous, `Result`-returning) on the probe and
    /// the encryption guard, bounded by [#ADMISSION_REQUESTS] request timeouts. Runs once per configured S3 tier.
    @TerminalOperation
    private static Result<AdmittedTier> admit(String instance,
                                              SharedTierConfig.S3 config,
                                              String nodeId,
                                              Option<EncryptionKeyring> keyring,
                                              RemoteTier tier) {
        return tier.probe(nodeId)
                   .flatMap(_ -> tier.readMarker(EncryptingStorageTier.MARKER_FILE_NAME))
                   .flatMap(marker -> encryptionGuard(instance, config, tier, keyring, marker))
                   .await(admissionBound(config));
    }

    private static Promise<AdmittedTier> encryptionGuard(String instance,
                                                         SharedTierConfig.S3 config,
                                                         RemoteTier tier,
                                                         Option<EncryptionKeyring> keyring,
                                                         Option<byte[]> marker) {
        return keyring.fold(() -> refuseIfMarked(instance, tier, marker),
                            ring -> armEncrypted(instance, config, tier, ring, marker));
    }

    private static Promise<AdmittedTier> refuseIfMarked(String instance, RemoteTier tier, Option<byte[]> marker) {
        return marker.fold(() -> Promise.success(new AdmittedTier(tier, none())), keyId -> refusal(instance, keyId));
    }

    /// Marker present: the prefix is already encrypted, nothing to write. Marker absent: the prefix must be empty,
    /// and the marker write is armed, not performed.
    private static Promise<AdmittedTier> armEncrypted(String instance,
                                                      SharedTierConfig.S3 config,
                                                      RemoteTier tier,
                                                      EncryptionKeyring ring,
                                                      Option<byte[]> marker) {
        var wrapped = EncryptingStorageTier.wrap(tier, ring);

        return marker.fold(() -> refuseIfNotEmpty(config, tier).map(_ -> new AdmittedTier(wrapped,
                                                                                          some(new PendingMarker(instance,
                                                                                                                 config,
                                                                                                                 tier,
                                                                                                                 ring.activeKeyId())))),
                           _ -> Promise.success(new AdmittedTier(wrapped, none())));
    }

    private static Promise<Unit> refuseIfNotEmpty(SharedTierConfig.S3 config, RemoteTier tier) {
        return tier.existingObjects(EXISTING_OBJECTS_LIMIT)
                   .flatMap(keys -> requireEmpty(config, keys));
    }

    private static Promise<Unit> requireEmpty(SharedTierConfig.S3 config, List<String> existing) {
        return existing.isEmpty()
               ? Promise.success(unit())
               : new EncryptionError.EnablingOverExistingPlaintext("s3://" + config.bucket() + "/" + config.prefix(),
                                                                   existing.size()).promise();
    }

    private static <T> Promise<T> refusal(String instance, byte[] keyId) {
        return new EncryptionError.EncryptedTierRequiresKeyring(instance, new String(keyId, StandardCharsets.UTF_8)).promise();
    }

    /// The configured S3 shared tier could not be admitted at boot. Terminal: the node does not start on it.
    /// Unlike a client-facing failure, this message is read by the operator at boot, so it ends with the
    /// origin's message (no secret is ever in it); the typed origin travels as `origin` for `source()`.
    record SharedTierUnavailable(String instance, String endpoint, String bucket, Cause origin, String message) implements S3SharedTier, Cause.Wrapped, Cause.Terminal {
        private static final String TEMPLATE = "Storage '%s': the configured S3 shared tier (endpoint %s, bucket %s) could not be "
                                             + "admitted at boot (credentials, probe or encryption marker); a configured "
                                             + "durable tier that is absent is a misconfiguration: %s";

        static final Fn4<SharedTierUnavailable, String, String, String, Cause> FACTORY = (instance, endpoint, bucket, origin) -> new SharedTierUnavailable(instance,
                                                                                                                                                           endpoint,
                                                                                                                                                           bucket,
                                                                                                                                                           origin,
                                                                                                                                                           String.format(TEMPLATE,
                                                                                                                                                                         instance,
                                                                                                                                                                         endpoint,
                                                                                                                                                                         bucket,
                                                                                                                                                                         chainMessage(origin)));

        /// The origin's message followed by each source below it, so "unavailable" carries its transport reason.
        private static String chainMessage(Cause origin) {
            return origin.message() + origin.source()
                                            .map(source -> " <- " + chainMessage(source))
                                            .or("");
        }

        /// Terminal wins over the origin's classification: a verdict is never also "worth retrying".
        @Override
        public boolean isTerminal() {
            return true;
        }

        @Override
        public boolean isTransient() {
            return false;
        }
    }

    record unused() implements S3SharedTier {}
}
