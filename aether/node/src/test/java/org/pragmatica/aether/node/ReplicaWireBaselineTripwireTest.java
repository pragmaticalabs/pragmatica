// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// TW-O10 of the worker-replica-feed design (W-OBS): the wire tags of the worker metadata projection.
///
/// `WireAssignmentTripwireTest` already fails on any drift of the baseline; this pins the SPECIFIC assignment the design retires.
/// Tags 1695-1699 (the manifest, chunk and scope messages) and 2119 (the DHT replication record) are in the baseline today. The
/// design removes the messages in W5 but keeps the tags as pins with RETIRED comments, because the pin STAYS so a tag is never
/// reused (`SystemTags` convention), and adds the replica feed's tags (W2). The baseline's retired-section format is not decided
/// yet, so the `@Disabled` half asserts the properties (no live line, a RETIRED comment carrying each number, a replica TAG line)
/// and leaves the exact layout to the PR that introduces it.
class ReplicaWireBaselineTripwireTest {
    private static final String BASELINE_RESOURCE = "/wire-assignment-baseline.txt";
    private static final String PROJECTION = "org.pragmatica.aether.worker.metadata.WorkerMetadataMessage";
    private static final List<String> RETIRED_TAGS = List.of("1695", "1696", "1697", "1698", "1699", "2119");

    private static List<String> baselineLines() {
        try (var stream = ReplicaWireBaselineTripwireTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            assertThat(stream).as(BASELINE_RESOURCE + " is on the test classpath").isNotNull();

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> liveProjectionTags() {
        return baselineLines().stream()
                              .filter(line -> line.startsWith("TAG ") && line.contains(PROJECTION))
                              .toList();
    }

    @Test
    void baseline_listsTheProjectionTags_asLiveAssignments_today() {
        var live = liveProjectionTags();

        assertThat(live).as("TW-O10: the six projection message types are live TAG lines of the baseline today").hasSize(6);
        assertThat(RETIRED_TAGS).as("and they carry exactly the tags the design retires")
                                .allSatisfy(tag -> assertThat(live).anyMatch(line -> line.endsWith(" " + tag)));
        assertThat(baselineLines()).as("no replica-feed tag exists yet").noneMatch(line -> line.startsWith("TAG ") && line.contains("Replica"));
    }

    @Disabled("W2/W5: enable when the projection tags are RETIRED pins and the replica feed's tags are recorded; the exact retired-section layout is decided by the PR that introduces it")
    @Test
    void baseline_keepsTheProjectionTagsRetired_andRecordsTheReplicaTags() {
        var lines = baselineLines();

        assertThat(liveProjectionTags()).as("no projection message remains a live assignment").isEmpty();
        assertThat(RETIRED_TAGS).as("each retired tag is kept as a RETIRED pin so it is never reused")
                                .allSatisfy(tag -> assertThat(lines).anyMatch(line -> line.startsWith("#") && line.contains("RETIRED") && line.contains(tag)));
        assertThat(lines).as("the replica feed's messages are recorded").anyMatch(line -> line.startsWith("TAG ") && line.contains("Replica"));
    }
}
