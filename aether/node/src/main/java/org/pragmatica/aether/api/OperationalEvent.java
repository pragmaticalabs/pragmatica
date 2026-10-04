// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;

import org.pragmatica.messaging.Message;


public sealed interface OperationalEvent extends Message.Local {
    record AccessDenied(String principal,
                        String method,
                        String path,
                        String actualRole,
                        String requiredRole,
                        long timestamp) implements OperationalEvent {
        public static AccessDenied accessDenied(String principal,
                                                String method,
                                                String path,
                                                String actualRole,
                                                String requiredRole) {
            return new AccessDenied(principal, method, path, actualRole, requiredRole, System.currentTimeMillis());
        }
    }

    record NodeLifecycleChanged(String nodeId, String transition, String requestedBy, long timestamp) implements OperationalEvent {
        public static NodeLifecycleChanged nodeLifecycleChanged(String nodeId, String transition, String requestedBy) {
            return new NodeLifecycleChanged(nodeId, transition, requestedBy, System.currentTimeMillis());
        }
    }

    record ConfigChanged(String key, String scope, String action, String requestedBy, long timestamp) implements OperationalEvent {
        public static ConfigChanged configChanged(String key, String scope, String action, String requestedBy) {
            return new ConfigChanged(key, scope, action, requestedBy, System.currentTimeMillis());
        }
    }

    record BlueprintDeployed(String artifactCoords, String requestedBy, long timestamp) implements OperationalEvent {
        public static BlueprintDeployed blueprintDeployed(String artifactCoords, String requestedBy) {
            return new BlueprintDeployed(artifactCoords, requestedBy, System.currentTimeMillis());
        }
    }

    record BlueprintDeleted(String artifactId, String requestedBy, long timestamp) implements OperationalEvent {
        public static BlueprintDeleted blueprintDeleted(String artifactId, String requestedBy) {
            return new BlueprintDeleted(artifactId, requestedBy, System.currentTimeMillis());
        }
    }

    /// #1730 (owner ruling): failover refused for a stream partition — its owner is dead and no in-sync replica is
    /// live. Raised once per committed refusal by the leader that committed it ([ClusterEvent.StreamFailoverRefused]).
    record StreamFailoverRefused(String stream,
                                 int partition,
                                 String owner,
                                 List<String> isr,
                                 List<String> live,
                                 String reason,
                                 long timestamp) implements OperationalEvent {
        public static StreamFailoverRefused streamFailoverRefused(String stream,
                                                                  int partition,
                                                                  String owner,
                                                                  List<String> isr,
                                                                  List<String> live,
                                                                  String reason) {
            return new StreamFailoverRefused(stream, partition, owner, isr, live, reason, System.currentTimeMillis());
        }
    }

    /// #1730: a refused stream partition has an owner again ([ClusterEvent.StreamFailoverResolved]).
    record StreamFailoverResolved(String stream,
                                  int partition,
                                  String owner,
                                  List<String> isr,
                                  List<String> live,
                                  String reason,
                                  long timestamp) implements OperationalEvent {
        public static StreamFailoverResolved streamFailoverResolved(String stream,
                                                                    int partition,
                                                                    String owner,
                                                                    List<String> isr,
                                                                    List<String> live,
                                                                    String reason) {
            return new StreamFailoverResolved(stream, partition, owner, isr, live, reason, System.currentTimeMillis());
        }
    }

    /// #1883: a stream partition's committed in-sync set fell below its confirmation factor, so every acknowledged
    /// publish is refused (`NOT_ENOUGH_REPLICAS`) until a replica rejoins ([ClusterEvent.StreamIsrBelowMinimum]).
    /// `fenced` is the committed set of members the leader keeps out for liveness.
    record StreamIsrBelowMinimum(String stream,
                                 int partition,
                                 String owner,
                                 List<String> isr,
                                 List<String> fenced,
                                 int confirmationFactor,
                                 long timestamp) implements OperationalEvent {
        public static StreamIsrBelowMinimum streamIsrBelowMinimum(String stream,
                                                                  int partition,
                                                                  String owner,
                                                                  List<String> isr,
                                                                  List<String> fenced,
                                                                  int confirmationFactor) {
            return new StreamIsrBelowMinimum(stream,
                                             partition,
                                             owner,
                                             isr,
                                             fenced,
                                             confirmationFactor,
                                             System.currentTimeMillis());
        }
    }

    /// #1873: a stream partition's owner began a new epoch of the SAME owner (its ring was rebuilt: a restart without a WAL, a
    /// lazy re-materialize, a re-created stream), so consumers that read the old epoch past `startOffset` are told to re-read
    /// from it ([ClusterEvent.StreamLineageRestarted]). A fact about the committed record, not a loss: the owner may have pulled
    /// every record back from replicas.
    record StreamLineageRestarted(String stream,
                                  int partition,
                                  String owner,
                                  String oldEpoch,
                                  String newEpoch,
                                  long startOffset,
                                  long timestamp) implements OperationalEvent {
        public static StreamLineageRestarted streamLineageRestarted(String stream,
                                                                    int partition,
                                                                    String owner,
                                                                    String oldEpoch,
                                                                    String newEpoch,
                                                                    long startOffset) {
            return new StreamLineageRestarted(stream, partition, owner, oldEpoch, newEpoch, startOffset, System.currentTimeMillis());
        }
    }

    /// #1883: the in-sync set of a partition reached its confirmation factor again ([ClusterEvent.StreamIsrRestored]).
    record StreamIsrRestored(String stream,
                             int partition,
                             String owner,
                             List<String> isr,
                             List<String> fenced,
                             int confirmationFactor,
                             long timestamp) implements OperationalEvent {
        public static StreamIsrRestored streamIsrRestored(String stream,
                                                          int partition,
                                                          String owner,
                                                          List<String> isr,
                                                          List<String> fenced,
                                                          int confirmationFactor) {
            return new StreamIsrRestored(stream,
                                         partition,
                                         owner,
                                         isr,
                                         fenced,
                                         confirmationFactor,
                                         System.currentTimeMillis());
        }
    }
}
