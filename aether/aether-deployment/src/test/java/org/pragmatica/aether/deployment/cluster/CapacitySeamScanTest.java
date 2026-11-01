// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// O3-2: every access of `CapacityControlledLifecycle` to a reservation or to the fleet counter goes through [CapacityRecords]. The scan is a
/// tripwire on the text: it keeps a later edit from reading or writing `CapacityReservationKey` / `CapacityLedgerKey` inline, which would
/// bypass the seam O3-4 replaces. The keys it may still touch directly are NOT capacity records (the cluster configuration, the leader, the
/// node placement, the admission marker, the placement operations).
class CapacitySeamScanTest {
    private static final Path LIFECYCLE = Path.of("src", "main", "java", "org", "pragmatica", "aether", "deployment", "cluster", "CapacityControlledLifecycle.java");
    private static final Pattern RECORD_KEYS = Pattern.compile("CapacityReservationKey|CapacityLedgerKey|CapacityReservationValue\\.class|CapacityLedgerValue\\.class");
    private static final Pattern STORE_ACCESS = Pattern.compile("store\\s*\\.\\s*(?:getTyped|get|snapshot)\\(([^;]*?)[,)]", Pattern.DOTALL);

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theLifecycle_namesNoReservationOrLedgerKey_orValueClass() {
        var source = read(LIFECYCLE);

        assertThat(source).as("control: the scan reads the lifecycle").contains("record CapacityControlledLifecycle").contains("records()");
        assertThat(RECORD_KEYS.matcher(source).results().map(match -> match.group()).toList()).isEmpty();
    }

    @Test
    void theLifecycle_readsTheStoreDirectlyOnlyForKeysThatAreNotCapacityRecords() {
        var source = read(LIFECYCLE);
        var allowed = List.of("AetherKey.ClusterConfigKey.CURRENT", "LeaderKey.INSTANCE", "placementKey", "new AetherKey.CapacityAdmissionKey(node", "");
        var accesses = STORE_ACCESS.matcher(source).results().map(match -> match.group(1).strip()).toList();

        assertThat(accesses).as("control: the scan finds the direct reads that remain").isNotEmpty();
        assertThat(accesses).allMatch(allowed::contains);
    }

    @Test
    void thePatterns_matchTheOldInlineAccess_andMissThePortCall() {
        assertThat(RECORD_KEYS.matcher("store.getTyped(new AetherKey.CapacityReservationKey(node), CapacityReservationValue.class)").find()).isTrue();
        assertThat(RECORD_KEYS.matcher("AetherKey.CapacityLedgerKey.INSTANCE").find()).isTrue();
        assertThat(RECORD_KEYS.matcher("records().reservation(node)").find()).isFalse();
        assertThat(STORE_ACCESS.matcher("store.getTyped(key, X.class)").results().map(m -> m.group(1)).toList()).containsExactly("key");
    }
}
