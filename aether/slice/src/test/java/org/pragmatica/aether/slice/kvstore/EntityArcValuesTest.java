// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherValue.EntityArcWaiverValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityArcWaiverValue.WaiverCase;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntitySeedOfferValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntitySeedOfferValue.OfferEntry;
import org.pragmatica.cluster.state.kvstore.LeaderAuthorized;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The two arc values of #3303 A3b: the seed offer `(parentLife, L, parentOwnerEpoch, blockIdHex, entries)` and the arc waiver
/// `(childLife, case, discarded)`.
///
/// The waiver is written only by the leader's `accept-loss` transaction (design r8.2 V6), so it is [LeaderAuthorized]; the offer is a
/// plain Put (V1) and must not carry the marker, or the applier would drop the owner's own write.
class EntityArcValuesTest {
    private static final EntitySeedOfferValue OFFER = EntitySeedOfferValue.entitySeedOfferValue(3L,
                                                                                                4L,
                                                                                                9L,
                                                                                                "00ff10ab",
                                                                                                List.of(OfferEntry.offerEntry(6, 120L, 2L),
                                                                                                        OfferEntry.offerEntry(7, 0L, 0L)));
    private static final EntityArcWaiverValue WAIVER = EntityArcWaiverValue.entityArcWaiverValue(4L,
                                                                                                 WaiverCase.SEED_CORRUPT,
                                                                                                 List.of("00ff10ab", "ab10ff00"));

    @Test
    void seedOffer_roundTrips_throughTheBackupCodec() {
        assertThat(roundTrip(OFFER)).isEqualTo(OFFER);
    }

    @Test
    void arcWaiver_roundTrips_throughTheBackupCodec() {
        assertThat(roundTrip(WAIVER)).isEqualTo(WAIVER);
    }

    @Test
    void seedOffer_roundTrips_withNoEntries() {
        var empty = EntitySeedOfferValue.entitySeedOfferValue(3L, 4L, 9L, "00ff10ab", List.of());

        assertThat(roundTrip(empty)).isEqualTo(empty);
    }

    @Test
    void arcWaiver_roundTrips_everyCase() {
        for (var waiverCase : WaiverCase.values()) {
            var waiver = EntityArcWaiverValue.entityArcWaiverValue(4L, waiverCase, List.of());

            assertThat(roundTrip(waiver)).isEqualTo(waiver);
        }
    }

    @Test
    void waiverCase_endsWithTheUnknownSentinel() {
        var constants = WaiverCase.values();

        assertThat(constants[constants.length - 1]).isEqualTo(WaiverCase.UNKNOWN);
    }

    @Test
    void arcWaiver_isLeaderAuthorized_andTheOfferIsNot() {
        assertThat(WAIVER).isInstanceOf(LeaderAuthorized.class);
        assertThat(OFFER).isNotInstanceOf(LeaderAuthorized.class);
    }

    @Test
    void seedOffer_copiesTheEntries_soALaterMutationOfTheCallersListDoesNotReachTheRecord() {
        var callers = new ArrayList<>(List.of(OfferEntry.offerEntry(6, 120L, 2L)));
        var offer = EntitySeedOfferValue.entitySeedOfferValue(3L, 4L, 9L, "00ff10ab", callers);

        callers.add(OfferEntry.offerEntry(7, 0L, 0L));

        assertThat(offer.entries()).containsExactly(OfferEntry.offerEntry(6, 120L, 2L));
    }

    @Test
    void arcWaiver_copiesTheDiscarded_soALaterMutationOfTheCallersListDoesNotReachTheRecord() {
        var callers = new ArrayList<>(List.of("00ff10ab"));
        var waiver = EntityArcWaiverValue.entityArcWaiverValue(4L, WaiverCase.SEED_CORRUPT, callers);

        callers.add("ab10ff00");

        assertThat(waiver.discarded()).containsExactly("00ff10ab");
    }

    @Test
    void seedOffer_accessors_returnTheirOwnComponent_withDistinctValues() {
        assertThat(OFFER.parentLife()).isEqualTo(3L);
        assertThat(OFFER.L()).isEqualTo(4L);
        assertThat(OFFER.parentOwnerEpoch()).isEqualTo(9L);
        assertThat(OFFER.blockIdHex()).isEqualTo("00ff10ab");
    }

    @Test
    void offerEntry_accessors_returnTheirOwnComponent_withDistinctValues() {
        var entry = OfferEntry.offerEntry(6, 120L, 2L);

        assertThat(entry.child()).isEqualTo(6);
        assertThat(entry.keyCount()).isEqualTo(120L);
        assertThat(entry.timerCount()).isEqualTo(2L);
    }

    @Test
    void arcWaiver_recordsTheInputCase_forEveryCase() {
        for (var waiverCase : WaiverCase.values()) {
            var expected = new EntityArcWaiverValue(4L, waiverCase, List.of("00ff10ab"));
            var built = EntityArcWaiverValue.entityArcWaiverValue(4L, waiverCase, List.of("00ff10ab"));

            assertThat(built.waiverCase()).isEqualTo(expected.waiverCase());
            assertThat(built.waiverCase()).isSameAs(waiverCase);
        }
    }

    @Test
    void waiverCase_namesTheRow3bAnd3dCauses_beforeTheSentinel() {
        var constants = List.of(WaiverCase.values());

        assertThat(constants.indexOf(WaiverCase.ARC_PARENT_RELEASED)).isLessThan(constants.indexOf(WaiverCase.UNKNOWN));
        assertThat(constants.indexOf(WaiverCase.ARC_BASE_WITHHELD)).isLessThan(constants.indexOf(WaiverCase.UNKNOWN));
    }

    private static AetherValue roundTrip(AetherValue value) {
        var codec = BackupFixtures.codec()
                                  .canonical();

        return codec.decode(codec.encode(value));
    }
}
