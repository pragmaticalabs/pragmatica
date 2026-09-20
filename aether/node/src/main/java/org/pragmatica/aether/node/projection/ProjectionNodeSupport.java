// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.List;
import java.util.function.LongSupplier;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.node.projection.ProjectionRegistry.Registration;
import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter.CommittedAssignments;
import org.pragmatica.aether.resource.projection.Projection;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The node collaborators a `ProjectionRuntime` needs, registered on the SPI provider as ONE extension
/// (#1333) beside the entity drivers: the registry the cursor hook and the routes read, and the
/// factory for the node's [NodeReplayCursor]. One extension rather than five so a bare provisioning
/// context (unit tests, a minimal runtime) is missing one thing with one name.
public interface ProjectionNodeSupport {
    ProjectionRegistry registry();
    /// Cursor reports the hosted projections refused or threw on, node-wide, since boot
    /// ([ProjectionAwareCursorStore]).
    long cursorReportFailures();

    /// Attach: register, then hand back the projection wired with this node's cursor. The group id the
    /// cursor rewinds is resolved lazily through the registry, so the subscription need not be visible yet.
    <S, T> Result<Projection<S, T>> attach(ArtifactBase slice,
                                           String topicStream,
                                           Option<String> method,
                                           Projection<S, T> projection);

    static ProjectionNodeSupport projectionNodeSupport(ProjectionRegistry registry,
                                                       LongSupplier cursorReportFailures,
                                                       Fn1<Option<Integer>, String> partitionCount,
                                                       PartitionBounds bounds,
                                                       Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> commandWriter,
                                                       Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                                                       CommittedAssignments committedAssignments) {
        record projectionNodeSupport(ProjectionRegistry registry,
                                     LongSupplier reportFailures,
                                     Fn1<Option<Integer>, String> partitionCount,
                                     PartitionBounds bounds,
                                     Fn1<Promise<Unit>, List<KVCommand<AetherKey>>> commandWriter,
                                     Fn1<Option<StreamCursorCheckpointValue>, StreamCursorCheckpointKey> committedReader,
                                     CommittedAssignments committedAssignments) implements ProjectionNodeSupport {
            @Override
            public long cursorReportFailures() {
                return reportFailures.getAsLong();
            }

            @Override
            public <S, T> Result<Projection<S, T>> attach(ArtifactBase slice,
                                                          String topicStream,
                                                          Option<String> method,
                                                          Projection<S, T> projection) {
                var wired = projection.withReplayCursor(new NodeReplayCursor(topicStream,
                                                                             () -> registry.groupIdOf(slice,
                                                                                                      topicStream,
                                                                                                      method),
                                                                             () -> partitionCount.apply(topicStream),
                                                                             bounds,
                                                                             commandWriter,
                                                                             committedReader,
                                                                             committedAssignments));

                return registry.register(new Registration(slice,
                                                          topicStream,
                                                          method,
                                                          ProjectionHandle.projectionHandle(wired)))
                               .map(_ -> wired);
            }
        }

        return new projectionNodeSupport(registry,
                                         cursorReportFailures,
                                         partitionCount,
                                         bounds,
                                         commandWriter,
                                         committedReader,
                                         committedAssignments);
    }
}
