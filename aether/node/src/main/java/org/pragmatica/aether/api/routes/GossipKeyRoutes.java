// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.GossipKeyRotationResponse;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #683 — the producer for [GossipKeyRotationKey]. Every node already consumes the record
/// (`GossipKeyRotationHandler` → `RotatingGossipEncryptor.rotate`, idempotent, replayed to late
/// joiners), but nothing ever wrote it: the emergency, in-place rotation an operator reaches for after
/// a suspected `cluster_secret` or gossip-key leak could not be invoked. `POST /cluster/gossip-key/rotate`
/// (ADMIN, exact row) generates 32 random bytes and Puts them through consensus with the previous
/// record's key carried for the decrypt overlap, so peers mid-rotation keep understanding each other.
///
/// Two facts the operator must know, stated in SECURITY.md: the key material lives in the consensus
/// log and its snapshots, readable by any KV reader (accepted by the §5.8 design — the alternative,
/// a per-node out-of-band channel, would need a second trust root); and after the first rotation the
/// `cluster_secret`-derived key scheme is superseded on that cluster for good. The key bytes are never
/// logged — only the ids are.
public final class GossipKeyRoutes implements RouteSource {
    private static final Logger log = LoggerFactory.getLogger(GossipKeyRoutes.class);
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Supplier<ManageableNode> nodeSupplier;

    private GossipKeyRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static GossipKeyRoutes gossipKeyRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new GossipKeyRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<GossipKeyRotationResponse> route(ManagementRoute.CLUSTER_GOSSIP_KEY_ROTATE).toJson(_ -> rotate()));
    }

    /// One consensus Put: `currentKeyId = previous + 1`, fresh key, previous key and id carried.
    ///
    /// The Put is fenced ([GossipKeyRotationValue] is `VersionFenced`) and CONFIRMED. Both halves
    /// are needed: the fence makes a second concurrent rotation a refused write rather than a
    /// silent overwrite, and the confirmation is what lets this route tell the operator which
    /// happened — a fenced-out rejection is invisible in the apply result, because under batch
    /// merging every submitter receives the full merged result list. Reporting success for a
    /// rotation that did nothing would be worse than the race it closes, on the one path where the
    /// operator is responding to a suspected leak.
    ///
    /// The confirmation is SEMANTIC — "is the committed record the one I wrote" — not version
    /// arithmetic: two writers racing from the same base both derive the same `currentKeyId`, so
    /// the id cannot distinguish them. Only the key bytes can.
    @SuppressWarnings("unchecked")
    Promise<GossipKeyRotationResponse> rotate() {
        var node = nodeSupplier.get();
        var value = nextRotation(committedRotation(node));
        var command = (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(),
                                                                               value);

        log.info("Rotating gossip key: keyId={} (previous keyId={})", value.currentKeyId(), value.previousKeyId());

        return node.<Object> apply(List.of(command))
                   .flatMap(_ -> confirmLanded(node, value));
    }

    /// The engine runs the local `process` before resolving the apply promise, so a local re-read
    /// afterwards is authoritative for this batch.
    private static Promise<GossipKeyRotationResponse> confirmLanded(ManageableNode node,
                                                                    GossipKeyRotationValue intended) {
        return committedRotation(node).filter(committed -> committed.currentKey()
                                                                    .equals(intended.currentKey()))
                                      .map(GossipKeyRoutes::rotationResponse)
                                      .map(Promise::success)
                                      .or(() -> new GossipKeyRotationError.Superseded(intended.currentKeyId()).promise());
    }

    private static GossipKeyRotationResponse rotationResponse(GossipKeyRotationValue value) {
        return new GossipKeyRotationResponse(value.currentKeyId(),
                                             value.previousKeyId(),
                                             value.rotatedAt());
    }

    private static Option<GossipKeyRotationValue> committedRotation(ManageableNode node) {
        return node.kvStore()
                   .get(GossipKeyRotationKey.gossipKeyRotationKey())
                   .filter(value -> value instanceof GossipKeyRotationValue)
                   .map(value -> (GossipKeyRotationValue) value);
    }

    /// Carries the refused rotation's id ONLY — never key material, which must not reach an error
    /// message any more than a log line.
    sealed interface GossipKeyRotationError extends Cause {
        record Superseded(int attemptedKeyId) implements GossipKeyRotationError {
            @Override
            public String message() {
                return "Gossip key rotation " + attemptedKeyId
                       + " was superseded by a concurrent rotation and did not land; re-read the current key id and retry";
            }
        }
    }


    private static GossipKeyRotationValue nextRotation(Option<GossipKeyRotationValue> previous) {
        var key = new byte[KEY_BYTES];

        RANDOM.nextBytes(key);
        var encoded = Base64.getEncoder().encodeToString(key);

        return previous.map(prior -> GossipKeyRotationValue.gossipKeyRotationValue(prior.currentKeyId() + 1,
                                                                                   encoded,
                                                                                   prior.currentKeyId(),
                                                                                   prior.currentKey()))
                       .or(() -> GossipKeyRotationValue.gossipKeyRotationValue(1, encoded));
    }
}
