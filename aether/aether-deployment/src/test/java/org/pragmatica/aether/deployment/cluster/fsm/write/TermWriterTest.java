// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm.write;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.HierarchyStateWriter;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.OTHER;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.SELF;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.commitLeader;
import static org.pragmatica.aether.deployment.cluster.fsm.write.WriteFixture.freshStore;

/// #2584 design §2.2 point 3a: [TermWriter#write] issues nothing unless the committed leader record names this node and
/// the term is live, and otherwise applies exactly what the site built and hands the apply's own outcome back.
class TermWriterTest {
    private static final Artifact SLICE = Artifact.artifact("com.example:slice-a:1.0.0").unwrap();
    private static final NodeArtifactKey KEY = NodeArtifactKey.nodeArtifactKey(SELF, SLICE);

    private final KVStore<AetherKey, AetherValue> store = freshStore();
    private final WriteFixture.RecordingClusterNode cluster = new WriteFixture.RecordingClusterNode(store);
    private final AtomicBoolean deactivated = new AtomicBoolean(false);
    private final AtomicInteger builds = new AtomicInteger();

    @Test
    void write_leaderValueNamesOtherNode_notIssued() {
        commitLeader(store, OTHER, 2);

        var outcome = termWriter().write(WriteSite.LOAD, this::loadCommand).await();

        assertThat(cluster.batches()).as("a node the committed leader record does not name issues nothing").isEmpty();
        assertThat(builds.get()).as("and does not even build the commands").isZero();
        assertThat(causeOf(outcome)).isInstanceOfSatisfying(FsmWriteError.NotLeader.class,
                                                            notLeader -> assertThat(notLeader.committedLeader()).isEqualTo(Option.some(OTHER)));
    }

    @Test
    void write_termDeactivated_notIssued() {
        commitLeader(store, SELF, 1);
        deactivated.set(true);

        var outcome = termWriter().write(WriteSite.LOAD, this::loadCommand).await();

        assertThat(cluster.batches()).as("an ended term issues nothing, though the record still names this node").isEmpty();
        assertThat(builds.get()).isZero();
        assertThat(causeOf(outcome)).isInstanceOf(FsmWriteError.NotLeader.class);
    }

    /// The positive control of the two refusals above: the same write under a record naming this node, in a live term.
    @Test
    void write_leaderValueNamesSelf_issuedOnce() {
        commitLeader(store, SELF, 1);

        var outcome = termWriter().write(WriteSite.LOAD, this::loadCommand).await();

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(builds.get()).isEqualTo(1);
        assertThat(cluster.batches()).containsExactly(loadBatch());
        assertThat(store.get(KEY)).isEqualTo(Option.some(loadValue()));
    }

    /// The hierarchy sites keep the refusal they have always seen (`HierarchyStateWriter.Refusal.INACTIVE`).
    @Test
    void hierarchy_notLeader_returnsInactiveRefusal() {
        commitLeader(store, OTHER, 2);
        var directiveKey = ActivationDirectiveKey.activationDirectiveKey(OTHER);

        var outcome = termWriter().hierarchyPut(directiveKey, Option.none(), ActivationDirectiveValue.core()).await();

        assertThat(cluster.batches()).isEmpty();
        assertThat(causeOf(outcome)).isEqualTo(HierarchyStateWriter.Refusal.INACTIVE);
    }

    /// The term half of the hierarchy authority: a record naming this node is not enough once the term has ended.
    @Test
    void hierarchy_termEnded_returnsInactiveRefusal() {
        commitLeader(store, SELF, 1);
        deactivated.set(true);
        var directiveKey = ActivationDirectiveKey.activationDirectiveKey(OTHER);

        var outcome = termWriter().hierarchyPut(directiveKey, Option.none(), ActivationDirectiveValue.core()).await();

        assertThat(cluster.batches()).isEmpty();
        assertThat(causeOf(outcome)).isEqualTo(HierarchyStateWriter.Refusal.INACTIVE);
    }

    /// The positive control of the refusal above: the same hierarchy write in a live term under a record naming this node.
    @Test
    void hierarchy_liveTermNamesSelf_isIssued() {
        commitLeader(store, SELF, 1);
        var directiveKey = ActivationDirectiveKey.activationDirectiveKey(OTHER);

        var outcome = termWriter().hierarchyPut(directiveKey, Option.none(), ActivationDirectiveValue.core()).await();

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(cluster.batches()).hasSize(1);
    }

    /// LEGACY = one apply of what the site built, then the site's own handler, given the apply's own cause unchanged.
    @Test
    void legacy_appliesThenRunsTheGivenHandler_unchanged() {
        commitLeader(store, SELF, 1);
        Cause applyFailure = Causes.cause("consensus timed out");
        var order = new ArrayList<String>();
        var handled = new ArrayList<Cause>();

        cluster.beforeEachBatch(_ -> order.add("apply"));
        cluster.failWith(applyFailure);
        assertThat(WriteSite.UNLOAD_STUCK_RESET.route()).as("precondition: a LEGACY site").isEqualTo(Route.LEGACY);

        termWriter().write(WriteSite.UNLOAD_STUCK_RESET, this::loadCommand)
                    .onFailure(cause -> order.add("handler"))
                    .onFailure(handled::add)
                    .await();

        assertThat(cluster.batches()).as("one apply").containsExactly(loadBatch());
        assertThat(order).as("the apply is attempted before the handler runs").containsExactly("apply", "handler");
        assertThat(handled).as("the handler sees the apply's own cause").containsExactly(applyFailure);
    }

    private TermWriter termWriter() {
        return FsmWriter.fsmWriter(cluster, store, SELF).openTerm(deactivated::get);
    }

    private List<KVCommand<AetherKey>> loadCommand() {
        builds.incrementAndGet();

        return loadBatch();
    }

    private static List<KVCommand<AetherKey>> loadBatch() {
        return List.of(new KVCommand.Put<>(KEY, loadValue()));
    }

    private static NodeArtifactValue loadValue() {
        return NodeArtifactValue.nodeArtifactValue(SliceState.LOAD, 0L);
    }

    private static Cause causeOf(org.pragmatica.lang.Result<?> outcome) {
        return outcome.fold(cause -> cause, _ -> Causes.cause("expected a failure, got a success"));
    }
}
