// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.FakeSliceStore;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingInvocationHandler;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingRoutePublisher;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// The operator surface of H3b's bound (#3174, CTO ruling on #3215 item 5): a `PUBLISH_ROUTES` that proceeds after the bound expired raises
/// `route-unpublish-await-expired` once per unsettled removal, and `route-unpublish-await-settled` when that removal settles. The adapter is
/// built with a 300 ms bound; the production bound is 100 s.
class PendingUnpublishExpiryTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(300).millis();

    private final FakeSliceStore store = new FakeSliceStore().withLoaded(ORDERS, new RuntimeFixtures.BareSlice());
    private final RecordingRoutePublisher publisher = new RecordingRoutePublisher();
    private final ConfigNotificationManager configNotifications = ConfigNotificationManager.configNotificationManager();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final SliceRuntimeAdapter adapter = SliceRuntimeAdapter.sliceRuntimeAdapter(new SliceRuntimeAdapter.Collaborators(SELF,
                                                                                                                              store,
                                                                                                                              new RecordingInvocationHandler(),
                                                                                                                              SliceCodec.sliceCodec(List.of()),
                                                                                                                              Option.some(publisher),
                                                                                                                              Option.some(RuntimeFixtures.FACADE),
                                                                                                                              configNotifications,
                                                                                                                              WiringFixtures.store(),
                                                                                                                              () -> new StubCluster(),
                                                                                                                              () -> BOOT_INCARNATION,
                                                                                                                              () -> OperatorWarningSink.handingOffTo(warnings::add)),
                                                                    BOUND);

    @AfterEach
    void tearDown() {
        configNotifications.shutdown();
    }

    private Promise<LocalOutcome> run(LocalStep step) {
        return adapter.run(new NodeFsmEffect.LocalOp(1, 7, Option.some(ORDERS), step, 0, Option.none()));
    }

    private Promise<Unit> suspendWithRemoval() {
        var removal = Promise.<Unit> promise();

        publisher.unpublishPending = removal;
        await().atMost(Duration.ofSeconds(1)).until(run(LocalStep.SUSPEND)::isResolved);

        return removal;
    }

    private long count(OperatorWarningCode code) {
        return warnings.stream().filter(w -> w.code() == code).count();
    }

    @Test
    void expiry_publishProceeds_andTheWarningNamesNodeAndArtifact() {
        suspendWithRemoval();

        var published = run(LocalStep.PUBLISH_ROUTES);

        await().atMost(Duration.ofSeconds(5)).until(published::isResolved);
        assertThat(published.await().unwrap()).isInstanceOf(LocalOutcome.Done.class);
        assertThat(publisher.calls).contains("publish " + ORDERS);
        await().atMost(Duration.ofSeconds(2)).until(() -> count(OperatorWarningCode.ROUTE_UNPUBLISH_AWAIT_EXPIRED) == 1);
        assertThat(warnings.getFirst().subject()).contains(SELF.id()).contains(ORDERS.toString());
        assertThat(count(OperatorWarningCode.ROUTE_UNPUBLISH_AWAIT_SETTLED)).as("not settled yet").isZero();
    }

    @Test
    void lateSettle_raisesTheRecoveryForTheSameSubject() {
        var removal = suspendWithRemoval();

        await().atMost(Duration.ofSeconds(5)).until(run(LocalStep.PUBLISH_ROUTES)::isResolved);
        removal.succeed(Unit.unit());

        await().atMost(Duration.ofSeconds(2)).until(() -> count(OperatorWarningCode.ROUTE_UNPUBLISH_AWAIT_SETTLED) == 1);
        assertThat(warnings).extracting(OperatorWarning::subject).containsOnly(warnings.getFirst().subject());
    }

    @Test
    void twoPublishesExpiringOnTheSameRemoval_raiseTheWarningOnce() {
        suspendWithRemoval();

        await().atMost(Duration.ofSeconds(5)).until(run(LocalStep.PUBLISH_ROUTES)::isResolved);
        await().atMost(Duration.ofSeconds(2)).until(() -> count(OperatorWarningCode.ROUTE_UNPUBLISH_AWAIT_EXPIRED) == 1);
        await().atMost(Duration.ofSeconds(5)).until(run(LocalStep.PUBLISH_ROUTES)::isResolved);

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> count(OperatorWarningCode.ROUTE_UNPUBLISH_AWAIT_EXPIRED) == 1);
    }

    @Test
    void settlingWithinTheBound_raisesNothing() {
        var removal = suspendWithRemoval();
        var published = run(LocalStep.PUBLISH_ROUTES);

        removal.succeed(Unit.unit());
        await().atMost(Duration.ofSeconds(5)).until(published::isResolved);

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(warnings::isEmpty);
    }
}
