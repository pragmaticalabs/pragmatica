// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.PublishOutcomeUnknown;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams overhaul NT-108 (#2201, spec 5.2 failure modes, F-06b): a WAL fsync that fails AFTER the ordered section. The frame was written
/// and the record appended (and sent to the replicas) inside the section; the group-commit wait runs outside it and fails. The WAL
/// fail-stops and the next publish is refused by the in-section guard (`PartitionFailStopped`, PR-2c D-01): both hold today.
///
/// The publish itself must resolve as an unknown outcome, because the record is in the log. Today it fails with the WAL's own cause, which
/// reads as "nothing reached the log". The spec places that mapping in 5.3 step (f), the `publishConfirmed` composition of PR-2d, so PR-2c
/// leaves the pair as a tripwire (enabled, today's cause) beside the real assertion (`@Disabled` until PR-2d).
@Timeout(60)
class PostSectionFsyncFailureTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final String ENABLE = "NT-108 fixed? A post-section fsync failure now resolves PublishOutcomeUnknown: delete this tripwire and "
                                         + "enable postSectionFsyncFailure_resolvesOutcomeUnknown (#2201, PR-2d step (f))";

    @TempDir
    Path walDir;

    @Test
    void postSectionFsyncFailure_failsWithTheWalCause_failStops_andTheNextPublishIsRefused_tripwire() throws Exception {
        var run = new Run(walDir);
        var outcome = run.publishWhoseFsyncFails();

        assertThat(outcome.isFailure()).as("control: the publish whose fsync failed did not succeed").isTrue();
        assertThat(run.recordIsInTheRing()).as("control: the record was appended inside the section").isTrue();
        assertThat(run.nextPublish()).as("the next publish is refused by the guard").isInstanceOf(StreamError.PartitionFailStopped.class);
        outcome.onFailure(cause -> assertThat(cause).as(ENABLE).isNotInstanceOf(PublishOutcomeUnknown.class));
        run.close();
    }

    @Disabled("NT-108 real, RED until PR-2d (spec 5.3 step (f)): a post-section fsync failure must resolve as an unknown outcome (#2201)")
    @Test
    void postSectionFsyncFailure_resolvesOutcomeUnknown() throws Exception {
        var run = new Run(walDir);
        var outcome = run.publishWhoseFsyncFails();

        outcome.onSuccess(offset -> fail("the publish whose fsync failed succeeded at " + offset))
               .onFailure(cause -> assertThat(cause).isInstanceOf(PublishOutcomeUnknown.class));
        assertThat(run.nextPublish()).isInstanceOf(StreamError.PartitionFailStopped.class);
        run.close();
    }

    private static final class Run {
        private final StreamPartitionManager manager;

        Run(Path walDir) {
            manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
            manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
            manager.publishLocal(STREAM, PARTITION, bytes("acked"), 1L).onFailure(cause -> fail(cause.message()));
        }

        /// The publish writes its frame and leaves the section; its fsync parks, the real channel closes under it, then it proceeds and fails.
        Result<Long> publishWhoseFsyncFails() throws Exception {
            var gate = GatedWalFsync.inject(GatedWalFsync.walOf(manager, STREAM, PARTITION));
            var outcome = new AtomicReference<Result<Long>>();
            var publisher = new Thread(() -> outcome.set(manager.publishLocal(STREAM, PARTITION, bytes("fsync-fails"), 2L)));

            publisher.start();
            assertThat(gate.forceEntered.await(10, TimeUnit.SECONDS)).as("control: the publish reached its fsync").isTrue();
            gate.delegate.close();
            gate.forceProceed.countDown();
            publisher.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(publisher.isAlive()).as("the publish resolved").isFalse();
            return outcome.get();
        }

        boolean recordIsInTheRing() {
            return manager.partitionBuffer(STREAM, PARTITION)
                          .unwrap()
                          .readAppended(1, 1)
                          .map(events -> events.stream().anyMatch(event -> new String(event.data(), UTF_8).equals("fsync-fails")))
                          .or(false);
        }

        Object nextPublish() {
            return manager.publishLocal(STREAM, PARTITION, bytes("after"), 3L).fold(cause -> cause, offset -> "accepted at " + offset);
        }

        void close() {
            manager.close();
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }
}
