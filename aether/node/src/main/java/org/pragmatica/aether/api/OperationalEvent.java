// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

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

    /// #1777 (CTO ruling R1b, owner rule): a live DHT replication change has stayed unsettled for longer than the
    /// operator-attention bound, so every node still reads and writes at the stricter transitional quorums
    /// ([ClusterEvent.DhtReplicationUnsettled]). Derived from the committed change record on every node; published at most once.
    record DhtReplicationUnsettled(long changeVersion,
                                   int replicationFactor,
                                   int confirmationFactor,
                                   String stage,
                                   long since,
                                   String reason,
                                   long timestamp) implements OperationalEvent {
        public static DhtReplicationUnsettled dhtReplicationUnsettled(long changeVersion,
                                                                      int replicationFactor,
                                                                      int confirmationFactor,
                                                                      String stage,
                                                                      long since,
                                                                      String reason) {
            return new DhtReplicationUnsettled(changeVersion,
                                               replicationFactor,
                                               confirmationFactor,
                                               stage,
                                               since,
                                               reason,
                                               System.currentTimeMillis());
        }
    }

    /// #1777: an overdue DHT replication change left the condition — it settled, or a newer change superseded it
    /// ([ClusterEvent.DhtReplicationSettled]).
    record DhtReplicationSettled(long changeVersion,
                                 int replicationFactor,
                                 int confirmationFactor,
                                 long since,
                                 String reason,
                                 long timestamp) implements OperationalEvent {
        public static DhtReplicationSettled dhtReplicationSettled(long changeVersion,
                                                                  int replicationFactor,
                                                                  int confirmationFactor,
                                                                  long since,
                                                                  String reason) {
            return new DhtReplicationSettled(changeVersion,
                                             replicationFactor,
                                             confirmationFactor,
                                             since,
                                             reason,
                                             System.currentTimeMillis());
        }
    }
}
