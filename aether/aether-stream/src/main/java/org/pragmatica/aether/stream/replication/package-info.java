// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

/// Stream partition replication: backfill, catch-up and owner activation.
///
/// The TLA+ model `Stalled` (at bcc2102db6) of the owner catch-up's all-stalled warning ([PartitionBackfill]) lives in
/// doc-comment fragments: this skeleton carries the state, the environment (cluster events raised by other components),
/// `Init`, `Next`, fairness and the properties; each action sits on the code that implements it.
/// `tools/tla/check.sh Stalled <variant> aether/aether-stream/src/main/java` extracts and checks it.
///
/// ```tla Stalled:head
/// EXTENDS Naturals
///
/// \* One partition of one stream on this node and one catch-up source. MAX is MAX_STALLED_ROUNDS. Env is the set
/// \* of environment events enabled in a run, EnvSteps bounds how many happen.
/// CONSTANTS MAX, EnvSteps, Env
///
/// VARIABLES
///     exists,      \* the stream exists on this node (destroy / recreate)
///     replica,     \* this node is a registered replica of the partition (ReplicaSetController)
///     owner,       \* this node is the partition's owner
///     quar,        \* the partition is quarantined
///     cnt,         \* the source's stalledRounds counter (nothing removes it when the stream is destroyed)
///     ep,          \* ghost: stalled rounds counted in this incarnation of the stream
///     reported,    \* reportedStalled holds the partition: the CRITICAL warning is open
///     rpc,         \* backfill round: "idle" | "read" | "report"
///     allStalled,  \* the round's chain ended SOURCE_STALLED
///     forget,      \* the stream-removed listener (clearStalledOf) is pending
///     env,         \* environment steps left
///     settled,     \* ghost: a reconcile call returned and nothing changed since
///     early        \* ghost: a raise with fewer than MAX stalled rounds in this incarnation
///
/// vars == <<exists, replica, owner, quar, cnt, ep, reported, rpc, allStalled, forget, env, settled, early>>
/// live == <<exists, replica, owner, quar>>
///
/// \* What backs the warning: the stream exists, self is a replica and the owner, not quarantined, stall >= MAX.
/// Backed == exists /\ replica /\ owner /\ ~quar /\ cnt >= MAX
///
/// \* -- Environment: cluster events from other components, each consuming one step of the budget --
/// Ev(name) == name \in Env /\ env > 0 /\ env' = env - 1 /\ settled' = FALSE
///
/// Destroy == /\ Ev("destroy")
///            /\ exists
///            /\ exists' = FALSE
///            /\ replica' = FALSE
///            /\ forget' = TRUE
///            /\ UNCHANGED <<owner, quar, cnt, ep, reported, rpc, allStalled, early>>
///
/// Recreate == /\ Ev("recreate")
///             /\ ~exists /\ ~forget
///             /\ exists' = TRUE
///             /\ replica' = TRUE
///             /\ ep' = 0
///             /\ UNCHANGED <<owner, quar, cnt, reported, rpc, allStalled, forget, early>>
///
/// \* ReplicaSetController.reconcilePartition -> registry.unregisterReplica(.., self)
/// RemoveSelf == /\ Ev("remove")
///               /\ replica
///               /\ replica' = FALSE
///               /\ UNCHANGED <<exists, owner, quar, cnt, ep, reported, rpc, allStalled, forget, early>>
///
/// AddSelf == /\ Ev("add")
///            /\ exists /\ ~replica
///            /\ replica' = TRUE
///            /\ UNCHANGED <<exists, owner, quar, cnt, ep, reported, rpc, allStalled, forget, early>>
///
/// OwnerFlip == /\ Ev("owner")
///              /\ owner' = ~owner
///              /\ UNCHANGED <<exists, replica, quar, cnt, ep, reported, rpc, allStalled, forget, early>>
///
/// QuarFlip == /\ Ev("quar")
///             /\ quar' = ~quar
///             /\ UNCHANGED <<exists, replica, owner, cnt, ep, reported, rpc, allStalled, forget, early>>
/// ```
///
/// ```tla Stalled:tail
/// Init == /\ exists = TRUE
///         /\ replica = TRUE
///         /\ owner = TRUE
///         /\ quar = FALSE
///         /\ cnt = 0
///         /\ ep = 0
///         /\ reported = FALSE
///         /\ rpc = "idle"
///         /\ allStalled = FALSE
///         /\ forget = FALSE
///         /\ env = EnvSteps
///         /\ settled = FALSE
///         /\ early = FALSE
///
/// EnvNext == Destroy \/ Recreate \/ RemoveSelf \/ AddSelf \/ OwnerFlip \/ QuarFlip
/// SysNext == R_Start \/ R_Read \/ R_Report \/ ClearStalledOf
/// Next == EnvNext \/ SysNext
///
/// \* Redrive ticks keep starting rounds of a candidate; a started step completes; the listener runs.
/// Fairness == WF_vars(R_Start) /\ WF_vars(R_Read) /\ WF_vars(R_Report) /\ WF_vars(ClearStalledOf)
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// \* -- Properties --
/// \* An unbacked warning eventually clears (or becomes backed again).
/// UnbackedClears == (reported /\ ~Backed) ~> (~reported \/ Backed)
///
/// \* After a reconcile call returned, and nothing changed since, an open warning is backed (vacuous here: no reconcile).
/// SettledIsBacked == settled /\ reported => Backed
///
/// \* Exploratory: a raise needs MAX stalled rounds in this incarnation of the stream.
/// NoEarlyRaise == ~early
/// ```
///
/// ```tlacfg Stalled:common
/// CONSTANTS MAX = 3
///           EnvSteps = 3
/// SPECIFICATION Spec
/// ```
///
/// ```tlacfg Stalled:live
/// CONSTANT Env = {"destroy", "recreate", "remove", "add", "owner", "quar"}
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:liveremove
/// CONSTANT Env = {"remove", "add", "owner", "quar"}
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:livedestroy
/// CONSTANT Env = {"destroy", "recreate"}
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:liveowner
/// CONSTANT Env = {"owner", "quar"}
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:early
/// CONSTANT Env = {"destroy", "recreate", "remove", "add", "owner", "quar"}
/// INVARIANT NoEarlyRaise
/// ```
package org.pragmatica.aether.stream.replication;
