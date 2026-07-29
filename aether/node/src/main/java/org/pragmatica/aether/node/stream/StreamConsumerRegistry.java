// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistrationValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Cluster-wide view of every declarative `[streams.X]` consumer (#488).
///
/// Mirrors `ScheduledTaskRegistry`: it tracks `StreamRegistrationKey` puts/removes off the KV-Store
/// notification stream and notifies a single change listener, which [StreamConsumerManager] uses to
/// re-evaluate what this node should consume.
///
/// The registration is a cluster-wide DECLARATION, not a per-node assignment. `StreamRegistrationKey`
/// is `(streamName, configSection, artifact, methodName)` — deliberately NOT node-scoped — so every
/// node deploying the slice writes the same key and the `nodeId` carried in the value is simply the
/// last writer. Nothing may treat that `nodeId` as "the node that consumes this stream"; which node
/// consumes which partition is decided locally from partition ownership. See [StreamConsumerManager].
public interface StreamConsumerRegistry {
    @MessageReceiver
    @Contract
    void onStreamRegistrationPut(ValuePut<StreamRegistrationKey, StreamRegistrationValue> valuePut);

    @MessageReceiver
    @Contract
    void onStreamRegistrationRemove(ValueRemove<StreamRegistrationKey, StreamRegistrationValue> valueRemove);

    List<ConsumerDeclaration> allDeclarations();

    @Contract
    void setChangeListener(BiConsumer<StreamRegistrationKey, Option<ConsumerDeclaration>> listener);

    /// One declared consumer method. `consumerGroup` is synthesized at deploy time as
    /// `artifact.base() + "-" + methodName`, so every replica of a slice shares one group — which is
    /// what makes the per-partition cursor meaningful across an ownership change.
    record ConsumerDeclaration(String streamName,
                               String configSection,
                               Artifact artifact,
                               MethodName methodName,
                               String consumerGroup,
                               boolean batchMode,
                               String eventType) {}

    static StreamConsumerRegistry streamConsumerRegistry() {
        record streamConsumerRegistry(Map<StreamRegistrationKey, ConsumerDeclaration> declarations,
                                      AtomicReference<BiConsumer<StreamRegistrationKey, Option<ConsumerDeclaration>>> changeListener) implements StreamConsumerRegistry {
            private static final Logger log = LoggerFactory.getLogger(StreamConsumerRegistry.class);

            @Override
            @Contract
            public void onStreamRegistrationPut(ValuePut<StreamRegistrationKey, StreamRegistrationValue> valuePut) {
                var key = valuePut.cause().key();
                var value = valuePut.cause().value();
                var declaration = new ConsumerDeclaration(key.streamName(),
                                                          key.configSection(),
                                                          key.artifact(),
                                                          key.methodName(),
                                                          value.consumerGroup(),
                                                          value.batchMode(),
                                                          value.eventType());
                var previous = Option.option(declarations.put(key, declaration));

                log.debug("Registered declarative stream consumer: {}", declaration);
                notifyIfChanged(key, previous, declaration);
            }

            @Override
            @Contract
            public void onStreamRegistrationRemove(ValueRemove<StreamRegistrationKey, StreamRegistrationValue> valueRemove) {
                var key = valueRemove.cause().key();

                Option.option(declarations.remove(key)).onPresent(removed -> handleRemoved(key, removed));
            }

            private void handleRemoved(StreamRegistrationKey key, ConsumerDeclaration removed) {
                log.debug("Unregistered declarative stream consumer: {}", removed);
                notifyListener(key, Option.none());
            }

            @Override
            public List<ConsumerDeclaration> allDeclarations() {
                return List.copyOf(declarations.values());
            }

            @Override
            @Contract
            public void setChangeListener(BiConsumer<StreamRegistrationKey, Option<ConsumerDeclaration>> listener) {
                changeListener.set(listener);
            }

            private void notifyIfChanged(StreamRegistrationKey key,
                                         Option<ConsumerDeclaration> previous,
                                         ConsumerDeclaration current) {
                if (previous.map(prev -> declarationChanged(prev, current)).or(true)) {
                    notifyListener(key, Option.some(current));
                }
            }

            private boolean declarationChanged(ConsumerDeclaration previous, ConsumerDeclaration current) {
                return ! Objects.equals(previous.consumerGroup(), current.consumerGroup()) || previous.batchMode() != current.batchMode() || !Objects.equals(previous.eventType(),
                                                                                                                                                             current.eventType());
            }

            private void notifyListener(StreamRegistrationKey key, Option<ConsumerDeclaration> declaration) {
                Option.option(changeListener.get()).onPresent(listener -> listener.accept(key, declaration));
            }
        }

        return new streamConsumerRegistry(new ConcurrentHashMap<>(), new AtomicReference<>());
    }
}
