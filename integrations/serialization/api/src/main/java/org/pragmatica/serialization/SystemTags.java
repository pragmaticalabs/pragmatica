/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.serialization;

import java.util.HashMap;
import java.util.Map;


/// The wire-tag registry for the SYSTEM range `0..16383` — every framework and Aether protocol type,
/// pinned to a number by hand.
///
/// ## Why a registry rather than a hash
/// A tag is a WIRE CONTRACT. Two nodes disagreeing on what tag 42 means is undiagnosable corruption,
/// not a clean failure, so a tag must be stable against everything that can change around it: a class
/// rename, a package move, a change of hash function. Hash derivation gives none of that, and its
/// collision behaviour already bit us — `AetherValue.EntityCheckpointValue` and `HealthHintWire` both
/// landed on 7612 under the old derivation, poisoning `NodeCodecs` static init and erroring 48
/// unrelated tests, invisibly to the owning module's own build.
///
/// System types are ENUMERABLE and framework-owned: they do not grow with user code, so hand
/// assignment is tractable. Slice-generated types are not, and stay hash-derived in the disjoint USER
/// range — see [SliceCodec#deterministicTag].
///
/// ## Why one file
/// Uniqueness across the whole space is the property that matters, and it is only reviewable when the
/// whole space is in one place. Scattering the numbers across per-type annotations would make a
/// duplicate invisible until two nodes disagreed about a payload. [#pin] and [#rejectDuplicateTags]
/// additionally reject a duplicate name or a duplicate tag at class-init, so the invariant does not
/// rest on review alone.
///
/// ## Rules
/// - **Never renumber. Never reuse.** A retired type's tag stays retired; take the next free slot.
/// - **A rename needs a deliberate re-key.** Renaming a class leaves its old key unmatched, the type
///   falls into the USER range, and [SliceCodec#systemCodec] fails the build naming it. That is the
///   intended behaviour: the human decides whether the wire identity moved with the name.
/// - **Blocks are advisory, numbers are not.** A subsystem that outgrows its block takes the next free
///   slot in the reserved tail rather than pushing its neighbours along.
///
/// ## The 1-byte window
/// Tags are VLQ-encoded, so `0..127` costs ONE byte on the wire, `128..16383` costs two. `0..20` are
/// the framework primitives (`SliceCodec.TAG_*`). `21..109` is spent on the cluster's own
/// highest-frequency traffic — consensus rounds, SWIM gossip, DHT lookups, KV commands, stream
/// replication, and the value objects nested inside all of them. `110..127` is deliberately left free
/// so a future hot type can still be promoted into one byte.
public interface SystemTags {
    /// Returned by [#tagFor] for a class name that has no hand-assigned tag.
    int NOT_PINNED = -1;

    Map<String, Integer> TAGS = table();

    /// The pinned tag for `className`, or [#NOT_PINNED] when the name is not a system type.
    ///
    /// The key is the name as the annotation processor spells it — dot-separated all the way down, so a
    /// nested type reads `a.b.Outer.Inner`, not `a.b.Outer$Inner`.
    static int tagFor(String className) {
        return TAGS.getOrDefault(className, NOT_PINNED);
    }

    private static void pin(Map<String, Integer> table, int tag, String className) {
        if (tag < 0 || tag > SliceCodec.SYSTEM_TAG_MAX) {
            throw new IllegalStateException("System tag %d for %s is outside the system range [0, %d]".formatted(tag,
                                                                                                                 className,
                                                                                                                 SliceCodec.SYSTEM_TAG_MAX));
        }

        var previousTag = table.put(className, tag);

        if (previousTag != null) {
            throw new IllegalStateException("%s is pinned twice, to %d and %d".formatted(className, previousTag, tag));
        }
    }

    /// Two names on one tag is the failure this whole file exists to prevent, and it is the one a
    /// per-entry check cannot see. Checked once over the finished table, where it IS visible.
    private static void rejectDuplicateTags(Map<String, Integer> table) {
        var byTag = new HashMap<Integer, String>(table.size() * 2);

        table.entrySet()
             .stream()
             .sorted(Map.Entry.comparingByKey())
             .forEach(entry -> {
                 var previousName = byTag.put(entry.getValue(), entry.getKey());

                 if (previousName != null) {
                     throw new IllegalStateException("Tag %d is pinned to both %s and %s".formatted(entry.getValue(),
                                                                                                    previousName,
                                                                                                    entry.getKey()));
                 }
             });
    }

