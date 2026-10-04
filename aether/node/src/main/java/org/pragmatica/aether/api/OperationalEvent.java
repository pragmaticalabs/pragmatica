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

    /// #1777 (owner rule): this node's DHT writes have been refused as stale by the replication-change fence for longer
    /// than the operator-attention bound, and it has not adopted the change ([ClusterEvent.DhtWriterStale]). Raised by the
    /// refused node itself, the subject.
    record DhtWriterStale(String nodeId, long fence, long since, long timestamp) implements OperationalEvent {
        public static DhtWriterStale dhtWriterStale(String nodeId, long fence, long since) {
            return new DhtWriterStale(nodeId, fence, since, System.currentTimeMillis());
        }
    }

    /// #1777: the stale writer adopted a newer replication change; its writes are stamped under it now
    /// ([ClusterEvent.DhtWriterStaleResolved]).
    record DhtWriterStaleResolved(String nodeId, long fence, long since, long timestamp) implements OperationalEvent {
        public static DhtWriterStaleResolved dhtWriterStaleResolved(String nodeId, long fence, long since) {
            return new DhtWriterStaleResolved(nodeId, fence, since, System.currentTimeMillis());
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
}
