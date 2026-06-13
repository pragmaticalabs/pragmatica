// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.mutation;

import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
public interface MutationForwarder {
    Logger LOG = LoggerFactory.getLogger(MutationForwarder.class);
    void forward(WorkerMutation mutation);
    void onMutationFromFollower(WorkerMutation mutation);
    void updateGovernor(Option<NodeId> governor);

    static MutationForwarder mutationForwarder(NodeId selfId, PassiveNode<?, ?> passiveNode) {
        return mutationForwarder(selfId, passiveNode.delegateRouter());
    }

    static MutationForwarder mutationForwarder(NodeId selfId, DelegateRouter delegateRouter) {
        record mutationForwarder(NodeId selfId,
                                 DelegateRouter delegateRouter,
                                 AtomicReference<Option<NodeId>> currentGovernor) implements MutationForwarder {
            @Override
            public void forward(WorkerMutation mutation) {
                var governor = currentGovernor.get();

                if (governor.isEmpty() || isGovernor(governor)) {
                    forwardToCore(mutation);

                    return;
                }

                forwardToGovernor(mutation, governor.unwrap());
            }

            @Override
            public void onMutationFromFollower(WorkerMutation mutation) {
                forwardToCore(mutation);
            }

            @Override
            public void updateGovernor(Option<NodeId> governor) {
                currentGovernor.set(governor);
            }

            private boolean isGovernor(Option<NodeId> governor) {
                return governor.map(selfId::equals)
                               .or(false);
            }

            private void forwardToCore(WorkerMutation mutation) {
                delegateRouter.route(new NetworkServiceMessage.Broadcast(mutation));
                LOG.trace("Forwarded mutation {} to core cluster", mutation.correlationId());
            }

            private void forwardToGovernor(WorkerMutation mutation, NodeId governorId) {
                delegateRouter.route(new NetworkServiceMessage.Send(governorId, mutation));
                LOG.trace("Forwarded mutation {} to governor {}", mutation.correlationId(), governorId.id());
            }
        }

        return new mutationForwarder(selfId, delegateRouter, new AtomicReference<>(Option.empty()));
    }
}
