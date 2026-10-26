/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.net;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens.Admission;
import org.pragmatica.consensus.net.IdentityLedger.DurableWriter;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The boot-token registry backed by an identity ledger (#2429): a retirement is loaded before admission opens and written
/// before the registry answers or announces it; the comparison rule is unchanged. The no-ledger rows stay in
/// `BootTokensTest`.
class BootTokensLedgerTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
    private static final long SELF = 0x5E1FL;
    private static final long FIRST = 0xA1L;
    private static final long OTHER = 0xB2L;
    private static final long THIRD = 0xC3L;
    private static final NodeId PEER = new NodeId("peer-1");

    @TempDir
    Path directory;

    /// Holds or fails the directory force, so a test can look at the registry while a write is in flight.
    private static final class GatedWriter implements DurableWriter {
        final DurableWriter real = DurableWriter.fileOps();
        volatile CountDownLatch gate = new CountDownLatch(0);
        final AtomicBoolean failing = new AtomicBoolean();

        @Override
        public Result<Unit> writeForced(Path file, byte[] content) {
            return failing.get()
                   ? Causes.cause("disk full").result()
                   : real.writeForced(file, content);
        }

        @Override
        public Result<Unit> moveAtomic(Path source, Path target) {
            return real.moveAtomic(source, target);
        }

        @Override
        public Result<Unit> forceDirectory(Path dir) {
            hold();

            return real.forceDirectory(dir);
        }

        private void hold() {
            try {
                gate.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Path file() {
        return directory.resolve("identity").resolve("n1").resolve("ledger");
    }

    /// What `IdentityLedgerWiring.wire` does at assembly: attach, open, preload.
    private BootTokens boot(DurableWriter writer, boolean createdNow) {
        var registry = BootTokens.bootTokens(SELF);
        var ledger = IdentityLedger.identityLedger(file(), writer);

        registry.attachLedger(ledger);
        var loading = ledger.open(createdNow);

        registry.preload(loading.facts(), loading.origin() == IdentityLedger.Origin.WIPED);

        return registry;
    }

    private BootTokens boot() {
        return boot(DurableWriter.fileOps(), true);
    }

    @Test
    void admit_beforePreload_isUnavailable_failsClosedNotOpen() {
        var registry = BootTokens.bootTokens(SELF);

        registry.attachLedger(IdentityLedger.identityLedger(file(), DurableWriter.fileOps()));

        assertThat(registry.admit(PEER, FIRST)).isEqualTo(Admission.UNAVAILABLE);
        assertThat(registry.tokenOf(PEER)).isZero();
        assertThat(registry.refusals()).as("not-ready is not a refusal").isZero();
    }

    @Test
    void admit_afterARestart_refusesEveryProcessForARetiredNodeId() {
        var before = boot();

        before.admit(PEER, FIRST);
        assertThat(before.admitDurably(PEER, OTHER).await(BOUND).unwrap()).isEqualTo(Admission.CONFLICT);

        var restarted = boot(DurableWriter.fileOps(), false);

        assertThat(restarted.isRetired(PEER)).as("preloaded from the ledger").isTrue();
        assertThat(restarted.admit(PEER, FIRST)).as("not even the process the NodeId was first bound to").isEqualTo(Admission.RETIRED);
        assertThat(restarted.admit(PEER, OTHER)).isEqualTo(Admission.RETIRED);
        assertThat(restarted.admit(PEER, THIRD)).as("nor a process this registry never saw").isEqualTo(Admission.RETIRED);
        assertThat(restarted.refusals()).isEqualTo(3L);
    }

    @Test
    void admit_afterARestart_aPeerThatWasOnlyBound_isAdmittedWithAnyToken_becauseTokensAreNotKept() {
        var before = boot();

        before.admit(PEER, FIRST);

        var restarted = boot(DurableWriter.fileOps(), false);

        assertThat(restarted.tokenOf(PEER)).as("a restarted node carries a new token: remembering the old one would refuse it").isZero();
        assertThat(restarted.admit(PEER, OTHER)).as("a cold restart of the whole cluster must still form").isEqualTo(Admission.ADMITTED);
    }

    @Test
    void admit_aConflict_announcesTheRetirementOnlyAfterItIsDurable() {
        var writer = new GatedWriter();
        var registry = boot(writer, true);
        var announced = Collections.synchronizedList(new ArrayList<NodeId>());

        registry.admit(PEER, FIRST);
        registry.onRetired(announced::add);
        writer.gate = new CountDownLatch(1);

        var verdict = registry.admitDurably(PEER, OTHER);

        assertThat(verdict.isResolved()).isFalse();
        assertThat(announced).as("retirement not announced before the ledger holds it").isEmpty();
        assertThat(registry.isRetired(PEER)).as("the refusal itself stands at once").isTrue();
        assertThat(registry.admit(PEER, THIRD)).as("evidence meanwhile is refused").isEqualTo(Admission.RETIRED);

        writer.gate.countDown();

        assertThat(verdict.await(BOUND).unwrap()).isEqualTo(Admission.CONFLICT);
        assertThat(announced).containsExactly(PEER);
        assertThat(boot(DurableWriter.fileOps(), false).isRetired(PEER)).as("the retirement survived a restart").isTrue();
    }

    @Test
    void admit_aSynchronousCaller_isNotToldConflictBeforeTheRetirementIsDurable() {
        var writer = new GatedWriter();
        var registry = boot(writer, true);

        registry.admit(PEER, FIRST);
        writer.gate = new CountDownLatch(1);

        assertThat(registry.admit(PEER, OTHER)).isEqualTo(Admission.UNAVAILABLE);

        writer.gate.countDown();
        awaitRefusals(registry, 1L);
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The conflict verdict is counted when the retirement has been written: wait for it, so the test does not leave a
    /// write running under its temp directory.
    private static void awaitRefusals(BootTokens registry, long expected) {
        var deadline = System.nanoTime() + BOUND.nanos();

        while (registry.refusals() < expected && System.nanoTime() < deadline) {
            sleepBriefly();
        }

        assertThat(registry.refusals()).isGreaterThanOrEqualTo(expected);
    }

    @Test
    void admit_aStalledLedgerWriter_delaysOnlyTheConflictVerdict_notPeersAlreadyBound() {
        var writer = new GatedWriter();
        var registry = boot(writer, true);
        var bound = new NodeId("bound");

        registry.admit(bound, FIRST);
        registry.admit(PEER, FIRST);
        writer.gate = new CountDownLatch(1);

        var stalled = registry.admitDurably(PEER, OTHER);

        assertThat(stalled.isResolved()).isFalse();
        assertThat(registry.admitDurably(bound, FIRST).isResolved()).as("a bound peer is answered at once").isTrue();
        assertThat(registry.admitDurably(new NodeId("new"), THIRD).isResolved()).as("so is a new peer").isTrue();
        assertThat(registry.tokenOf(bound)).isEqualTo(FIRST);

        writer.gate.countDown();
        stalled.await(BOUND);
    }

    @Test
    void admit_aFailedWrite_stillRefusesAndAnnounces_andReportsTheLedgerNotDurable() {
        var writer = new GatedWriter();
        var registry = boot(writer, true);
        var announced = Collections.synchronizedList(new ArrayList<NodeId>());

        registry.admit(PEER, FIRST);
        registry.onRetired(announced::add);
        writer.failing.set(true);

        assertThat(registry.admitDurably(PEER, OTHER).await(BOUND).unwrap()).as("the refusal does not depend on the disk").isEqualTo(Admission.CONFLICT);
        assertThat(announced).containsExactly(PEER);
        assertThat(registry.ledgerDurable()).isFalse();
    }

    @Test
    void admit_onAWipedNode_admitsNoNewPeer_untilAMergeCompletes() {
        var wiped = boot(DurableWriter.fileOps(), false);

        assertThat(wiped.admitDurably(PEER, FIRST).await(BOUND).unwrap()).isEqualTo(Admission.UNAVAILABLE);
        assertThat(wiped.tokenOf(PEER)).isZero();
        assertThat(wiped.refusals()).isZero();

        assertThat(wiped.merged(IdentityFacts.terminated(new NodeId("gone"), BootTokens.TOKEN_CONFLICT)).await(BOUND).isSuccess()).isTrue();

        assertThat(wiped.isRetired(new NodeId("gone"))).as("the merge is applied").isTrue();
        assertThat(wiped.admitDurably(PEER, FIRST).await(BOUND).unwrap()).isEqualTo(Admission.ADMITTED);
        assertThat(boot(DurableWriter.fileOps(), false).isRetired(new NodeId("gone"))).as("and written, so a restart is not wiped again").isTrue();
    }

    @Test
    void admit_tokenZero_isStillAdmitted_andRecordsNothing() {
        var registry = boot();

        assertThat(registry.admit(PEER, 0L)).isEqualTo(Admission.ADMITTED);
        assertThat(IdentityLedger.identityLedger(file(), DurableWriter.fileOps()).open(false).facts().isEmpty()).isTrue();
    }

    @Test
    void ledgerDurable_isFalseWithoutALedger_andTrueOnceOneWasCreated() {
        assertThat(BootTokens.bootTokens(SELF).ledgerDurable()).isFalse();
        assertThat(boot().ledgerDurable()).isTrue();
    }
}
