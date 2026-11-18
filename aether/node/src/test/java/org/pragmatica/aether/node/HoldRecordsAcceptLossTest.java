// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.HoldRecords.HoldRefusal;
import org.pragmatica.aether.node.HoldRecords.LossConfirmation;
import org.pragmatica.cluster.metrics.HeldEnd;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.node.HoldRecordsFixture.EPISODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.NODE;
import static org.pragmatica.aether.node.HoldRecordsFixture.held;
import static org.pragmatica.aether.node.HoldRecordsFixture.report;

/// The override (#3345, memo §4): checked against the FAN's latest report (same episode AND digest, or the unknown form while the
/// count is not enumerated), recorded first as a standalone `dht-departure-loss-accepted`, relayed only once that record landed.
class HoldRecordsAcceptLossTest {
    private static final LossConfirmation D1 = LossConfirmation.Digest.digest("d1");

    private final HoldRecordsFixture fx = new HoldRecordsFixture();

    @Test
    void staleDigest409() {
        fx.pong(held(3, "d1"));
        fx.live.put(NODE, held(3, "d2"));

        var refusal = refusalOf(fx.records.acceptLoss(NODE, EPISODE, D1).await());

        assertThat(refusal).as("same count, other digest").isInstanceOf(HoldRefusal.StaleDigest.class);
        assertThat(((HoldRefusal.StaleDigest) refusal).current().owedDigest()).as("carries the current report").isEqualTo("d2");
        assertThat(status(refusal)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fx.raisedOf(OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED)).isZero();
        assertThat(fx.records.decisionFor(NODE)).isEqualTo(Option.none());
    }

    @Test
    void unknownFormOnlyWhileUnenumerated() {
        fx.pong(held(3, "d1"));
        assertThat(refusalOf(fx.records.acceptLoss(NODE, EPISODE, LossConfirmation.InventoryUnknown.INVENTORY_UNKNOWN).await()))
            .as("unknown form on an enumerated inventory").isInstanceOf(HoldRefusal.StaleDigest.class);

        var unenumerated = new HoldRecordsFixture();
        unenumerated.pong(held(-1, ""));
        assertThat(refusalOf(unenumerated.records.acceptLoss(NODE, EPISODE, LossConfirmation.Digest.digest("")).await()))
            .as("digest form while unenumerated").isInstanceOf(HoldRefusal.StaleDigest.class);
        assertThat(unenumerated.records.acceptLoss(NODE, EPISODE, LossConfirmation.InventoryUnknown.INVENTORY_UNKNOWN).await()
                               .isSuccess()).isTrue();
    }

    @Test
    void recordLandsBeforeRelay() {
        fx.pong(held(3, "d1"));
        fx.holdOpen = true;
        var call = fx.records.acceptLoss(NODE, EPISODE, D1);

        assertThat(fx.raisedOf(OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED)).isEqualTo(1);
        assertThat(fx.records.decisionFor(NODE)).as("record not landed yet").isEqualTo(Option.none());
        assertThat(fx.records.heldLatched(NODE)).isTrue();

        fx.settlePending(true, () -> fx.records.decisionFor(NODE).isPresent());

        assertThat(fx.records.decisionFor(NODE)).isEqualTo(Option.some(HoldRecords.HoldDecision.AcceptLoss.acceptLoss(EPISODE, D1)));
        assertThat(call.await().unwrap().report().owedDigest()).isEqualTo("d1");
    }

    @Test
    void recordNotLandedIs503AndNothingRelayed() {
        fx.pong(held(3, "d1"));
        fx.lands = false;

        var refusal = refusalOf(fx.records.acceptLoss(NODE, EPISODE, D1).await());

        assertThat(refusal).isInstanceOf(HoldRefusal.RecordNotLanded.class);
        assertThat(status(refusal)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(fx.records.decisionFor(NODE)).isEqualTo(Option.none());
        assertThat(fx.records.heldLatched(NODE)).isTrue();

        fx.lands = true;
        assertThat(fx.records.acceptLoss(NODE, EPISODE, D1).await().isSuccess()).as("a retry may proceed").isTrue();
    }

    @Test
    void secondCall409() {
        fx.pong(held(3, "d1"));
        fx.holdOpen = true;
        fx.records.acceptLoss(NODE, EPISODE, D1);
        assertThat(refusalOf(fx.records.acceptLoss(NODE, EPISODE, D1).await())).as("while the first is in flight")
                                                                               .isInstanceOf(HoldRefusal.AlreadyAccepted.class);

        fx.settlePending(true, () -> fx.records.decisionFor(NODE).isPresent());
        var refusal = refusalOf(fx.records.acceptLoss(NODE, EPISODE, D1).await());

        assertThat(refusal).isInstanceOf(HoldRefusal.AlreadyAccepted.class);
        assertThat(status(refusal)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fx.raisedOf(OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED)).isEqualTo(1);
    }

    @Test
    void notHeldEndedOrOtherEpisodeAre409() {
        assertThat(refusalOf(fx.records.acceptLoss(NODE, EPISODE, D1).await())).isInstanceOf(HoldRefusal.NotHeld.class);

        fx.pong(held(3, "d1"));
        assertThat(refusalOf(fx.records.acceptLoss(NODE, "drainer-1@2", D1).await())).isInstanceOf(HoldRefusal.OtherEpisode.class);

        fx.pong(report(3, 0, "d1", HeldEnd.DELIVERED));
        var ended = refusalOf(fx.records.acceptLoss(NODE, EPISODE, D1).await());
        assertThat(ended).isInstanceOf(HoldRefusal.Ended.class);
        assertThat(status(ended)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void recordTextCarriesTheConfirmedReport() {
        fx.pong(held(3, "d1"));
        fx.records.acceptLoss(NODE, EPISODE, D1).await();

        var record = fx.raised.getFirst().warning();
        assertThat(record.code()).isEqualTo(OperatorWarningCode.DHT_DEPARTURE_LOSS_ACCEPTED);
        assertThat(record.subject()).isEqualTo(EPISODE);
        assertThat(record.message()).contains("3 key(s)").contains("--digest d1").contains("first keys: k1,k2");
    }

    private static Cause refusalOf(Result<?> result) {
        return result.fold(cause -> cause, value -> fail("expected a refusal, got " + value));
    }

    private static HttpStatus status(Cause cause) {
        return ((HttpStatusAware) cause).httpStatus();
    }
}
