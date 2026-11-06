// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestBaseKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestBaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.cluster.state.kvstore.KVCommand.ReadWitness;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/// #2713 (CTO ruling "AB witness option A"): the per-slice index of live A/B tests ([AbTestBaseKey]) and the one write shape of an A/B
/// test record, which keeps the two in the same transaction.
///
/// The index is DERIVED from the base's committed [AbTestValue]s on every write, never incremented from the committed index. So a
/// base whose live tests were committed before the index existed (pre-GA data, or a backup of an earlier build) gets a complete entry
/// at its next A/B write, and until then the index stays absent: the combiner's absence witness is still sound, because every write
/// that creates or moves a live test of the base writes the entry, and a test already holding at the combiner's read is refused by
/// the read itself ([SliceRequirements#canaryRunning]). No rebuild runs at activation.
///
/// A/B test records are never removed from the store (the manager prunes terminal tests from memory only), so "removing" a test
/// from the index is its write into a terminal state; the base's last live test ending removes the entry.
public sealed interface AbTestIndex {
    /// The leader transaction of one A/B test write: the put of `test` at its [AbTestKey] expecting the committed record, and in the
    /// same transaction the base's index at the value derived from the committed tests with `test` in place: a put, a removal when no
    /// live test is left, or, when the derived value is the committed one, a witness on it. A concurrent write that moved the index
    /// refuses this one, and its rebuild derives from the moved state. Fails with
    /// [SliceTargetFence.SliceTargetFenceError.NoCommittedLeader] when no leader is committed.
    static Result<LeaderTransaction<AetherKey, AetherValue>> testWrite(KVStore<AetherKey, AetherValue> store, AbTestValue test) {
        var key = new AbTestKey(test.testId());
        var indexKey = AbTestBaseKey.abTestBaseKey(test.artifactBase());
        var committed = store.get(indexKey);
        var derived = derivedIndex(store, test).map(AetherValue.class::cast);
        var testMutation = new Mutation<AetherKey, AetherValue>(key, store.get(key), Option.some(test));

        return derived.equals(committed)
               ? SliceTargetFence.guardedTransaction(store, List.of(witness(store, test.artifactBase())), List.of(testMutation))
               : SliceTargetFence.guardedTransaction(store,
                                                     List.of(),
                                                     List.of(testMutation,
                                                             new Mutation<AetherKey, AetherValue>(indexKey, committed, derived)));
    }

    /// The witness on `base`'s index at its committed value, absent included: the requirement combiner's fence on every A/B write of
    /// the base, a test created after its read included.
    static ReadWitness<AetherKey> witness(KVStore<AetherKey, AetherValue> store, ArtifactBase base) {
        var key = AbTestBaseKey.abTestBaseKey(base);

        return new ReadWitness<>(key, store.get(key).map(Object.class::cast));
    }

    /// The index of `written`'s base once `written` commits: the base's committed tests with `written` in place of its own record.
    static Option<AbTestBaseValue> derivedIndex(KVStore<AetherKey, AetherValue> store, AbTestValue written) {
        var tests = new ArrayList<AbTestValue>();

        store.forEach(AbTestKey.class, AbTestValue.class, (_, test) -> tests.add(test));

        return indexOf(written,
                       tests.stream()
                            .filter(test -> test.artifactBase()
                                                .equals(written.artifactBase()) && !test.testId()
                                                                                       .equals(written.testId()))
                            .toList());
    }

    private static Option<AbTestBaseValue> indexOf(AbTestValue written, List<AbTestValue> others) {
        var all = new ArrayList<>(others);

        all.add(written);

        var entries = all.stream()
                         .filter(AbTestValue::isLive)
                         .sorted(Comparator.comparing(AbTestValue::testId))
                         .map(test -> test.testId() + "=" + test.state())
                         .toList();

        return entries.isEmpty()
               ? Option.none()
               : Option.some(AbTestBaseValue.abTestBaseValue(String.join(",", entries)));
    }

    record unused() implements AbTestIndex {}
}
