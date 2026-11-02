// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Write-ahead recording of the VMs one apply creates (cluster-apply spec 4.2). Every billed VM has a ledger row at or
/// before the moment it can exist: `intend` is written BEFORE the provider is called (a failed write means no call),
/// `created` after it answered, and a terminate is preceded by `terminating`. Each method writes the ledger durably and
/// returns the failure of that write; the in-memory view advances only when the write succeeded.
///
/// One recorder per apply; methods are serialised, so the node-by-node provisioning of an action and a future beat
/// thread see one consistent ledger.
@SuppressWarnings("JBCT-UTIL-02")
public final class ApplyRecorder {
    /// The steps of the write discipline a test can be killed at (spec 4.2): S0 action started, S1 intent written,
    /// S2 the provider has answered and nothing is written yet, S3 created written, S4 joined written, S5 action done,
    /// S6 terminating written, S7 terminated written.
    public enum Step {
        S0,
        S1,
        S2,
        S3,
        S4,
        S5,
        S6,
        S7
    }

    /// The seam a test installs to die at a step. Called after the step's write (S2: after the provider's answer).
    static Consumer<Step> stepHook = _ -> {};

    private ApplyState state;
    private String actionKey = "";

    private ApplyRecorder(ApplyState state) {
        this.state = state;
    }

    static ApplyRecorder applyRecorder(ApplyState initial) {
        return new ApplyRecorder(initial);
    }

    synchronized String applyId() {
        return state.applyId();
    }

    synchronized ApplyState snapshot() {
        return state;
    }

    /// S0: the action starts. Later rows are recorded under its key.
    synchronized Result<Unit> beginAction(String key) {
        actionKey = key;

        return commit(state.withActionStatus(key, ApplyAction.Status.IN_PROGRESS), Step.S0);
    }

    /// S5: every node of the action is recorded.
    synchronized Result<Unit> finishAction() {
        return commit(state.withActionStatus(actionKey, ApplyAction.Status.DONE), Step.S5);
    }

    /// S1: BEFORE the provider create. A failure here means the create must not be issued.
    synchronized Result<Unit> intend(String nodeId, String provider) {
        return actionKey.isEmpty()
               ? new NoAction(nodeId).result()
               : commit(state.withNode(ApplyResource.applyResource(actionKey,
                                                                   nodeId,
                                                                   provider,
                                                                   Option.none(),
                                                                   ApplyResource.State.INTENDED)),
                        Step.S1);
    }

    /// Nodes of `key` that exist and are accounted for: what a resumed action does not create again.
    synchronized int recordedFor(String key) {
        return (int) state.resources()
                          .stream()
                          .filter(row -> row.actionKey()
                                            .equals(key))
                          .filter(row -> row.state() == ApplyResource.State.CREATED || row.state() == ApplyResource.State.JOINED || row.state() == ApplyResource.State.ADOPTED)
                          .count();
    }

    /// Nodes of the current action whose create the resume lookup found never to have happened (ABSENT): the ids a resumed action
    /// issues again. Never a CREATED, JOINED, GONE or TERMINATED row.
    synchronized List<String> absentNodeIds() {
        return state.resources()
                    .stream()
                    .filter(row -> row.actionKey()
                                      .equals(actionKey))
                    .filter(row -> row.state() == ApplyResource.State.ABSENT)
                    .map(ApplyResource::nodeId)
                    .toList();
    }

    synchronized ApplyAction.Status statusOf(String key) {
        return state.actions()
                    .stream()
                    .filter(action -> action.key()
                                            .equals(key))
                    .map(ApplyAction::status)
                    .findFirst()
                    .orElse(ApplyAction.Status.PENDING);
    }

    /// The resume lookup found the node `nodeId` the intent row was written for: the row becomes CREATED.
    synchronized Result<Unit> found(String nodeId, String serverId) {
        return transition(nodeId, Option.some(serverId), ApplyResource.State.CREATED, Step.S3);
    }

