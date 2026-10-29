// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;


/// The result of one `step`: the next model and the effects, in order (spec R-3.1.1). `dropped` marks an input the FSM
/// ignored (stale incarnation, dead token, wrong state); the model is then unchanged.
public record Transition(NodeFsmModel model, List<NodeFsmEffect> effects, boolean dropped) {
    public Transition {
        effects = List.copyOf(effects);
    }

    public static Transition transition(NodeFsmModel model) {
        return new Transition(model, List.of(), false);
    }

    /// The same transition, marked as an input the FSM ignored.
    public Transition ignoring() {
        return new Transition(model, effects, true);
    }

    public Transition emit(NodeFsmEffect effect) {
        var all = new ArrayList<>(effects);

        all.add(effect);

        return new Transition(model, all, dropped);
    }

    public Transition emitAll(List<NodeFsmEffect> more) {
        var all = new ArrayList<>(effects);

        all.addAll(more);

        return new Transition(model, all, dropped);
    }

    /// Applies `action` to each artifact in order, threading the transition through.
    public Transition each(List<Artifact> artifacts, Fn2<Transition, Transition, Artifact> action) {
        return artifacts.stream()
                        .reduce(this,
                                action::apply,
                                (left, right) -> right);
    }

    public Transition withModel(NodeFsmModel next) {
        return new Transition(next, effects, dropped);
    }

    /// Records whether the local store holds `artifact`, on top of whatever the transition has already changed.
    public Transition loaded(Artifact artifact, boolean present) {
        return withModel(model.withLoaded(artifact, present));
    }

    public InstanceRecord instance(Artifact artifact) {
        return model.instance(artifact);
    }

    public Transition update(Artifact artifact, Fn1<InstanceRecord, InstanceRecord> change) {
        return withModel(model.withInstance(artifact,
                                            change.apply(model.instance(artifact))));
    }

    public long incarnation() {
        return model.incarnation();
    }

    /// Runs `next` with a fresh token; the token is consumed from the model.
    public Transition issuing(Fn2<Transition, Transition, Long> next) {
        var token = model.nextToken();

        return next.apply(withModel(model.bumped()), token);
    }
}
