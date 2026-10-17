// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PlacementOperationPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// A movement drain closes admission first, then waits for the core to commit its completion.
/// The ordinary drain grace deadline remains the hard bound; expiry produces no success evidence.
public interface CommunityDrainCoordinator {
    Unit onRequest(CommunityPlacementMessage.DrainRequested request);
    Unit onAccepted(CommunityPlacementMessage.DrainAccepted response);
    Promise<Unit> onQuiesced();

    static CommunityDrainCoordinator communityDrainCoordinator(NodeId self,
                                                               Supplier<Option<NodeId>> leader,
                                                               Supplier<Option<CommunityPlacementOperationValue>> operation,
                                                               BiConsumer<NodeId, CommunityPlacementMessage> send,
                                                               Runnable initiate) {
        record Pending(String operationId, Promise<Unit> acknowledged) {}
        record coordinator(NodeId self,
                           Supplier<Option<NodeId>> leader,
                           Supplier<Option<CommunityPlacementOperationValue>> operation,
                           BiConsumer<NodeId, CommunityPlacementMessage> send,
                           Runnable initiate,
                           AtomicReference<Option<Pending>> pending,
                           AtomicBoolean quiesced) implements CommunityDrainCoordinator {
            @Override
            public Unit onRequest(CommunityPlacementMessage.DrainRequested request) {
                if (!leader.get().filter(request.sender()::equals).isPresent() || !operation.get()
                                                                                            .filter(value -> value.operationId()
                                                                                                                  .equals(request.operationId())
                                                                                                             && value.previousNode()
                                                                                                                     .filter(self::equals)
                                                                                                                     .isPresent()
                                                                                                             && value.phase() == PlacementOperationPhase.DRAIN_REQUESTED
                                                                                                             && value.issuer()
                                                                                                                     .leader()
                                                                                                                     .equals(request.sender()))
                                                                                            .isPresent()) {
                    return Unit.unit();
                }

                var next = Option.some(new Pending(request.operationId(), Promise.promise()));

                pending.compareAndSet(Option.none(), next);
                pending.get()
                       .filter(value -> value.operationId()
                                             .equals(request.operationId()))
                       .onPresent(this::startOrRepeat);

                return Unit.unit();
            }

            private void startOrRepeat(Pending entry) {
                if (quiesced.get()) {
                    sendCompletion(entry);
                } else {
                    initiate.run();
                }
            }

            private void sendCompletion(Pending entry) {
                leader.get()
                      .onPresent(target -> send.accept(target,
                                                       new CommunityPlacementMessage.DrainCompleted(self,
                                                                                                    entry.operationId())));
            }

            @Override
            public Promise<Unit> onQuiesced() {
                quiesced.set(true);

                return pending.get()
                              .fold(Promise::unitPromise, this::awaitCommit);
            }

            private Promise<Unit> awaitCommit(Pending entry) {
                sendCompletion(entry);

                return entry.acknowledged()
                            .timeout(TimeSpan.timeSpan(10).seconds());
            }

            @Override
            public Unit onAccepted(CommunityPlacementMessage.DrainAccepted response) {
                if (!response.accepted() || !leader.get().filter(response.sender()::equals).isPresent()) {
                    return Unit.unit();
                }

                pending.get()
                       .filter(entry -> entry.operationId()
                                             .equals(response.operationId()))
                       .onPresent(entry -> entry.acknowledged()
                                                .succeed(Unit.unit()));

                return Unit.unit();
            }
        }

        return new coordinator(self,
                               leader,
                               operation,
                               send,
                               initiate,
                               new AtomicReference<>(Option.none()),
                               new AtomicBoolean());
    }
}