    /// The resume lookup found nothing on every listing of the window: the node was never created.
    synchronized Result<Unit> absent(String nodeId) {
        return transition(nodeId, Option.none(), ApplyResource.State.ABSENT, Step.S3);
    }

    /// The audit found a ledger VM the provider no longer lists.
    synchronized Result<Unit> gone(String nodeId) {
        return transition(nodeId, Option.none(), ApplyResource.State.GONE, Step.S7);
    }

    /// The audit found a VM under the apply label the ledger has no row for: it is recorded, never ignored.
    synchronized Result<Unit> adopt(String nodeId, String provider, String serverId) {
        return commit(state.withNode(ApplyResource.applyResource("(adopted by audit)",
                                                                 nodeId,
                                                                 provider,
                                                                 Option.some(serverId),
                                                                 ApplyResource.State.ADOPTED)),
                      Step.S3);
    }

    synchronized Result<Unit> phase(ApplyPhase phase) {
        return commit(state.withPhase(phase), Step.S0);
    }

    /// S2: the provider has answered and nothing is written yet. A seam only.
    Unit providerAnswered() {
        stepHook.accept(Step.S2);

        return Unit.unit();
    }

    /// S3: AFTER the provider answered with `serverId`.
    synchronized Result<Unit> created(String nodeId, String serverId) {
        return transition(nodeId, Option.some(serverId), ApplyResource.State.CREATED, Step.S3);
    }

    /// S6: BEFORE a terminate call; no terminate is issued when this write fails.
    synchronized Result<Unit> terminating(String nodeId) {
        return transition(nodeId, Option.none(), ApplyResource.State.TERMINATING, Step.S6);
    }

    /// S7: after the provider confirmed the terminate.
    synchronized Result<Unit> terminated(String nodeId) {
        return transition(nodeId, Option.none(), ApplyResource.State.TERMINATED, Step.S7);
    }

    /// S7, failure side: the terminate failed, the VM is still billed and stays in the ledger as such.
    synchronized Result<Unit> terminateFailed(String nodeId) {
        return transition(nodeId, Option.none(), ApplyResource.State.TERMINATE_FAILED, Step.S7);
    }

    /// S7, failure side for a VM whose id only the provider's answer carried (the CREATED row was never written): the row
    /// keeps the server id, so the billed VM survives the loss of the operator's terminal.
    synchronized Result<Unit> terminateFailed(String nodeId, String serverId) {
        return transition(nodeId, Option.some(serverId), ApplyResource.State.TERMINATE_FAILED, Step.S7);
    }

    private Result<Unit> transition(String nodeId, Option<String> serverId, ApplyResource.State to, Step step) {
        return state.resources()
                    .stream()
                    .filter(row -> row.nodeId()
                                      .equals(nodeId))
                    .findFirst()
                    .map(row -> commit(state.withNode(ApplyResource.applyResource(row.actionKey(),
                                                                                  row.nodeId(),
                                                                                  row.provider(),
                                                                                  serverId.orElse(() -> row.serverId()),
                                                                                  to)),
                                       step))
                    .orElseGet(() -> new NoRow(nodeId, to).result());
    }

    private Result<Unit> commit(ApplyState next, Step step) {
        return ApplyState.save(next).onSuccess(_ -> advance(next, step));
    }

    @Contract
    private void advance(ApplyState next, Step step) {
        state = next;
        stepHook.accept(step);
    }

    /// A create asked for before any action started: a row without an action cannot be read back, so no create is issued.
    record NoAction(String nodeId) implements org.pragmatica.lang.Cause {
        @Override
        public String message() {
            return "Apply ledger has no started action to record node " + nodeId + " under; nothing was created";
        }
    }

    /// A transition for a node the ledger has no row for: a programming error, reported rather than written.
    record NoRow(String nodeId, ApplyResource.State to) implements org.pragmatica.lang.Cause {
        @Override
        public String message() {
            return "Apply ledger has no row for node " + nodeId + " to move to " + to;
        }
    }
}
