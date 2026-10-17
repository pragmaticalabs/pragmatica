// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;


/// Bounded request correlation, accepting replies only from the core leader contacted.
public interface GovernorAuthorityClient {
    Promise<GovernorAuthorityMessage.Response> request(GovernorAuthorityMessage.Request request);

    @Contract
    void onResponse(GovernorAuthorityMessage.Response response);

    static GovernorAuthorityClient governorAuthorityClient(Supplier<Option<NodeId>> leader,
                                                           BiConsumer<NodeId, GovernorAuthorityMessage> send) {
        record Pending(NodeId target, String community, Promise<GovernorAuthorityMessage.Response> promise) {}
        record client(Supplier<Option<NodeId>> leader,
                      BiConsumer<NodeId, GovernorAuthorityMessage> send,
                      ConcurrentHashMap<Long, Pending> pending) implements GovernorAuthorityClient {
            @Override
            public Promise<GovernorAuthorityMessage.Response> request(GovernorAuthorityMessage.Request request) {
                return leader.get()
                             .map(target -> sendRequest(target, request))
                             .or(() -> Causes.cause("No committed core leader for governor request").promise());
            }

            private Promise<GovernorAuthorityMessage.Response> sendRequest(NodeId target,
                                                                           GovernorAuthorityMessage.Request request) {
                var promise = Promise.<GovernorAuthorityMessage.Response> promise();
                var entry = new Pending(target, request.communityId(), promise);

                if (pending.putIfAbsent(request.requestId(), entry) != null) {
                    return Causes.cause("Governor request already pending").promise();
                }

                send.accept(target, request);

                return promise.timeout(TimeSpan.timeSpan(10).seconds())
                              .onResultRun(() -> pending.remove(request.requestId(),
                                                                entry));
            }

            @Override
            @Contract
            public void onResponse(GovernorAuthorityMessage.Response response) {
                Option.option(pending.get(response.requestId()))
                      .filter(entry -> entry.target()
                                            .equals(response.sender()) && entry.community()
                                                                               .equals(response.communityId()))
                      .onPresent(entry -> entry.promise()
                                               .succeed(response));
            }
        }

        return new client(leader, send, new ConcurrentHashMap<>());
    }
}
