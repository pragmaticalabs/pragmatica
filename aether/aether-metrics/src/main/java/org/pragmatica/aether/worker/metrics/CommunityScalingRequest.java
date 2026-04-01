package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

/// Request from a governor to the core cluster to scale a community.
/// Only sent when the CommunityScalingEvaluator detects a sustained threshold breach.
/// Zero traffic when community is healthy.
///
/// @param communityId       community identifier
/// @param governorId        governor node ID
/// @param artifact          the artifact to scale
/// @param direction         "UP" or "DOWN"
/// @param currentInstances  current instance count
/// @param requestedInstances desired instance count
/// @param evidence          scaling evidence from sliding window
@Codec public record CommunityScalingRequest( String communityId,
                                              NodeId governorId,
                                              Artifact artifact,
                                              String direction,
                                              int currentInstances,
                                              int requestedInstances,
                                              ScalingEvidence evidence) implements Message.Wired {
    /// Factory following JBCT naming convention.
    public static CommunityScalingRequest communityScalingRequest(String communityId,
                                                                  NodeId governorId,
                                                                  Artifact artifact,
                                                                  String direction,
                                                                  int currentInstances,
                                                                  int requestedInstances,
                                                                  ScalingEvidence evidence) {
        return new CommunityScalingRequest(communityId,
                                           governorId,
                                           artifact,
                                           direction,
                                           currentInstances,
                                           requestedInstances,
                                           evidence);
    }
}
