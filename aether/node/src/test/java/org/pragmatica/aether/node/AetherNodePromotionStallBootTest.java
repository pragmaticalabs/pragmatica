// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.storage.BlockId;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A booted node raises `storage-promotion-stalled` through its own operator sink when promotion copies keep failing,
/// and `storage-promotion-resumed` when a copy succeeds again. What is observed is the node's own
/// `ClusterEventAggregator`, the sink's only consumer: the stalled event is the aggregator's
/// `lastRaisedOperatorWarning()` (the precedent for "the warning reached this node's aggregator", used where the
/// cluster-events stream is not up, as in a single-node fixture), and the resumed event is the aggregator closing the
/// stalled warning's throttle window (`operatorWarningThrottleKeys()` back to its baseline), which only a recovery that
/// reached `onOperatorWarning` does. The line `[storage-promotion-resumed]` in the log is not enough: it is written before
/// the sink is offered anything.
///
/// The failure is real too: `artifacts` has a writable disk tier, and the disk copy of a hot block is replaced by a
/// DIRECTORY at its block path, so the promotion's source read (disk -> memory) fails every cycle. The block is made hot
/// first and memory is left with no room (promotion skips a tier at its watermark without reading), so the failing reads
/// only start when room is made. Restoring the file makes the next cycle's copy succeed.
class AetherNodePromotionStallBootTest {
    private static final String STALLED = "storage-promotion-stalled";

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 180, unit = SECONDS)
    void failingCopiesRaiseTheStalledEvent_andASuccessfulCopyRaisesTheResumedEvent() throws IOException {
        node = AetherNode.aetherNode(PromotionBootFixture.config(tempDir, true), () -> {})
                         .onFailure(cause -> fail("construction must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(timeSpan(30).seconds()).onFailure(cause -> fail("start() must succeed: " + cause.message()));
        PromotionBootFixture.awaitActive(node);

        var artifacts = node.storageSetups().get(PromotionBootFixture.ARTIFACTS);
        var ids = PromotionBootFixture.fillMemoryTier(artifacts);

        PromotionBootFixture.await(() -> artifacts.metadataStore().listBlocksByTier(org.pragmatica.storage.TierLevel.MEMORY).size()
                                         < PromotionBootFixture.MEMORY_BLOCKS,
                                   "the node's own tick demoted the full memory tier");

        var cold = ids.stream().filter(id -> !PromotionBootFixture.isResidentInMemory(artifacts, id)).findFirst().orElseThrow();

        PromotionBootFixture.readRepeatedly(artifacts, cold, PromotionBootFixture.HOT_READS);
        PromotionBootFixture.sleep(2_500);

        assertThat(artifacts.promotionManager().stats().failed()).as("control: nothing has failed yet; memory has no room")
                                                                   .isZero();
        assertThat(raisedCode()).as("control: nothing raised before the failure").isNotEqualTo(STALLED);

        var baselineWindows = node.eventAggregator().operatorWarningThrottleKeys();
        var blockFile = blockFile(cold);
        var saved = blockFile.resolveSibling(blockFile.getFileName() + ".saved");

        Files.move(blockFile, saved);
        Files.createDirectory(blockFile);
        ids.stream()
           .filter(id -> PromotionBootFixture.isResidentInMemory(artifacts, id))
           .limit(3)
           .forEach(id -> artifacts.instance().delete(id).await().onFailure(cause -> fail("delete failed: " + cause.message())));

        PromotionBootFixture.await(() -> STALLED.equals(raisedCode()), "the node's aggregator received storage-promotion-stalled");

        var stalled = node.eventAggregator().lastRaisedOperatorWarning().unwrap();

        assertThat(stalled).isInstanceOfSatisfying(ClusterEvent.OperatorWarning.class,
                                                   warning -> assertThat(warning.details()).containsEntry("subject", PromotionBootFixture.ARTIFACTS)
                                                                                           .containsEntry("subsystem", "storage"));
        assertThat(artifacts.promotionManager().stats().failed()).isGreaterThanOrEqualTo(3);
        assertThat(node.eventAggregator().operatorWarningThrottleKeys()).as("the stalled warning opened its throttle window")
                                                                         .isEqualTo(baselineWindows + 1);

        Files.delete(blockFile);
        Files.move(saved, blockFile);

        PromotionBootFixture.await(() -> node.eventAggregator().operatorWarningThrottleKeys() == baselineWindows,
                                   "the node's aggregator received storage-promotion-resumed and closed the stalled window");
        assertThat(PromotionBootFixture.isResidentInMemory(artifacts, cold)).as("the successful copy is what ended the stall")
                                                                              .isTrue();
        assertThat(artifacts.promotionManager().stats().blocksPromoted()).isGreaterThanOrEqualTo(1);
    }

    private String raisedCode() {
        return node.eventAggregator()
                   .lastRaisedOperatorWarning()
                   .filter(ClusterEvent.OperatorWarning.class::isInstance)
                   .map(ClusterEvent.OperatorWarning.class::cast)
                   .map(warning -> warning.details().get("code"))
                   .or("");
    }

    /// `LocalDiskTier`'s own block layout: `{disk_path}/{hex[0:2]}/{hex[2:4]}/{hex}`.
    private Path blockFile(BlockId id) {
        var hex = id.hexString();

        return tempDir.resolve("blocks").resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex);
    }
}
