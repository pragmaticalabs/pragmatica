// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.swim.SwimMember;


/// Worker-side nomination and renewal. Only a core-committed response activates a governor.
public interface GovernorAnnouncer {
    AtomicLong REQUEST_IDS = new AtomicLong();
    TimeSpan DEFAULT_REANNOUNCE_INTERVAL = TimeSpan.timeSpan(30).seconds();
    String communityId();

    @Contract
    void start();

    @Contract
    void stop();

    @Contract
    void onMembershipChange(List<SwimMember> members);

    boolean isGovernor();
    Option<NodeId> currentGovernor();

    static GovernorAnnouncer governorAnnouncer(NodeId self,
                                               Supplier<String> community,
                                               Supplier<String> address,
                                               Supplier<Option<GovernorAnnouncementValue>> committed,
                                               BooleanSupplier eligible,
                                               Function<GovernorAuthorityMessage.Request, Promise<GovernorAuthorityMessage.Response>> request) {
        record announcer(NodeId self,
                         Supplier<String> community,
                         Supplier<String> address,
                         Supplier<Option<GovernorAnnouncementValue>> committed,
                         BooleanSupplier eligible,
                         Function<GovernorAuthorityMessage.Request, Promise<GovernorAuthorityMessage.Response>> request,
                         AtomicBoolean started,
                         AtomicBoolean pending,
                         AtomicLong sequence,
                         AtomicReference<List<SwimMember>> members,
                         AtomicReference<Option<GovernorAnnouncementValue>> granted,
                         CancellableTask timer) implements GovernorAnnouncer {
            @Override
            public String communityId() {
                return community.get();
            }

            @Override
            @Contract
            public void start() {
                if (started.compareAndSet(false, true)) {
                    timer.set(SharedScheduler.scheduleAtFixedRate(this::nominate, DEFAULT_REANNOUNCE_INTERVAL));
                }
            }

            @Override
            @Contract
            public void stop() {
                started.set(false);
                sequence.set(-1);
                granted.set(Option.none());
                timer.cancel();
            }

            @Override
            public boolean isGovernor() {
                return started.get()
                       && eligible.getAsBoolean()
                       && authority().filter(value -> !value.dissolved() && value.governorId()
                                                                                 .equals(self))
                                   .isPresent();
            }

            @Override
            public Option<NodeId> currentGovernor() {
                return authority().filter(value -> !value.dissolved())
                                .map(GovernorAnnouncementValue::governorId);
            }

            private Option<GovernorAnnouncementValue> authority() {
                var local = committed.get();
                var acknowledged = granted.get();

                return local.filter(value -> value.communityTerm() >= acknowledged.map(GovernorAnnouncementValue::communityTerm)
                                                                                  .or(-1L))
                            .orElse(acknowledged);
            }

            @Override
            @Contract
            public void onMembershipChange(List<SwimMember> alive) {
                members.set(List.copyOf(alive));
                nominate();
            }

            @Contract
            private void nominate() {
                if (!started.get() || !eligible.getAsBoolean() || pending.get()) {
                    return;
                }

                var election = GovernorElection.evaluateReadyNomination(self, members.get(), currentGovernor());

                if (! (election instanceof GovernorState.Governor) || !pending.compareAndSet(false, true)) {
                    return;
                }

                var id = REQUEST_IDS.incrementAndGet();

                sequence.set(id);
                var proposal = new GovernorAuthorityMessage.Request(self,
                                                                    community.get(),
                                                                    id,
                                                                    authority().map(GovernorAnnouncementValue::communityTerm)
                                                                             .or(0L),
                                                                    address.get());

                request.apply(proposal)
                       .onSuccess(response -> accept(id, response))
                       .onResultRun(() -> pending.set(false));
            }

            @Contract
            private void accept(long id, GovernorAuthorityMessage.Response response) {
                if (started.get() && sequence.get() == id && response.requestId() == id && response.communityId()
                                                                                                   .equals(community.get())) {
                    granted.set(response.authority());
                }
            }
        }

        return new announcer(self,
                             community,
                             address,
                             committed,
                             eligible,
                             request,
                             new AtomicBoolean(),
                             new AtomicBoolean(),
                             new AtomicLong(),
                             new AtomicReference<>(List.of()),
                             new AtomicReference<>(Option.none()),
                             CancellableTask.cancellableTask());
    }
}
