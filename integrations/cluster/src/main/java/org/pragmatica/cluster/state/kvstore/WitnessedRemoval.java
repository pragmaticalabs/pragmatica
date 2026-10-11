package org.pragmatica.cluster.state.kvstore;

/// A [VersionFenced] value that only the writer of its CURRENT committed incarnation may delete (#806).
///
/// [VersionFenced] closes the lost-update race on `Put`; it leaves `Remove` open, so a holder whose
/// claim was superseded can still delete its successor's record with a bare `Remove(key)`. For a
/// lock-shaped record that turns "the lease expired under a slow holder" from a refused write into a
/// destroyed lock: the late holder's release erases the taker's claim and a third party then acquires it
/// while the taker is still working.
///
/// A `Remove` of a key whose committed value is `WitnessedRemoval` is applied only when its `witness`
/// is EQUAL to the committed value — the exact value the remover last wrote. Equality, not the version
/// number alone: a released key restarts its chain, so a stale holder's `v1` witness would otherwise match
/// an unrelated later claim that is also `v1`. A missing, stale or differently-typed witness leaves the
/// record untouched and emits NO notification; like every fenced rejection it is silent, so a remover that
/// needs the outcome re-reads committed state. The predicate reads only committed storage and the command,
/// so every replica decides identically.
///
/// Opt-in, not blanket on [VersionFenced]: other `VersionFenced` records are still deleted by witnessless
/// removers (blueprint withdrawal clears its `DeploymentOutcomeValue` bare). #972 did not need that remover
/// witnessed: it binds each outcome to the publish attempt it closes instead, and an absent outcome is
/// never read as a verdict. Adopting this for a record is `implements WitnessedRemoval` plus a witness at
/// the call site.
public interface WitnessedRemoval extends VersionFenced {}