    private static Map<String, Integer> table() {
        var table = new HashMap<String, Integer>(512);

        // consensus core  [base 21]
        pin(table, 21, "org.pragmatica.consensus.NodeId");
        pin(table, 22, "org.pragmatica.consensus.StateMachine.Batch");
        pin(table, 23, "org.pragmatica.consensus.StateMachine.Batch.Id");

        // Rabia consensus protocol  [base 24]
        pin(table, 24, "org.pragmatica.consensus.rabia.ClusterConfig");
        pin(table, 25, "org.pragmatica.consensus.rabia.CorrelationId");
        pin(table, 26, "org.pragmatica.consensus.rabia.Phase");
        pin(table, 27, "org.pragmatica.consensus.rabia.RabiaPersistence.SavedState");
        pin(table, 28, "org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch");
        pin(table, 29, "org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest");
        pin(table, 30, "org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision");
        pin(table, 31, "org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose");
        pin(table, 32, "org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse");
        pin(table, 33, "org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1");
        pin(table, 34, "org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2");
        pin(table, 35, "org.pragmatica.consensus.rabia.StateValue");

        // cluster network protocol  [base 36]
        pin(table, 36, "org.pragmatica.consensus.net.NetworkMessage.DiscoveredNodes");
        pin(table, 37, "org.pragmatica.consensus.net.NetworkMessage.DiscoverNodes");
        pin(table, 38, "org.pragmatica.consensus.net.NetworkMessage.Hello");
        pin(table, 39, "org.pragmatica.consensus.net.NetworkMessage.KeepAlive");
        pin(table, 40, "org.pragmatica.consensus.net.NetworkMessage.KVSyncRequest");
        pin(table, 41, "org.pragmatica.consensus.net.NetworkMessage.KVSyncResponse");
        pin(table, 42, "org.pragmatica.consensus.net.NodeInfo");

        // TCP transport  [base 43]
        pin(table, 43, "org.pragmatica.net.tcp.NodeAddress");

        // SWIM membership gossip  [base 44]
        pin(table, 44, "org.pragmatica.swim.SwimConfig");
        pin(table, 45, "org.pragmatica.swim.SwimMember");
        pin(table, 46, "org.pragmatica.swim.SwimMember.MemberState");
        pin(table, 47, "org.pragmatica.swim.SwimMessage.Ack");
        pin(table, 48, "org.pragmatica.swim.SwimMessage.Announce");
        pin(table, 49, "org.pragmatica.swim.SwimMessage.MembershipUpdate");
        pin(table, 50, "org.pragmatica.swim.SwimMessage.Ping");
        pin(table, 51, "org.pragmatica.swim.SwimMessage.PingReq");
        pin(table, 52, "org.pragmatica.swim.SwimMessage.WhoAmI");
        pin(table, 53, "org.pragmatica.swim.SwimMessage.WhoAmIReply");

        // DHT protocol  [base 54]
        pin(table, 54, "org.pragmatica.dht.DHTMessage.DigestRequest");
        pin(table, 55, "org.pragmatica.dht.DHTMessage.DigestResponse");
        pin(table, 56, "org.pragmatica.dht.DHTMessage.ExistsRequest");
        pin(table, 57, "org.pragmatica.dht.DHTMessage.ExistsResponse");
        pin(table, 58, "org.pragmatica.dht.DHTMessage.GetRequest");
        pin(table, 59, "org.pragmatica.dht.DHTMessage.GetResponse");
        pin(table, 60, "org.pragmatica.dht.DHTMessage.KeyValue");
        pin(table, 61, "org.pragmatica.dht.DHTMessage.MigrationDataAck");
        pin(table, 62, "org.pragmatica.dht.DHTMessage.MigrationDataRequest");
        pin(table, 63, "org.pragmatica.dht.DHTMessage.MigrationDataResponse");
        pin(table, 64, "org.pragmatica.dht.DHTMessage.PutRequest");
        pin(table, 65, "org.pragmatica.dht.DHTMessage.PutResponse");
        pin(table, 66, "org.pragmatica.dht.DHTMessage.RemoveRequest");
        pin(table, 67, "org.pragmatica.dht.DHTMessage.RemoveResponse");
        pin(table, 68, "org.pragmatica.dht.Partition");

        // replicated KV commands  [base 69]
        pin(table, 69, "org.pragmatica.cluster.state.kvstore.KVCommand.Get");
        pin(table, 70, "org.pragmatica.cluster.state.kvstore.KVCommand.Noop");
        pin(table, 71, "org.pragmatica.cluster.state.kvstore.KVCommand.Put");
        pin(table, 72, "org.pragmatica.cluster.state.kvstore.KVCommand.Remove");
        pin(table, 73, "org.pragmatica.cluster.state.kvstore.LeaderKey");
        pin(table, 74, "org.pragmatica.cluster.state.kvstore.LeaderValue");

        // cluster health + metrics gossip  [base 75]
        pin(table, 75, "org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing");
        pin(table, 76, "org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong");
        pin(table, 77, "org.pragmatica.cluster.metrics.CommunityReport");
        pin(table, 78, "org.pragmatica.cluster.metrics.ConnectivityState");
        pin(table, 79, "org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsEntry");
        pin(table, 80, "org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPing");
        pin(table, 81, "org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsPong");
        pin(table, 82, "org.pragmatica.cluster.metrics.HealthHintWire");
        pin(table, 83, "org.pragmatica.cluster.metrics.PeerConnectivityObservation");
        pin(table, 84, "org.pragmatica.cluster.metrics.PeerHealthObservation");

        // worker protocol  [base 85]
        pin(table, 85, "org.pragmatica.aether.worker.heartbeat.FollowerHeartbeat");
        pin(table, 86, "org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot");
        pin(table, 87, "org.pragmatica.aether.worker.metrics.PerMethodMetrics");
        pin(table, 88, "org.pragmatica.aether.worker.metrics.PerSliceMetrics");
        pin(table, 89, "org.pragmatica.aether.worker.mutation.WorkerMutation");
        pin(table, 90, "org.pragmatica.aether.worker.network.DHTRelayMessage");

        // stream replication and forwarding  [base 91]
        pin(table, 91, "org.pragmatica.aether.stream.consensus.StreamConsensusCommand");
        pin(table, 92, "org.pragmatica.aether.stream.forward.RawEventDto");
        pin(table, 93, "org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward");
        pin(table, 94, "org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse");
        pin(table, 95, "org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward");
        pin(table, 96, "org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse");
        pin(table, 97, "org.pragmatica.aether.stream.replication.ReplicationMessage.BatchSync");
        pin(table, 98, "org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupRequest");
        pin(table, 99, "org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse");
        pin(table, 100, "org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck");
        pin(table, 101, "org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents");

        // value objects nested in the above  [base 102]
        pin(table, 102, "java.net.InetSocketAddress");
        pin(table, 103, "org.pragmatica.aether.slice.MethodName");
        pin(table, 104, "org.pragmatica.lang.io.TimeSpan");
        pin(table, 105, "org.pragmatica.lang.vo.Email");
        pin(table, 106, "org.pragmatica.lang.vo.IsoDateTime");
        pin(table, 107, "org.pragmatica.lang.vo.NonBlankString");
        pin(table, 108, "org.pragmatica.lang.vo.Url");
        pin(table, 109, "org.pragmatica.lang.vo.Uuid");

        // durable pub-sub topic envelopes (#386)  [base 110] — TopicEventEnvelope heads the payload
        // bytes of EVERY durable-topic event (hot on merit); DlqEnvelope is failure-bounded traffic,
        // but the aether.stream.* hot-prefix contract (SystemCodecPinningTest) binds every type under
        // the prefix to the one-byte window, and relocating the class to dodge the contract was
        // ruled out as weakening it.
        pin(table, 110, "org.pragmatica.aether.stream.topic.TopicEventEnvelope");
        pin(table, 111, "org.pragmatica.aether.stream.topic.DlqEnvelope");

        // ---- 112..127 RESERVED: the last free 1-byte slots. Spend them on hot types only. ----
        // ---- 128..16383: two-byte system tags. ----

        // worker bootstrap (rare, large payloads)  [base 128]
        pin(table, 128, "org.pragmatica.aether.worker.bootstrap.SnapshotRequest");
        pin(table, 129, "org.pragmatica.aether.worker.bootstrap.SnapshotResponse");

        // artifact coordinates  [base 192]
        pin(table, 192, "org.pragmatica.aether.artifact.Artifact");
        pin(table, 193, "org.pragmatica.aether.artifact.ArtifactBase");
        pin(table, 194, "org.pragmatica.aether.artifact.ArtifactId");
        pin(table, 195, "org.pragmatica.aether.artifact.GroupId");
        pin(table, 196, "org.pragmatica.aether.artifact.Version");

        // cluster events  [base 256]
        pin(table, 256, "org.pragmatica.aether.api.ClusterEvent.AccessDenied");
        pin(table, 257, "org.pragmatica.aether.api.ClusterEvent.AlertInjected");
        pin(table, 258, "org.pragmatica.aether.api.ClusterEvent.BackupCreated");
        pin(table, 259, "org.pragmatica.aether.api.ClusterEvent.BackupRestored");
        pin(table, 260, "org.pragmatica.aether.api.ClusterEvent.BlueprintDeleted");
        pin(table, 261, "org.pragmatica.aether.api.ClusterEvent.BlueprintDeployed");
        pin(table, 262, "org.pragmatica.aether.api.ClusterEvent.CommunityMetricsSnapshot");
        pin(table, 263, "org.pragmatica.aether.api.ClusterEvent.CommunityScaleRequest");
        pin(table, 264, "org.pragmatica.aether.api.ClusterEvent.ConfigChanged");
        pin(table, 265, "org.pragmatica.aether.api.ClusterEvent.ConnectionEstablished");
        pin(table, 266, "org.pragmatica.aether.api.ClusterEvent.ConnectionFailed");
        pin(table, 267, "org.pragmatica.aether.api.ClusterEvent.DeparturePushIncomplete");
        pin(table, 268, "org.pragmatica.aether.api.ClusterEvent.DeploymentCompleted");
        pin(table, 269, "org.pragmatica.aether.api.ClusterEvent.DeploymentFailed");
        pin(table, 270, "org.pragmatica.aether.api.ClusterEvent.DeploymentStarted");
        pin(table, 271, "org.pragmatica.aether.api.ClusterEvent.GenerationChanged");
        pin(table, 272, "org.pragmatica.aether.api.ClusterEvent.LeaderElected");
        pin(table, 273, "org.pragmatica.aether.api.ClusterEvent.LeaderLost");
        pin(table, 274, "org.pragmatica.aether.api.ClusterEvent.NodeFailed");
        pin(table, 275, "org.pragmatica.aether.api.ClusterEvent.NodeJoined");
        pin(table, 276, "org.pragmatica.aether.api.ClusterEvent.NodeLeft");
        pin(table, 277, "org.pragmatica.aether.api.ClusterEvent.NodeLifecycleChanged");
        pin(table, 278, "org.pragmatica.aether.api.ClusterEvent.QuorumEstablished");
        pin(table, 279, "org.pragmatica.aether.api.ClusterEvent.QuorumLost");
        pin(table, 280, "org.pragmatica.aether.api.ClusterEvent.ScaleCapped");
        pin(table, 281, "org.pragmatica.aether.api.ClusterEvent.ScaleDown");
        pin(table, 282, "org.pragmatica.aether.api.ClusterEvent.ScaleUp");
        pin(table, 283, "org.pragmatica.aether.api.ClusterEvent.SelfDrainInitiated");
        pin(table, 284, "org.pragmatica.aether.api.ClusterEvent.Severity");
        pin(table, 285, "org.pragmatica.aether.api.ClusterEvent.SliceFailure");
        pin(table, 286, "org.pragmatica.aether.api.ClusterEvent.StreamDeleted");
        pin(table, 287, "org.pragmatica.aether.api.ClusterEvent.StreamMemoryExceeded");
        pin(table, 288, "org.pragmatica.aether.api.ClusterEvent.StreamRegistered");
        pin(table, 289, "org.pragmatica.aether.api.ClusterEvent.TraceInjected");

        // HTTP handling and forwarding  [base 512]
        pin(table, 512, "org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardRequest");
        pin(table, 513, "org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse");
        pin(table, 514, "org.pragmatica.aether.http.forward.HttpForwardMessage.Pipeline");
        pin(table, 515, "org.pragmatica.aether.http.handler.HttpRequestContext");
        pin(table, 516, "org.pragmatica.aether.http.handler.HttpResponseData");
        pin(table, 517, "org.pragmatica.aether.http.handler.security.AuthorizationRole");
        pin(table, 518, "org.pragmatica.aether.http.handler.security.Principal");
        pin(table, 519, "org.pragmatica.aether.http.handler.security.Role");
        pin(table, 520, "org.pragmatica.aether.http.handler.security.SecurityContext");

        // slice invocation  [base 640]
        // RETIRED 2026-08-27 (#571): `DHTNotification` was deleted — it had zero senders and zero
        // receivers. These two pins STAY, and deleting them would be the bug: the table is what makes
        // "never reuse" enforceable, so a freed tag could be silently reclaimed by a new type and two
        // node versions would then disagree about a payload on the wire. The names are unresolvable
        // today, which is harmless — `pin` records STRINGS, never class literals.
        //
        // Do not renumber around them, and do not "tidy" them away. Take the next free slot instead,
        // per this file's Rules section.
        pin(table, 640, "org.pragmatica.aether.dht.DHTNotification.Put");
        pin(table, 641, "org.pragmatica.aether.dht.DHTNotification.Removed");
        pin(table, 642, "org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest");
        pin(table, 643, "org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse");

        // slice core types  [base 704]
        pin(table, 704, "org.pragmatica.aether.slice.ConsistencyMode");
        pin(table, 705, "org.pragmatica.aether.slice.ExecutionMode");
        pin(table, 706, "org.pragmatica.aether.slice.RetentionMode");
        pin(table, 707, "org.pragmatica.aether.slice.RetentionPolicy");
        pin(table, 708, "org.pragmatica.aether.slice.SliceState");
        pin(table, 709, "org.pragmatica.aether.slice.StreamCompression");
        pin(table, 710, "org.pragmatica.aether.slice.StreamConfig");
        pin(table, 711, "org.pragmatica.aether.slice.TierAwareRetention");

        // generation and community topology  [base 832]
        pin(table, 832, "org.pragmatica.aether.slice.generation.ClusterMode");
        pin(table, 833, "org.pragmatica.aether.slice.generation.ClusterQuiescence");
        pin(table, 834, "org.pragmatica.aether.slice.generation.CommunityGenerationSnapshot");
        pin(table, 835, "org.pragmatica.aether.slice.generation.CommunityQuiescence");
        pin(table, 836, "org.pragmatica.aether.slice.generation.CommunitySummary");
        pin(table, 837, "org.pragmatica.aether.slice.generation.CoreMember");
        pin(table, 838, "org.pragmatica.aether.slice.generation.Epoch");
        pin(table, 839, "org.pragmatica.aether.slice.generation.GenerationReason");
        pin(table, 840, "org.pragmatica.aether.slice.generation.HealthHint");
        pin(table, 841, "org.pragmatica.aether.slice.generation.PartitionOwner");

        // blueprints  [base 960]
        pin(table, 960, "org.pragmatica.aether.slice.blueprint.Blueprint");
        pin(table, 961, "org.pragmatica.aether.slice.blueprint.BlueprintArtifact");
        pin(table, 962, "org.pragmatica.aether.slice.blueprint.BlueprintId");
        pin(table, 963, "org.pragmatica.aether.slice.blueprint.ExpandedBlueprint");
        pin(table, 964, "org.pragmatica.aether.slice.blueprint.MigrationEntry");
        pin(table, 965, "org.pragmatica.aether.slice.blueprint.ResolvedSlice");
        pin(table, 966, "org.pragmatica.aether.slice.blueprint.SecurityOverridePolicy");
        pin(table, 967, "org.pragmatica.aether.slice.blueprint.SecurityOverrides");
        pin(table, 968, "org.pragmatica.aether.slice.blueprint.SecurityOverrides.Entry");

        // AetherKey  [base 1088]
        pin(table, 1088, "org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey");
        pin(table, 1089, "org.pragmatica.aether.slice.kvstore.AetherKey.AbTestRoutingKey");
        pin(table, 1090, "org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey");
        pin(table, 1091, "org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey");
        pin(table, 1092, "org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyAuditKey");
        pin(table, 1093, "org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey");
        pin(table, 1094, "org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey");
        pin(table, 1095, "org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey");
        pin(table, 1096, "org.pragmatica.aether.slice.kvstore.AetherKey.CloudCredentialsKey");
        pin(table, 1097, "org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey");
        pin(table, 1098, "org.pragmatica.aether.slice.kvstore.AetherKey.ClusterPhaseKey");
        pin(table, 1099, "org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey");
        pin(table, 1100, "org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey");
        pin(table, 1101, "org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey");
        pin(table, 1102, "org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey");
        pin(table, 1103, "org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey");
        pin(table, 1104, "org.pragmatica.aether.slice.kvstore.AetherKey.DrainDeadlineKey");
        pin(table, 1105, "org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey");
        pin(table, 1106, "org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey");
        pin(table, 1107, "org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey");
        pin(table, 1108, "org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey");
        pin(table, 1109, "org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey");
        pin(table, 1110, "org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey");
        pin(table, 1111, "org.pragmatica.aether.slice.kvstore.AetherKey.JoinDeadlineKey");
        pin(table, 1112, "org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey");
        pin(table, 1113, "org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey");
        pin(table, 1114, "org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey");
        pin(table, 1115, "org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey");
        pin(table, 1116, "org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey");
        pin(table, 1117, "org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey");
        pin(table, 1118, "org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey");
        pin(table, 1119, "org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey");
        pin(table, 1120, "org.pragmatica.aether.slice.kvstore.AetherKey.SchemaMigrationLockKey");
        pin(table, 1121, "org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey");
        pin(table, 1122, "org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey");
        pin(table, 1123, "org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey");
        pin(table, 1124, "org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey");
        pin(table, 1125, "org.pragmatica.aether.slice.kvstore.AetherKey.StorageBlockKey");
        pin(table, 1126, "org.pragmatica.aether.slice.kvstore.AetherKey.StorageRefKey");
        pin(table, 1127, "org.pragmatica.aether.slice.kvstore.AetherKey.StorageStatusKey");
        pin(table, 1128, "org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey");
        pin(table, 1129, "org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey");
        pin(table, 1130, "org.pragmatica.aether.slice.kvstore.AetherKey.StreamMetadataKey");
        pin(table, 1131, "org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionAssignmentKey");
        pin(table, 1132, "org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey");
        pin(table, 1133, "org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey");
        pin(table, 1134, "org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey");
        pin(table, 1135, "org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey");
        pin(table, 1136, "org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey");
        pin(table, 1137, "org.pragmatica.aether.slice.kvstore.AetherKey.WorkerSliceDirectiveKey");

        // AetherValue  [base 1600]
        pin(table, 1600, "org.pragmatica.aether.slice.kvstore.AetherValue.AbTestRoutingValue");
        pin(table, 1601, "org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue");
        pin(table, 1602, "org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue");
        pin(table, 1603, "org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue");
        pin(table, 1604, "org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyAuditValue");
        pin(table, 1605, "org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue");
        pin(table, 1606, "org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue");
        pin(table, 1607, "org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue");
        pin(table, 1608, "org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue.NamedAddress");
        pin(table, 1609, "org.pragmatica.aether.slice.kvstore.AetherValue.CloudCredentialsValue");
        pin(table, 1610, "org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue");
        pin(table, 1611, "org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase");
        pin(table, 1612, "org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhaseValue");
        pin(table, 1613, "org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue");
        pin(table, 1614, "org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue");
        pin(table, 1615, "org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue");
        pin(table, 1616, "org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue");
        pin(table, 1617, "org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue");
        pin(table, 1618, "org.pragmatica.aether.slice.kvstore.AetherValue.DrainDeadlineValue");
        pin(table, 1619, "org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue");
        pin(table, 1620, "org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue");
        pin(table, 1621, "org.pragmatica.aether.slice.kvstore.AetherValue.EntityKeyspaceRegistrationValue");
        pin(table, 1622, "org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue");
        pin(table, 1623, "org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue");
        pin(table, 1624, "org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue");
        pin(table, 1625, "org.pragmatica.aether.slice.kvstore.AetherValue.JoinDeadlineValue");
        pin(table, 1626, "org.pragmatica.aether.slice.kvstore.AetherValue.LogLevelValue");
        pin(table, 1627, "org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue");
        pin(table, 1628, "org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue");
        pin(table, 1629, "org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry");
        pin(table, 1630, "org.pragmatica.aether.slice.kvstore.AetherValue.ObservabilityConfigValue");
        pin(table, 1631, "org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue");
        pin(table, 1632, "org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue");
        pin(table, 1633, "org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource");
        pin(table, 1634, "org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue");
        pin(table, 1635, "org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue");
        pin(table, 1636, "org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue");
        pin(table, 1637, "org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus");
        pin(table, 1638, "org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue");
        pin(table, 1639, "org.pragmatica.aether.slice.kvstore.AetherValue.SliceNodeValue");
        pin(table, 1640, "org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue");
        pin(table, 1641, "org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus");
        pin(table, 1642, "org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue");
        pin(table, 1643, "org.pragmatica.aether.slice.kvstore.AetherValue.StorageBlockValue");
        pin(table, 1644, "org.pragmatica.aether.slice.kvstore.AetherValue.StorageRefValue");
        pin(table, 1645, "org.pragmatica.aether.slice.kvstore.AetherValue.StorageStatusValue");
        pin(table, 1646, "org.pragmatica.aether.slice.kvstore.AetherValue.StorageStatusValue.TierStatus");
        pin(table, 1647, "org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue");
        pin(table, 1648, "org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue");
        pin(table, 1649, "org.pragmatica.aether.slice.kvstore.AetherValue.StreamMetadataValue");
        pin(table, 1650, "org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionAssignmentValue");
        pin(table, 1651, "org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionAssignmentValue.PartitionAssignment");
        pin(table, 1652, "org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue");
        pin(table, 1653, "org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue");
        pin(table, 1654, "org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryValue");
        pin(table, 1655, "org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue");
        pin(table, 1656, "org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry");
        pin(table, 1657, "org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue");
        pin(table, 1658, "org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue");
        pin(table, 1659, "org.pragmatica.aether.slice.kvstore.CommunityState");

        // entity owner-forwarding (#596)  [base 1660]
        pin(table, 1660, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForward");
        pin(table, 1661, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCreateForward");
        pin(table, 1662, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityDeleteForward");
        pin(table, 1663, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForwardResponse");

        // cluster command forwarding (#634 boot-guard catch — the pair was routed but never encodable)  [base 1664]
        pin(table, 1664, "org.pragmatica.cluster.node.forward.ForwardApplyRequest");
        pin(table, 1665, "org.pragmatica.cluster.node.forward.ForwardApplyResponse");

        // entity owner-forwarding, read half (#596)  [base 1666]
        pin(table, 1666, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityGetForward");
        pin(table, 1667, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityGetForwardResponse");

        // entity owner-forwarding, timer verbs (#345 I4)  [base 1668]
        // Cancel has no response of its own — it answers with EntityUpdateForwardResponse (1663) and an
        // empty state, exactly as delete does, so no tag is spent on a second Unit-shaped carrier.
        pin(table, 1668, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityScheduleTimerForward");
        pin(table, 1669, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityScheduleTimerForwardResponse");
        pin(table, 1670, "org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCancelTimerForward");

        // durable per-blueprint deployment outcome (#759 review BLOCKING 3 / #760 #724 review round
        // 2 item g) [base 1671] — the natural homes (AetherKey block 1088..1137, AetherValue block
        // 1600..1659) are both flush against the next block with no free slot, so per this file's
        // "blocks are advisory" rule these three take the next free slot after the highest pinned tag
        // instead of pushing every subsequent block's numbering along.
        pin(table, 1671, "org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey");
        pin(table, 1672, "org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus");
        pin(table, 1673, "org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue");

        // ---- 2112..16383 RESERVED ----
        rejectDuplicateTags(table);

        return Map.copyOf(table);
    }
}
