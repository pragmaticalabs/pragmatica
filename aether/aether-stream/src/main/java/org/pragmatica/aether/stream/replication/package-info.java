// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
/// Stream partition replication: backfill, catch-up and owner activation.
///
/// The TLA+ model `Stalled` (level-triggered, #2967) of the owner catch-up's all-stalled warning ([PartitionBackfill]) lives in
/// doc-comment fragments: this skeleton carries the state, the environment (cluster events raised by other components),
/// `Init`, `Next`, fairness and the properties; each action sits on the code that implements it.
/// `tools/tla/check.sh Stalled <variant> aether/aether-stream/src/main/java` extracts and checks one config;
/// `tools/tla/run.sh` runs every config with its expected verdict (`tools/tla/models.tsv`). Results are bounds, not
/// proofs: a passing config says no violation exists within its constants.
///
/// ```tla Stalled:head
/// EXTENDS Naturals
///
/// \* One partition of one stream on this node and one catch-up source. MAX is MAX_STALLED_ROUNDS. Env is the set
/// \* of environment events enabled in a run, EnvSteps bounds how many happen.
/// \* Fixed: the fixes the modelled code carries (the code as it stands carries AllFixes). A config that drops one
/// \* models the earlier version and must fail:
/// \*   level - the warning is level-triggered: every round and reconcileStalled re-check what backs it, and a raise
/// \*           needs the stream to exist (bcc2102db6 was edge-triggered: v2967b F1, F2)
/// \*   reset - the stream-removed listener drops the stream's stall counters and a stream that no longer exists
/// \*           counts no stalled round, so the counter belongs to one incarnation (#3156)
/// \*   recheck - a reconcile pass also re-derives the stream, replica and counter backing of every open report
/// CONSTANTS MAX, EnvSteps, Env, Fixed
///
/// AllFixes == {"level", "reset", "recheck"}
/// Fix(f) == f \in Fixed
/// ASSUME Fixed \subseteq AllFixes
///
/// VARIABLES
///     exists,      \* the stream exists on this node (destroy / recreate)
///     replica,     \* this node is a registered replica of the partition (ReplicaSetController)
///     owner,       \* this node is the partition's owner
///     quar,        \* the partition is quarantined
///     cnt,         \* the source's stalledRounds counter (dropped with the stream's reports under the fix "reset")
///     ep,          \* ghost: stalled rounds counted in this incarnation of the stream
///     reported,    \* reportedStalled holds the partition: the CRITICAL warning is open
///     rpc,         \* backfill round: "idle" | "read" | "report"
///     allStalled,  \* the round's chain ended SOURCE_STALLED
///     forget,      \* the stream-removed listener (clearStalledOf) is pending
///     env,         \* environment steps left
///     settled,     \* ghost: a reconcile pass completed with nothing changed since its snapshot, and nothing since
///     early,       \* ghost: a raise with fewer than MAX stalled rounds in this incarnation
///     rec,         \* a reconcileStalled call (redrive tick, replica-set pass): "idle" | "apply" | "dirty" (the
///                  \* environment changed or a report was raised after its snapshot, so the next pass settles it)
///     recNotHeld   \* its notHeld read, taken before the monitor
///
/// vars == <<exists, replica, owner, quar, cnt, ep, reported, rpc, allStalled, forget, env, settled, early, rec,
///           recNotHeld>>
/// live == <<exists, replica, owner, quar>>
///
/// \* What backs the warning: the stream exists, self is a replica and the owner, not quarantined, stall >= MAX.
/// Backed == exists /\ replica /\ owner /\ ~quar /\ cnt >= MAX
///
/// \* -- Environment: cluster events from other components, each consuming one step of the budget --
/// Ev(name) == /\ name \in Env /\ env > 0 /\ env' = env - 1 /\ settled' = FALSE
///             /\ rec' = IF rec = "apply" THEN "dirty" ELSE rec
///             /\ UNCHANGED recNotHeld
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
///         /\ rec = "idle"
///         /\ recNotHeld = FALSE
///
/// EnvNext == Destroy \/ Recreate \/ RemoveSelf \/ AddSelf \/ OwnerFlip \/ QuarFlip
/// SysNext == R_Start \/ R_Read \/ R_Report \/ ClearStalledOf \/ Rec_Read \/ Rec_Apply
/// Next == EnvNext \/ SysNext
///
/// \* Redrive ticks keep starting rounds of a candidate and reconcile calls; a started step completes; the listener runs.
/// Fairness == /\ WF_vars(R_Start) /\ WF_vars(R_Read) /\ WF_vars(R_Report) /\ WF_vars(ClearStalledOf)
///             /\ WF_vars(Rec_Read) /\ WF_vars(Rec_Apply)
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// \* -- Properties --
/// \* An unbacked warning eventually clears (or becomes backed again).
/// UnbackedClears == (reported /\ ~Backed) ~> (~reported \/ Backed)
///
/// \* Settled as of the next reconcile pass (#3155): a pass during which nothing changed after its snapshot, and nothing
/// \* since, leaves only backed warnings. A change after the snapshot (a flip, a raise) may leave a warning open when the
/// \* call returns; the next pass closes it (UnbackedClears).
/// SettledIsBacked == settled /\ reported => Backed
///
/// \* A raise needs MAX stalled rounds in this incarnation of the stream (#3156).
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
/// CONSTANT Fixed <- AllFixes
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:liveremove
/// CONSTANT Env = {"remove", "add", "owner", "quar"}
/// CONSTANT Fixed <- AllFixes
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:livedestroy
/// CONSTANT Env = {"destroy", "recreate"}
/// CONSTANT Fixed <- AllFixes
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:liveowner
/// CONSTANT Env = {"owner", "quar"}
/// CONSTANT Fixed <- AllFixes
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:edgeremove
/// CONSTANT Env = {"remove", "add", "owner", "quar"}
/// CONSTANT Fixed = {}
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:edgedestroy
/// CONSTANT Env = {"destroy", "recreate"}
/// CONSTANT Fixed = {}
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:edgeowner
/// CONSTANT Env = {"owner", "quar"}
/// CONSTANT Fixed = {}
/// PROPERTY UnbackedClears
/// ```
///
/// ```tlacfg Stalled:settled
/// CONSTANT Env = {"destroy", "recreate", "remove", "add", "owner", "quar"}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT SettledIsBacked
/// ```
///
/// ```tlacfg Stalled:settledweak
/// CONSTANT Env = {"destroy", "recreate", "remove", "add", "owner", "quar"}
/// CONSTANT Fixed = {"level", "reset"}
/// INVARIANT SettledIsBacked
/// ```
///
/// ```tlacfg Stalled:early
/// CONSTANT Env = {"destroy", "recreate", "remove", "add", "owner", "quar"}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT NoEarlyRaise
/// ```
///
/// ```tlacfg Stalled:earlynoreset
/// CONSTANT Env = {"destroy", "recreate", "remove", "add", "owner", "quar"}
/// CONSTANT Fixed = {"level", "recheck"}
/// INVARIANT NoEarlyRaise
/// ```
package org.pragmatica.aether.stream.replication;
