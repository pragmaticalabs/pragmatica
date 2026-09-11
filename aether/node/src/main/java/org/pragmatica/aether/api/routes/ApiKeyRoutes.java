// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyAuditKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyAuditValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.http.routing.PathParameter.aString;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"})
public final class ApiKeyRoutes implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyRoutes.class);
    private static final TimeSpan SWEEP_INTERVAL = TimeSpan.timeSpan(60).seconds();
    /// A key this API created and can revoke: its record lives in the replicated KV store.
    public static final String SOURCE_CLUSTER = "cluster";
    /// A key declared in the node's configuration file or `AETHER_API_KEYS`. The node HONOURS it —
    /// [`org.pragmatica.aether.http.security.KvStoreApiKeyValidator`] consults the config validator
    /// first — but this API is not its authority and cannot retire it. Listed so an operator can
    /// SEE every credential the node accepts; see [#handleRevokeKey] for what revoking one does.
    public static final String SOURCE_CONFIG = "config";
    /// Synthetic, and never the key itself. The map key in `AppHttpConfig.apiKeys()` IS the secret,
    /// so the listing identifies a configured key by its declared name only.
    public static final String CONFIG_KEY_ID_PREFIX = "config:";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final long NOT_APPLICABLE = -1L;

    private final Supplier<ManageableNode> nodeSupplier;
    private final Supplier<Map<String, ApiKeyEntry>> configuredKeysSupplier;
    private final ScheduledFuture<?> sweepTask;

    private ApiKeyRoutes(Supplier<ManageableNode> nodeSupplier,
                         Supplier<Map<String, ApiKeyEntry>> configuredKeysSupplier) {
        this.nodeSupplier = nodeSupplier;
        this.configuredKeysSupplier = configuredKeysSupplier;
        this.sweepTask = SharedScheduler.scheduleAtFixedRate(this::sweepExpiredKeys, SWEEP_INTERVAL);
    }

    public static ApiKeyRoutes apiKeyRoutes(Supplier<ManageableNode> nodeSupplier,
                                            Supplier<Map<String, ApiKeyEntry>> configuredKeysSupplier) {
        return new ApiKeyRoutes(nodeSupplier, configuredKeysSupplier);
    }

    /// Cancel the expiry sweep (#642). [`SharedScheduler`] is process-wide and this route source is
    /// per-`ManagementServer`, so without this every node ever started in a shared JVM keeps sweeping
    /// API keys through a `nodeSupplier` whose node is gone. Called from `ManagementServer.stop()`.
    @Contract
    public void stop() {
        sweepTask.cancel(false);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<Object> route(ManagementRoute.CLUSTER_KEYS_CREATE)
                                         .withBody(CreateKeyRequest.class)
                                         .toJson(this::handleCreateKey),
                         ManagementRoutes.<List<KeyInfo>> route(ManagementRoute.CLUSTER_KEYS_LIST)
                                         .to(_ -> handleListKeys())
                                         .asJson(),
                         ManagementRoutes.<Object> route(ManagementRoute.CLUSTER_KEYS_REVOKE)
                                         .withPath(aString())
                                         .withBody(RevokeKeyRequest.class)
                                         .toJson(this::handleRevokeKey),
                         ManagementRoutes.<List<AuditEntry>> route(ManagementRoute.CLUSTER_KEYS_AUDIT)
                                         .to(_ -> handleListAudit())
                                         .asJson());
    }

    @SuppressWarnings("unchecked")
    private Promise<Object> handleCreateKey(CreateKeyRequest request) {
        var role = request.authorizationRole() == null || request.authorizationRole().isBlank()
                   ? ApiKeyValue.DEFAULT_ROLE
                   : request.authorizationRole().toUpperCase();
        var keyValue = ApiKeyValue.apiKeyValue(request.keyId(), request.keyHash(), request.gracePeriodMs(), role);
        var keyCommand = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ApiKeyKey.apiKeyKey(request.keyId()),
                                                                                  keyValue);
        var auditValue = ApiKeyAuditValue.apiKeyAuditValue(request.keyId(),
                                                           request.auditAction(),
                                                           request.operatorHint());
        var auditId = request.keyId() + "-" + System.currentTimeMillis();
        var auditCommand = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ApiKeyAuditKey.apiKeyAuditKey(auditId),
                                                                                    auditValue);

        log.info("Creating API key entry: keyId={}, status=ACTIVE", request.keyId());

        return nodeSupplier.get()
                           .<Object> apply(List.of(keyCommand, auditCommand))
                           .map(_ -> (Object) new CreateKeyResponse(request.keyId(),
                                                                    "ACTIVE"));
    }

    /// Every credential this node ACCEPTS, from both of its two sources, each record naming which.
    ///
    /// The listing used to show cluster-held keys only, so a key declared in the node's config file
    /// authenticated against every route while being invisible to the tooling an operator uses to
    /// audit credentials — it could be neither enumerated nor rotated, and nothing said so. A
    /// credential that cannot be seen cannot be reasoned about during an incident.
    ///
    /// Config-declared records carry `source = "config"` and a synthetic `config:<name>` id. They
    /// are reported ACTIVE because the node does accept them; timestamps are `-1` because a file
    /// declaration has no creation, expiry or revocation event to report. Clients that ACT on this
    /// listing must filter on `source` — `aether cluster rotate-key` does.
    /// Package-visible so a test can assert what an operator RECEIVES from this endpoint — that a
    /// config-declared credential is listed, and distinguishable from a cluster-held one. Asserting
    /// it through the route table instead would need a bound listener and would test the router.
    Promise<List<KeyInfo>> handleListKeys() {
        var keys = new ArrayList<KeyInfo>();

        nodeSupplier.get().kvStore().forEach(ApiKeyKey.class, ApiKeyValue.class, (_, v) -> keys.add(clusterKeyInfo(v)));
        configuredKeysSupplier.get().values().forEach(entry -> keys.add(configuredKeyInfo(entry)));

        return Promise.success(List.copyOf(keys));
    }

    private static KeyInfo clusterKeyInfo(ApiKeyValue value) {
        return new KeyInfo(value.keyId(),
                           value.status(),
                           value.createdAt(),
                           value.expiresAt(),
                           value.revokedAt(),
                           value.gracePeriodMs(),
                           value.authorizationRole(),
                           SOURCE_CLUSTER);
    }

    private static KeyInfo configuredKeyInfo(ApiKeyEntry entry) {
        return new KeyInfo(configuredKeyId(entry),
                           STATUS_ACTIVE,
                           NOT_APPLICABLE,
                           NOT_APPLICABLE,
                           NOT_APPLICABLE,
                           0L,
                           entry.authorizationRole(),
                           SOURCE_CONFIG);
    }

    static String configuredKeyId(ApiKeyEntry entry) {
        return CONFIG_KEY_ID_PREFIX + entry.name();
    }

    /// REVOKING A CONFIG-DECLARED KEY IS REFUSED, and the refusal is the honest answer rather than a
    /// missing feature.
    ///
    /// Revocation here means committing a REVOKED record through consensus. That works for a
    /// cluster-held key because the KV store is the key's authority. It does not work for a key
    /// declared in a file: the file is the authority, the node cannot rewrite an operator's file,
    /// and [`org.pragmatica.aether.http.security.KvStoreApiKeyValidator`] consults the config
    /// validator FIRST and returns on success, so a tombstone would sit in KV while the key kept
    /// authenticating. Reporting success there would be worse than refusing — an operator would
    /// believe a leaked credential was dead.
    ///
    /// Making it genuinely revocable needs a design ruling this fix deliberately does not take,
    /// because both available answers cost something real. Consulting a KV tombstone before
    /// accepting a config key leaves a bypass window on every restart, since an empty KV during
    /// replay is indistinguishable from "nothing revoked" — a recurring hole exactly when a node
    /// bounces. Gating config keys on `ManageableNode#isReady()` closes that window but stops them
    /// authenticating before the node is ACTIVE, which is when cluster formation uses them.
    ///
    /// The operator's route is the file: remove the declaration and restart the node. The message
    /// says so, because an error an operator cannot act on is a dead end.
    @SuppressWarnings("unchecked")
    Promise<Object> handleRevokeKey(String keyId, RevokeKeyRequest request) {
        var node = nodeSupplier.get();
        var key = ApiKeyKey.apiKeyKey(keyId);
        var existing = node.kvStore().get(key);

        if (isConfiguredKeyId(keyId)) {
            return new ConfigDeclaredKeyError(keyId).promise();
        }

        if (existing.isEmpty()) {
            return new KeyNotFoundError(keyId).promise();
        }

        var value = (ApiKeyValue) existing.unwrap();
        var gracePeriod = request.immediate()
                          ? 0
                          : request.gracePeriodMs();
        var revoked = value.withRevoked(gracePeriod);
        var keyCommand = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, revoked);
        var auditValue = ApiKeyAuditValue.apiKeyAuditValue(keyId,
                                                           ApiKeyAuditValue.ACTION_REVOKED,
                                                           request.operatorHint());
        var auditId = keyId + "-" + System.currentTimeMillis();
        var auditCommand = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ApiKeyAuditKey.apiKeyAuditKey(auditId),
                                                                                    auditValue);

        log.info("Revoking API key: keyId={}, immediate={}, gracePeriodMs={}", keyId, request.immediate(), gracePeriod);

        return node.<Object> apply(List.of(keyCommand, auditCommand))
                   .map(_ -> (Object) new RevokeKeyResponse(keyId, "REVOKED", gracePeriod));
    }

    private Promise<List<AuditEntry>> handleListAudit() {
        var node = nodeSupplier.get();
        var audits = new ArrayList<AuditEntry>();

        node.kvStore()
            .forEach(ApiKeyAuditKey.class,
                     ApiKeyAuditValue.class,
                     (_, v) -> audits.add(new AuditEntry(v.keyId(),
                                                         v.action(),
                                                         v.timestamp(),
                                                         v.operatorHint())));
        audits.sort((a, b) -> Long.compare(b.timestamp(), a.timestamp()));

        return Promise.success(List.copyOf(audits));
    }

    @SuppressWarnings({"unchecked", "JBCT-EX-01"})
    @Contract
    private void sweepExpiredKeys() {
        try {
            var node = nodeSupplier.get();

            if (!node.isLeader()) {
                return;
            }

            var now = System.currentTimeMillis();
            var commands = new ArrayList<KVCommand<AetherKey>>();

            node.kvStore()
                .forEach(ApiKeyKey.class,
                         ApiKeyValue.class,
                         (key, v) -> {
                             if (!v.isActive()) {
                             return;
                         }

                             if (v.expiresAt() <= 0 || v.expiresAt() > now) {
                             return;
                         }

                             var expired = v.withExpired();

                             commands.add((KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, expired));
                             var auditId = v.keyId() + "-expired-" + now;
                             var auditValue = ApiKeyAuditValue.apiKeyAuditValue(v.keyId(),
                                                                                ApiKeyAuditValue.ACTION_EXPIRED,
                                                                                "expiration-sweep");

                             commands.add((KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ApiKeyAuditKey.apiKeyAuditKey(auditId),
                                                                                                   auditValue));
                             log.info("API key expired: keyId={}",
                                      v.keyId());
                         });
            if (!commands.isEmpty()) {
                node.apply(commands);
            }
        } catch (Exception e) {
            log.debug("API key expiration sweep skipped: {}", e.getMessage());
        }
    }

    private boolean isConfiguredKeyId(String keyId) {
        return Option.option(keyId)
                     .filter(id -> id.startsWith(CONFIG_KEY_ID_PREFIX))
                     .map(this::matchesDeclaredKey)
                     .or(false);
    }

    /// The prefix alone is not enough: a caller could otherwise mask any missing key behind the
    /// config refusal by prefixing its id. The id must match a key this node actually declares.
    private boolean matchesDeclaredKey(String keyId) {
        return configuredKeysSupplier.get()
                                     .values()
                                     .stream()
                                     .anyMatch(entry -> configuredKeyId(entry).equals(keyId));
    }

    record CreateKeyRequest(String keyId,
                            String keyHash,
                            long gracePeriodMs,
                            String auditAction,
                            String operatorHint,
                            String authorizationRole) {}

    record CreateKeyResponse(String keyId, String status) {}

    record RevokeKeyRequest(boolean immediate, long gracePeriodMs, String operatorHint) {}

    record RevokeKeyResponse(String keyId, String status, long gracePeriodMs) {}

    /// `source` is [#SOURCE_CLUSTER] or [#SOURCE_CONFIG]. It is the field a client must read before
    /// acting on a record: only cluster-held keys can be revoked through this API.
    record KeyInfo(String keyId,
                   String status,
                   long createdAt,
                   long expiresAt,
                   long revokedAt,
                   long gracePeriodMs,
                   String authorizationRole,
                   String source) {}

    record AuditEntry(String keyId, String action, long timestamp, String operatorHint) {}

    record KeyNotFoundError(String keyId) implements Cause {
        @Override
        public String message() {
            return "API key not found: " + keyId;
        }
    }

    /// Distinct from [KeyNotFoundError] on purpose: the key EXISTS and the node accepts it. Saying
    /// "not found" would tell an operator the credential is gone when it is still live.
    record ConfigDeclaredKeyError(String keyId) implements Cause {
        @Override
        public String message() {
            return "API key '" + keyId
                 + "' is declared in node configuration, not in the cluster key store, "
                 + "and cannot be revoked through this API: the configuration file is its authority and the "
                 + "node cannot rewrite it. Remove the [app-http.api-keys.<key>] table (or the AETHER_API_KEYS "
                 + "entry) and restart the node. Keys created via POST /api/v1/cluster/keys carry "
                 + "source=\"cluster\" and are revocable here.";
        }
    }
}
