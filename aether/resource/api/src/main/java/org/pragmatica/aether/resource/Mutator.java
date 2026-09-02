// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource;

/// A pure `S -> S` state transition that can be NAMED, and therefore persisted and transferred.
///
/// ## Why this exists rather than [org.pragmatica.lang.Functions.Fn1]
/// A lambda has no name. The slice JAR is on every node, so the CODE a transition needs is already
/// cluster-wide — only the DATA identifying which transition to run has to travel. A record has a
/// name, its components ARE its arguments, and codec generation therefore makes transferability a
/// BUILD-TIME guarantee. No amount of discipline gets that from a lambda.
///
/// This is deliberately NOT `Fn1<S, S>`. `Fn1` carries `then`/`before`, which return a COMPOSED
/// LAMBDA typed as `Fn1` — not a record, so no generated codec; not in the implementor's sealed
/// hierarchy, so no tag and no wire identity. Inheriting those combinators would let
/// `a.then(b)` typecheck and produce something that looks like a transition and cannot cross a
/// boundary, on exactly the paths this type exists to make safe.
///
/// ## The type parameter is what keeps lambdas out
/// This interface has a single abstract method, so a lambda CAN target it. Nothing about the name
/// prevents that. What prevents it is that the durable APIs accept the implementor's own
/// `C extends Mutator<S>` — a SEALED interface whose variants are records — and a lambda cannot
/// implement a sealed interface. Never accept a raw `Mutator<S>` on a path whose value must survive
/// a restart or a hop; accept `C`.
///
/// It is also why `C` must be a TYPE PARAMETER rather than an incidental type: the slice processor's
/// `collectTypeArguments` walks every type argument of a resource-qualified parameter, so `C` is
/// collected for codec generation for free. A command type that is not a type argument is invisible
/// to it.
///
/// ## Shared on purpose
/// A durable entity update, a durable timer's `onFire`, and a journaled workflow step are the same
/// shape — a named transition applied to state, possibly later and possibly elsewhere. Persisting a
/// timer's action and forwarding an update to a partition owner are one problem wearing two hats.
///
/// ## Contract
/// Implementations MUST be pure: no IO, no observable side effects, and no dependence on anything
/// but the supplied state and the implementor's own components. The caller consumes the returned
/// state; a transition that reaches outside itself cannot be re-applied during recovery, which is
/// the operation every durable use of this type depends on.
public interface Mutator<S> {
    S apply(S state);
}
