// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.http.routing.PathParameter.aString;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"}) public final class ApiKeyRoutes implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyRoutes.class);

    private static final TimeSpan SWEEP_INTERVAL = TimeSpan.timeSpan(60).seconds();

    private final Supplier<ManageableNode> nodeSupplier;
    private final ScheduledFuture<?> sweepTask;

    private ApiKeyRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
        this.sweepTask = SharedScheduler.scheduleAtFixedRate(this::sweepExpiredKeys, SWEEP_INTERVAL);
    }

    public static ApiKeyRoutes apiKeyRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ApiKeyRoutes(nodeSupplier);
    }

    @Override public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<Object>route(ManagementRoute.CLUSTER_KEYS_CREATE)
                                         .withBody(CreateKeyRequest.class)
                                         .toJson(this::handleCreateKey),
                         ManagementRoutes.<List<KeyInfo>>route(ManagementRoute.CLUSTER_KEYS_LIST)
                                         .to(_ -> handleListKeys())
                                         .asJson(),
                         ManagementRoutes.<Object>route(ManagementRoute.CLUSTER_KEYS_REVOKE)
                                         .withPath(aString())
                                         .withBody(RevokeKeyRequest.class)
                                         .toJson(this::handleRevokeKey),
                         ManagementRoutes.<List<AuditEntry>>route(ManagementRoute.CLUSTER_KEYS_AUDIT)
                                         .to(_ -> handleListAudit())
                                         .asJson());
    }

    @SuppressWarnings("unchecked") private Promise<Object> handleCreateKey(CreateKeyRequest request) {
        var keyValue = ApiKeyValue.apiKeyValue(request.keyId(), request.keyHash(), request.gracePeriodMs());
        var keyCommand = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ApiKeyKey.apiKeyKey(request.keyId()),
                                                                                  keyValue);
        var auditValue = ApiKeyAuditValue.apiKeyAuditValue(request.keyId(),
                                                           request.auditAction(),
                                                           request.operatorHint());
        var auditId = request.keyId() + "-" + System.currentTimeMillis();
        var auditCommand = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(ApiKeyAuditKey.apiKeyAuditKey(auditId),
                                                                                    auditValue);
        log.info("Creating API key entry: keyId={}, status=ACTIVE", request.keyId());
        return nodeSupplier.get().<Object>apply(List.of(keyCommand, auditCommand))
                               .map(_ -> (Object) new CreateKeyResponse(request.keyId(),
                                                                        "ACTIVE"));
    }

    private Promise<List<KeyInfo>> handleListKeys() {
        var node = nodeSupplier.get();
        var keys = new ArrayList<KeyInfo>();
        node.kvStore()
                    .forEach(ApiKeyKey.class,
                             ApiKeyValue.class,
                             (_, v) -> keys.add(new KeyInfo(v.keyId(),
                                                            v.status(),
                                                            v.createdAt(),
                                                            v.expiresAt(),
                                                            v.revokedAt(),
                                                            v.gracePeriodMs())));
        return Promise.success(List.copyOf(keys));
    }

    @SuppressWarnings("unchecked") private Promise<Object> handleRevokeKey(String keyId, RevokeKeyRequest request) {
        var node = nodeSupplier.get();
        var key = ApiKeyKey.apiKeyKey(keyId);
        var existing = node.kvStore().get(key);
        if (existing.isEmpty()) {return new KeyNotFoundError(keyId).promise();}
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
        return node.<Object>apply(List.of(keyCommand, auditCommand))
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

    @SuppressWarnings({"unchecked", "JBCT-EX-01"}) @Contract private void sweepExpiredKeys() {
        try {
            var node = nodeSupplier.get();
            if (!node.isLeader()) {return;}
            var now = System.currentTimeMillis();
            var commands = new ArrayList<KVCommand<AetherKey>>();
            node.kvStore()
                        .forEach(ApiKeyKey.class,
                                 ApiKeyValue.class,
                                 (key, v) -> {
                                     if (!v.isActive()) {return;}
                                     if (v.expiresAt() <= 0 || v.expiresAt() > now) {return;}
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
            if (!commands.isEmpty()) {node.apply(commands);}
        } catch (Exception e) {
            log.debug("API key expiration sweep skipped: {}", e.getMessage());
        }
    }

    record CreateKeyRequest(String keyId, String keyHash, long gracePeriodMs, String auditAction, String operatorHint){}

    record CreateKeyResponse(String keyId, String status){}

    record RevokeKeyRequest(boolean immediate, long gracePeriodMs, String operatorHint){}

    record RevokeKeyResponse(String keyId, String status, long gracePeriodMs){}

    record KeyInfo(String keyId, String status, long createdAt, long expiresAt, long revokedAt, long gracePeriodMs){}

    record AuditEntry(String keyId, String action, long timestamp, String operatorHint){}

    record KeyNotFoundError(String keyId) implements Cause {
        @Override public String message() {
            return "API key not found: " + keyId;
        }
    }
}
