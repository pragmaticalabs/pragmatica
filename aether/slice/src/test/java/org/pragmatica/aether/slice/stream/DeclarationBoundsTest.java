// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.RetentionDeclaration;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.stream.DeclarationBounds.Arm;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class DeclarationBoundsTest {
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * MIB;

    private static RetentionDeclaration retention(Option<Long> age, Option<Long> bytes, Option<Long> count, RetentionMode mode) {
        return RetentionDeclaration.retentionDeclaration(age, bytes, count, some(mode));
    }

    @Test
    void floors_areTwiceTheSealConstants() {
        assertThat(DeclarationBounds.minHotBytes()).isEqualTo(2 * DeclarationDefaults.SEAL_RUN_BYTES);
        assertThat(DeclarationBounds.minHotCount()).isEqualTo(2L * DeclarationDefaults.SEAL_RUN_EVENTS);
        assertThat(DeclarationBounds.minShareBytes()).isEqualTo(2 * DeclarationDefaults.SEAL_MERGE_BYTES);
        assertThat(DeclarationBounds.minShareCount()).isEqualTo(2L * DeclarationDefaults.SEAL_RUN_EVENTS);
    }

    @Test
    void anyMode_theSmallestComputableBoundSetsTheShare() {
        var declared = retention(none(), some(1024 * GIB), some(1024L * 8192), RetentionMode.ANY);
        var refusal = DeclarationBounds.shareRefusal(declared, 1024, 512).or(() -> null);

        assertThat(refusal).isNotNull();
        assertThat(refusal.arm()).isEqualTo(Arm.BYTE);
        assertThat(refusal.setBy()).isEqualTo("retention.max_count");
        assertThat(refusal.share()).isEqualTo(4 * MIB);
    }

    @Test
    void allMode_theLargestBoundSetsTheShare_andAnAgeBoundMakesItNotComputable() {
        var both = retention(none(), some(1024 * GIB), some(1024L * 8192), RetentionMode.ALL);
        var withAge = retention(some(3_600_000L), some(GIB), none(), RetentionMode.ALL);

        assertThat(DeclarationBounds.shareRefusal(both, 1024, 512).isEmpty()).isTrue();
        assertThat(DeclarationBounds.shareRefusal(withAge, 1024, 512).isEmpty()).isTrue();
    }

    @Test
    void ageOnly_isNeverRefused() {
        assertThat(DeclarationBounds.shareRefusal(retention(some(1L), none(), none(), RetentionMode.ANY), 1024, 1).isEmpty()).isTrue();
    }

    @Test
    void shareAtTheFloor_isAccepted_oneBelowIsRefused() {
        assertThat(DeclarationBounds.shareRefusal(retention(none(), some(8 * MIB), none(), RetentionMode.ANY), 1, 1).isEmpty()).isTrue();
        assertThat(DeclarationBounds.shareRefusal(retention(none(), some(8 * MIB - 1), none(), RetentionMode.ANY), 1, 1)
                                  .isPresent()).isTrue();
    }

    @Test
    void productsSaturate_neverThrowOrWrap() {
        var declared = retention(none(), none(), some(Long.MAX_VALUE), RetentionMode.ANY);

        assertThat(DeclarationBounds.shareRefusal(declared, 1, Long.MAX_VALUE).isEmpty()).isTrue();
        assertThat(DeclarationBounds.shareRefusal(declared, 1, 1 << 20).isEmpty()).isTrue();
        assertThat(DeclarationBounds.shareRefusal(retention(none(), some(1L), none(), RetentionMode.ANY), Integer.MAX_VALUE, 1)
                                    .or(() -> null)
                                    .smallestValid()).isEqualTo(8 * MIB * Integer.MAX_VALUE);
    }
}
