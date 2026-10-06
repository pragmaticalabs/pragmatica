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
                                 String eventId,
                                 long timestamp) implements OperationalEvent {
        public static StreamFailoverRefused streamFailoverRefused(String stream,
                                                                  int partition,
                                                                  String owner,
                                                                  List<String> isr,
                                                                  List<String> live,
                                                                  String reason,
                                                                  String eventId) {
            return new StreamFailoverRefused(stream,
                                             partition,
                                             owner,
                                             isr,
                                             live,
                                             reason,
                                             eventId,
                                             System.currentTimeMillis());
        }
    }

    /// #1730: a refused stream partition has an owner again ([ClusterEvent.StreamFailoverResolved]).
    record StreamFailoverResolved(String stream,
                                  int partition,
                                  String owner,
                                  List<String> isr,
                                  List<String> live,
                                  String reason,
                                  String eventId,
                                  long timestamp) implements OperationalEvent {
        public static StreamFailoverResolved streamFailoverResolved(String stream,
                                                                    int partition,
                                                                    String owner,
                                                                    List<String> isr,
                                                                    List<String> live,
                                                                    String reason,
                                                                    String eventId) {
            return new StreamFailoverResolved(stream,
                                              partition,
                                              owner,
                                              isr,
                                              live,
                                              reason,
                                              eventId,
                                              System.currentTimeMillis());
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
                                 String eventId,
                                 long timestamp) implements OperationalEvent {
        public static StreamIsrBelowMinimum streamIsrBelowMinimum(String stream,
                                                                  int partition,
                                                                  String owner,
                                                                  List<String> isr,
                                                                  List<String> fenced,
                                                                  int confirmationFactor,
                                                                  String eventId) {
            return new StreamIsrBelowMinimum(stream,
                                             partition,
                                             owner,
                                             isr,
                                             fenced,
                                             confirmationFactor,
                                             eventId,
                                             System.currentTimeMillis());
        }
    }

    /// #1883: the in-sync set of a partition reached its confirmation factor again ([ClusterEvent.StreamIsrRestored]).
    record StreamIsrRestored(String stream,
                             int partition,
                             String owner,
                             List<String> isr,
                             List<String> fenced,
                             int confirmationFactor,
                             String eventId,
                             long timestamp) implements OperationalEvent {
        public static StreamIsrRestored streamIsrRestored(String stream,
                                                          int partition,
                                                          String owner,
                                                          List<String> isr,
                                                          List<String> fenced,
                                                          int confirmationFactor,
                                                          String eventId) {
            return new StreamIsrRestored(stream,
                                         partition,
                                         owner,
                                         isr,
                                         fenced,
                                         confirmationFactor,
                                         eventId,
                                         System.currentTimeMillis());
        }
    }

    /// #1883: a committed config lowered a running stream's confirmation factor, which is not applied online
    /// (durability only increases), so the stream keeps enforcing `effectiveConfirmationFactor`
    /// ([ClusterEvent.StreamConfigChangeNotApplied]). A point event: no resolved pair.
    record StreamConfigChangeNotApplied(String stream,
                                        int requestedConfirmationFactor,
                                        int effectiveConfirmationFactor,
                                        String reason,
                                        String eventId,
                                        long timestamp) implements OperationalEvent {
        public static StreamConfigChangeNotApplied streamConfigChangeNotApplied(String stream,
                                                                                int requestedConfirmationFactor,
                                                                                int effectiveConfirmationFactor,
                                                                                String reason,
                                                                                String eventId) {
            return new StreamConfigChangeNotApplied(stream,
                                                    requestedConfirmationFactor,
                                                    effectiveConfirmationFactor,
                                                    reason,
                                                    eventId,
                                                    System.currentTimeMillis());
        }
    }

    /// #1723: a scheduled task has a fire whose outcome is UNKNOWN (no response within the invocation timeout), so it is
    /// not known whether the work ran ([ClusterEvent.ScheduledTaskOutcomeUnknown]). `task` is
    /// `section/artifact/method`, `node` the per-node row of an ALL-mode task (empty otherwise), `fireAt` when the fire
    /// was recorded.
    record ScheduledTaskOutcomeUnknown(String task, String node, long fireAt, String eventId, long timestamp) implements OperationalEvent {
        public static ScheduledTaskOutcomeUnknown scheduledTaskOutcomeUnknown(String task,
                                                                              String node,
                                                                              long fireAt,
                                                                              String eventId) {
            return new ScheduledTaskOutcomeUnknown(task, node, fireAt, eventId, System.currentTimeMillis());
        }

        /// The key the aggregator pairs this event with its [ScheduledTaskOutcomeRestored] by.
        public String key() {
            return task + "@" + node;
        }
    }

    /// #1723: the last unknown fire of a scheduled task was answered late ([ClusterEvent.ScheduledTaskOutcomeRestored]).
    /// `outcome` is what that late answer said: `executed` or `failed`.
    record ScheduledTaskOutcomeRestored(String task, String node, String outcome, String eventId, long timestamp) implements OperationalEvent {
        public static ScheduledTaskOutcomeRestored scheduledTaskOutcomeRestored(String task,
                                                                                String node,
                                                                                String outcome,
                                                                                String eventId) {
            return new ScheduledTaskOutcomeRestored(task, node, outcome, eventId, System.currentTimeMillis());
        }

        public String key() {
            return task + "@" + node;
        }
    }
}
