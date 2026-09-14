// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.artifact;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Version;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// #281 — `<latest>`/`<release>` order: numeric parts, then the qualifier as Maven's
/// `ComparableVersion` ranks it. Every `LOWER_THAN` row was checked against `ComparableVersion`
/// 3.9.12 (verify-1132); bare `a`/`b` are unknown qualifiers there, hence above the release.
class VersionOrderTest {
    private record Row(String lower, String higher) {}

    private static final List<Row> LOWER_THAN = List.of(
        new Row("1.0.0", "1.0.1"),
        new Row("1.0.9", "1.0.10"),
        new Row("1.9.0", "2.0.0"),
        new Row("1.0.0-alpha", "1.0.0-beta"),
        new Row("1.0.0-beta", "1.0.0-rc1"),
        new Row("1.0.0-rc1", "1.0.0-rc4"),
        new Row("1.0.0-rc4", "1.0.0-rc10"),
        new Row("1.0.0-rc4", "1.0.0"),
        new Row("1.0.0-rc4", "1.0.0-SNAPSHOT"),
        new Row("1.0.0-SNAPSHOT", "1.0.0"),
        new Row("1.0.0", "1.0.0-sp1"),
        new Row("1.0.0-m2", "1.0.0-cr1"),
        new Row("1.0.0-rc1", "1.0.0-custom"),
        new Row("1.0.0-SNAPSHOT", "1.0.0-custom"),
        new Row("1.0.0-sp1", "1.0.0-custom"),
        new Row("1.0.0-custom", "1.0.0-zzz"),
        new Row("1.0.0-final", "1.0.0-sp1"),
        new Row("1.0.0", "1.0.0-1"),
        new Row("1.0.0-rc4-SNAPSHOT", "1.0.0-rc4"),
        new Row("1.0.0-rc4-SNAPSHOT", "1.0.0-SNAPSHOT"),
        new Row("1.0.0-rc4", "1.0.0-rc5-SNAPSHOT"),
        new Row("1.0.0-beta-snapshot", "1.0.0-beta"),
        new Row("1.0.0-alpha", "1.0.0-a"),
        new Row("1.0.0", "1.0.0-b"));

    private static final List<Row> EQUIVALENT = List.of(
        new Row("1.0.0", "1.0.0-ga"),
        new Row("1.0.0-ga", "1.0.0-final"),
        new Row("1.0.0-a1", "1.0.0-alpha1"),
        new Row("1.0.0-cr2", "1.0.0-rc2"));

    @Test
    void ordersLowerBeforeHigher() {
        for (var row : LOWER_THAN) {
            var a = version(row.lower());
            var b = version(row.higher());

            assertThat(VersionOrder.INSTANCE.compare(a, b)).as("%s < %s", row.lower(), row.higher()).isNegative();
            assertThat(VersionOrder.INSTANCE.compare(b, a)).as("%s > %s", row.higher(), row.lower()).isPositive();
        }
    }

    @Test
    void aliasedQualifiersCompareEqual() {
        for (var row : EQUIVALENT) {
            assertThat(VersionOrder.INSTANCE.compare(version(row.lower()), version(row.higher())))
                .as("%s == %s", row.lower(), row.higher())
                .isZero();
        }

        for (var v : List.of("1.0.0", "1.0.0-rc4", "1.0.0-SNAPSHOT")) {
            assertThat(VersionOrder.INSTANCE.compare(version(v), version(v))).isZero();
        }
    }

    @Test
    void snapshotDetection() {
        assertThat(VersionOrder.isSnapshot(version("1.0.0-SNAPSHOT"))).isTrue();
        assertThat(VersionOrder.isSnapshot(version("1.0.0-beta-snapshot"))).isTrue();
        assertThat(VersionOrder.isSnapshot(version("1.0.0-rc4"))).isFalse();
        assertThat(VersionOrder.isSnapshot(version("1.0.0"))).isFalse();
    }

    private static Version version(String s) {
        return Version.version(s).unwrap();
    }
}
