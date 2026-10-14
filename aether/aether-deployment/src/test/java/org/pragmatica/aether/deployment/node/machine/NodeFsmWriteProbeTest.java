// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G2;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.SELF;

/// NT-134 (spec O1, R-3.4.4, R-3.4.9, R-3.4.11): the write probe and its sensor, on the node FSM, the injected clock and the
/// REAL `KVStore` applier. The write under test is the final ACTIVE batch of N4 (version 4 after LOADING, LOADED and ACTIVATING),
/// attempt 1, unless an arm says otherwise. Probe times are read from `NodeFsmTimings`, never as literals. The Ember half of
/// arm (a) is T on FSM2 (forge-tests).
class NodeFsmWriteProbeTest {
    private static final String SUBMISSIONS = "aether_deployment_fsm_submissions_total";
    private static final StreamRegistryEntry REF = StreamRegistryEntry.blueprint(ResourceAddress.resourceAddress("app:orders:1.0.0").unwrap(),
                                                                                  RetentionPolicy.retentionPolicy(),
                                                                                  Instant.EPOCH);

    // ------------------------------------------------------------------ fixtures

    /// The stream-ref ACQUIRE for `count`, as `acquireCommand` builds it: an absolute value.
    private static LegCommand acquire(int count) {
        return LegCommand.legCommand(LegCommand.LegKind.STREAM_REF,
                                     new KVCommand.Put<AetherKey, AetherValue>(StreamRegistryKey.streamRegistryKey(REF.address()),
                                                                               StreamRegistryValue.streamRegistryValue(REF.withRefCount(count))));
    }

    private static int refCount(NodeHarness h) {
        return h.store.get(StreamRegistryKey.streamRegistryKey(REF.address()))
                .filter(StreamRegistryValue.class::isInstance)
                .map(value -> ((StreamRegistryValue) value).entry().refCount())
                .or(0);
    }

    /// The builder reads the mirror: its ACQUIRE is the stored count plus one.
    private static NodeHarness builders(NodeHarness h) {
        return h.script(LocalStep.BUILD_LEGS, LocalOutcome.done(List.of(NodeHarness.leg(LegCommand.LegKind.TOPIC_SUBSCRIPTION, ARTIFACT))))
                .scriptWith(LocalStep.DESCRIBE_ACTIVE,
                            () -> LocalOutcome.described(List.of("handle"), List.of(acquire(refCount(h) + 1))));
    }

    private static boolean ownActive(NodeHarness.Submitted submitted) {
        return !submitted.submit().isProbe() && NodeHarness.putOf(submitted.submit()).map(row -> row.state() == SliceObservedState.ACTIVE).or(false);
    }

    /// ACTIVE is the goal; the attempt's own ACTIVE submission is lost (never applied, never settled), as T's fault does.
    private static NodeHarness lostFirstActive() {
        var h = builders(new NodeHarness());

        h.writeScript = submitted -> ownActive(submitted) ? NodeHarness.WriteMode.LOST : null;

        return h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
    }

    private static NodeFsmEffect.Submit firstActive(NodeHarness h) {
        return h.effectsOf(NodeFsmEffect.Submit.class).stream()
                .filter(submit -> !submit.isProbe())
                .filter(submit -> NodeHarness.putOf(submit).map(row -> row.state() == SliceObservedState.ACTIVE).or(false))
                .findFirst()
                .orElseThrow();
    }

    private static long versionOf(NodeFsmEffect.Submit submit) {
        return NodeHarness.putOf(submit).map(SliceObservedValue::version).or(0L);
    }

    private static List<NodeFsmEffect.Submit> probes(NodeHarness h) {
        return h.effectsOf(NodeFsmEffect.Submit.class).stream().filter(NodeFsmEffect.Submit::isProbe).toList();
    }

    private static List<NodeFsmEffect.Log> lines(NodeHarness h, String code) {
        return h.effectsOf(NodeFsmEffect.Log.class).stream().filter(log -> log.message().startsWith(code)).toList();
    }

    private static long arm(NodeHarness h, String outcome) {
        return arm(h, "observed", outcome);
    }

    private static long arm(NodeHarness h, String kind, String outcome) {
        return h.effectsOf(NodeFsmEffect.Metric.class).stream()
                .filter(metric -> metric.name().equals(SUBMISSIONS)
                                  && kind.equals(metric.labels().get("kind"))
                                  && outcome.equals(metric.labels().get("outcome")))
                .mapToLong(NodeFsmEffect.Metric::delta)
                .sum();
    }

    private static long probeOffset(NodeHarness h, int index) {
        return h.timings.probeOffsetsMs().get(index);
    }

    private static SliceObservedValue row(NodeHarness h) {
        return h.observed(ARTIFACT).or((SliceObservedValue) null);
    }

    private static boolean noProbeTimers(NodeHarness h) {
        return h.timersOf(TimerId.TimerKind.WRITE_PROBE).isEmpty();
    }

    // ------------------------------------------------------------------ (a)

    /// Red when: the first probe does not resubmit exactly once with the same version and commands; the resubmission does not
    /// confirm the write and complete the chain before the second probe; the attempt counter reads above 1, a second attempt is
    /// submitted, or WRITE_CONFIRM fires.
    @Test
    @DisplayName("NT-134 (a): a lost first submission is resubmitted by the first probe and confirmed within it")
    void nt134a_lostFirstSubmission_isResubmittedAndConfirmedByTheFirstProbe() {
        var h = lostFirstActive();
        var original = firstActive(h);

        assertThat(row(h).state()).as("precondition: the ACTIVATING row committed, the ACTIVE write is lost").isEqualTo(SliceObservedState.ACTIVATING);
        assertThat(h.timersOf(TimerId.TimerKind.WRITE_PROBE)).as("one live probe series").hasSize(1);

        h.advance(probeOffset(h, 0) - 1L);

        assertThat(probes(h)).as("not before the first probe").isEmpty();

        h.advance(1L);

        assertThat(probes(h)).hasSize(1);
        assertThat(probes(h).getFirst().commands()).as("the same observed Put and the same builder output").isEqualTo(original.commands());
        assertThat(probes(h).getFirst().probe()).isEqualTo(1);
        assertThat(probes(h).getFirst().attempt()).as("a probe resubmission carries the attempt's number").isEqualTo(1);
        assertThat(probes(h).getFirst().correlation()).as("its own correlation").isNotEqualTo(original.correlation());

        h.advance(probeOffset(h, 1) - probeOffset(h, 0) - 1L);

        assertThat(row(h).state()).isEqualTo(SliceObservedState.ACTIVE);
        assertThat(row(h).version()).isEqualTo(versionOf(original));
        assertThat(h.record(ARTIFACT).write().isEmpty()).as("confirmed before the second probe").isTrue();
        assertThat(h.record(ARTIFACT).chain().isEmpty()).as("the chain completed").isTrue();
        assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).extracting(NodeFsmEffect.Submit::attempt).as("no second attempt").containsOnly(1);
        assertThat(arm(h, "confirm_timeout")).isZero();
        assertThat(arm(h, "observed_probe", "accepted")).as("the probe's settlement is counted apart").isEqualTo(1L);
        assertThat(arm(h, "accepted")).as("and never on the attempt arm").isEqualTo(arm(control(), "accepted") - 1L);
        assertThat(noProbeTimers(h)).as("the probe series is cancelled by the confirmation").isTrue();
        assertThat(h.timersOf(TimerId.TimerKind.WRITE_CONFIRM)).isEmpty();
    }

    // ------------------------------------------------------------------ (b)

    private static long activeRowsApplied(NodeHarness h) {
        return h.history.stream().filter(applied -> applied.after().map(row -> row.state() == SliceObservedState.ACTIVE).or(false)).count();
    }

    private static long completed(NodeHarness h) {
        return h.effectsOf(NodeFsmEffect.Route.class).stream()
                .filter(route -> route.event() instanceof NodeFsmEffect.RoutedEvent.DeploymentCompleted)
                .count();
    }

    private static NodeHarness control() {
        var h = builders(new NodeHarness());

        return h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
    }

    private static void assertSingleEffect(NodeHarness h, NodeHarness control) {
        assertThat(row(h).version()).as("the row ends at the write's version").isEqualTo(row(control).version());
        assertThat(row(h).state()).isEqualTo(row(control).state());
        assertThat(row(h).methods()).isEqualTo(row(control).methods());
        assertThat(activeRowsApplied(h)).as("one own ObservedPut for the version").isEqualTo(1L);
        assertThat(completed(h)).as("DeploymentCompleted is routed once").isEqualTo(1L);
        assertThat(refCount(h)).as("one ACQUIRE").isEqualTo(refCount(control));
        assertThat(h.record(ARTIFACT).write().isEmpty()).isTrue();
    }

    /// Red when: after the probe's resubmission and the late original both reach the real applier, in this order (probe
    /// first), the row ends at another version, a second own ObservedPut is delivered, DeploymentCompleted is routed twice or the
    /// node-scoped keys differ from a single-submission control run.
    @Test
    @DisplayName("NT-134 (b): probe first, then the held original: no double effect")
    void nt134b_probeFirstThenLateOriginal_hasNoDoubleEffect() {
        var h = lostFirstActive();
        var original = firstActive(h);

        h.advance(probeOffset(h, 0));
        h.applyLate(original);

        assertSingleEffect(h, control());
    }

    @Test
    @DisplayName("NT-134 (b): the held original first, then the probe's resubmission: no double effect")
    void nt134b_originalFirstThenProbe_hasNoDoubleEffect() {
        var h = builders(new NodeHarness());

        h.writeScript = submitted -> ownActive(submitted) || submitted.submit().isProbe() ? NodeHarness.WriteMode.LOST : null;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        var original = firstActive(h);

        h.advance(probeOffset(h, 0));

        var probe = probes(h).getFirst();

        h.applyLate(original);
        h.applyLate(probe);

        assertSingleEffect(h, control());
    }

    /// (b2) Tripwire for L-20, ENABLED, asserting today's behaviour (rebuild semantics): the probe rebuilds its ACQUIRE from the
    /// mirror at probe time (n, so it writes n + 1). The held original commits (n + 1), another holder's ACQUIRE commits
    /// (n + 2), then the probe's resubmission commits: its fenced Put is refused, but its unfenced ACQUIRE writes n + 1.
    @Test
    @DisplayName("NT-134 (b2) L-20 tripwire: the other holder's increment is lost, as with an L-03 retry")
    void nt134b2_l20Tripwire_otherHoldersIncrementIsLost() {
        var h = builders(new NodeHarness());

        h.writeScript = submitted -> ownActive(submitted) || submitted.submit().isProbe() ? NodeHarness.WriteMode.LOST : null;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        var original = firstActive(h);

        h.advance(probeOffset(h, 0));
        h.applyLate(original);
        h.applyBatch(List.of(acquire(2).command()));
        h.applyLate(probes(h).getFirst());

        assertThat(refCount(h)).as("L-20 is open (design P3-Q30): the count reads n + 1 = 1. Once the node batch is all-or-nothing on its fence, "
                                   + "enable the disabled assertion beside this one (the count reads n + 2) and delete this one")
                               .isEqualTo(1);
    }

    @Test
    @Disabled("L-20 / P3-Q30 is open: enable when a refused fenced Put drops its legs too, and delete the tripwire above")
    @DisplayName("NT-134 (b2) L-20 target: the other holder's increment survives")
    void nt134b2_l20Target_otherHoldersIncrementSurvives() {
        var h = builders(new NodeHarness());

        h.writeScript = submitted -> ownActive(submitted) || submitted.submit().isProbe() ? NodeHarness.WriteMode.LOST : null;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        var original = firstActive(h);

        h.advance(probeOffset(h, 0));
        h.applyLate(original);
        h.applyBatch(List.of(acquire(2).command()));
        h.applyLate(probes(h).getFirst());

        assertThat(refCount(h)).isEqualTo(2);
    }

    /// (b3) The first-submission replay is closed. Attempt 1 is dropped. Another node's ACQUIRE of the same ref commits before the
    /// first probe. The probe rebuilds from that mirror and resubmits. Red when the final count is not n + 2, as it is when the probe
    /// replays the first-submission batch.
    @Test
    @DisplayName("NT-134 (b3): the probe rebuilds from the mirror, it does not replay the first-submission batch")
    void nt134b3_probeRebuildsFromTheMirror_notTheFirstBatch() {
        var h = lostFirstActive();
        var original = firstActive(h);

        h.applyBatch(List.of(acquire(1).command()));
        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).hasSize(1);
        assertThat(probes(h).getFirst().commands()).as("rebuilt, so it differs from the first submission's batch").isNotEqualTo(original.commands());
        assertThat(refCount(h)).as("n + 2: the other holder's increment and the node's own").isEqualTo(2);
        assertThat(row(h).state()).isEqualTo(SliceObservedState.ACTIVE);
    }

    /// The legs a probe's rebuild registers equal what one run registers (the rerun of BUILD_LEGS is idempotent): the topic
    /// subscription and the stream registration, as real puts against the real applier, end identical to a run with no loss.
    @Test
    @DisplayName("NT-134 (b): running BUILD_LEGS twice registers the topic and the stream exactly as one run does")
    void nt134b_buildLegsTwice_registersLikeOnce() {
        var method = MethodName.methodName("handle").unwrap();
        var topic = AetherKey.TopicSubscriptionKey.topicSubscriptionKey(REF.address(), ARTIFACT, method, SELF);
        var stream = AetherKey.StreamRegistrationKey.streamRegistrationKey("orders", "stream.orders", ARTIFACT, method);
        var legs = List.of(LegCommand.legCommand(LegCommand.LegKind.TOPIC_SUBSCRIPTION,
                                                 new KVCommand.Put<AetherKey, AetherValue>(topic, AetherValue.TopicSubscriptionValue.topicSubscriptionValue(SELF))),
                           LegCommand.legCommand(LegCommand.LegKind.STREAM_REGISTRATION,
                                                 new KVCommand.Put<AetherKey, AetherValue>(stream,
                                                                                           AetherValue.StreamRegistrationValue.streamRegistrationValue(SELF, "g", false, "Order"))));
        var once = builders(new NodeHarness());
        var twice = lostFirstActive();

        once.script(LocalStep.BUILD_LEGS, LocalOutcome.done(legs)).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
        twice.script(LocalStep.BUILD_LEGS, LocalOutcome.done(legs)).advance(probeOffset(twice, 0));

        assertThat(twice.localSteps().stream().filter(step -> step == LocalStep.BUILD_LEGS).count()).as("run twice").isEqualTo(2L);
        assertThat(once.localSteps().stream().filter(step -> step == LocalStep.BUILD_LEGS).count()).as("run once").isEqualTo(1L);
        assertThat(twice.store.get(topic).or((Object) null)).isNotNull().isEqualTo(once.store.get(topic).or((Object) null));
        assertThat(twice.store.get(stream).or((Object) null)).isNotNull().isEqualTo(once.store.get(stream).or((Object) null));
        assertThat(refCount(twice)).isEqualTo(refCount(once));
    }

    // ------------------------------------------------------------------ (c)

    private static NodeHarness everythingLost() {
        var h = builders(new NodeHarness());

        h.writeScript = submitted -> NodeHarness.putOf(submitted.submit()).map(row -> row.state() == SliceObservedState.ACTIVE).or(false)
                                     ? NodeHarness.WriteMode.LOST
                                     : null;

        return h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
    }

    private static void assertAttemptSchedule(NodeHarness h) {
        var wa = h.timings.writeAttemptMs();
        var own = h.effectsOf(NodeFsmEffect.Submit.class).stream()
                   .filter(submit -> !submit.isProbe())
                   .filter(submit -> NodeHarness.putOf(submit).map(row -> row.state() == SliceObservedState.ACTIVE).or(false))
                   .toList();
        var t0 = h.submitWall.get(own.getFirst().correlation());

        assertThat(own).extracting(NodeFsmEffect.Submit::attempt).containsExactly(1, 2, 3);
        assertThat(own).extracting(submit -> h.submitWall.get(submit.correlation()) - t0).containsExactly(0L, wa, 2L * wa);

        for (var attempt = 1; attempt <= 3; attempt++) {
            var number = attempt;
            var at = t0 + (attempt - 1L) * wa;
            var ofAttempt = probes(h).stream().filter(submit -> submit.attempt() == number).toList();

            assertThat(ofAttempt).as("attempt " + attempt + " has exactly 3 probe resubmissions").hasSize(3);
            assertThat(ofAttempt).extracting(submit -> h.submitWall.get(submit.correlation()) - at)
                                 .containsExactly(probeOffset(h, 0), probeOffset(h, 1), probeOffset(h, 2));
            assertThat(ofAttempt).extracting(NodeFsmEffect.Submit::probe).containsExactly(1, 2, 3);
        }
    }

    /// Red when: attempt 2 or 3 is not submitted exactly `writeAttemptMs` after the previous one, an attempt emits other than 3 probe
    /// resubmissions at the scheduled offsets, or exhaustion does not take the CHAIN path (WriteExhausted, FAILED retryable), as it
    /// does with probes disabled.
    @Test
    @DisplayName("NT-134 (c): the give-up after 3 x 35 s is unchanged, with 3 probes per attempt")
    void nt134c_giveUpIsUnchanged() {
        var h = everythingLost();
        var without = withoutProbes();

        h.advance(3L * h.timings.writeAttemptMs());
        without.advance(3L * without.timings.writeAttemptMs());

        assertAttemptSchedule(h);

        var failed = row(h);
        var baseline = row(without);

        assertThat(failed.state()).as("the same exhaustion outcome as with probes disabled").isEqualTo(baseline.state());
        assertThat(failed.state()).isEqualTo(SliceObservedState.FAILED);
        assertThat(failed.fatal()).isEqualTo(baseline.fatal());
        assertThat(failed.failureReason()).isEqualTo(baseline.failureReason());
        assertThat(failed.version()).isEqualTo(baseline.version());
        assertThat(h.effectsOf(NodeFsmEffect.Submit.class).stream().filter(submit -> !submit.isProbe()).map(NodeFsmEffect.Submit::attempt).toList())
            .isEqualTo(without.effectsOf(NodeFsmEffect.Submit.class).stream().map(NodeFsmEffect.Submit::attempt).toList());
        assertThat(arm(h, "probe_resubmit")).isEqualTo(9L);
        assertThat(arm(h, "confirm_timeout")).isEqualTo(3L);
        assertThat(arm(without, "probe_resubmit")).isZero();
        assertThat(h.timersOf(TimerId.TimerKind.WRITE_CONFIRM)).isEmpty();
        assertThat(noProbeTimers(h)).as("exhaustion cancels the probe series").isTrue();
    }

    private static NodeHarness withoutProbes() {
        var h = builders(new NodeHarness(NodeHarness.SELF_TOKEN, NodeFsmTimings.nodeFsmTimings().withProbeOffsets(List.of())));

        h.writeScript = submitted -> ownActive(submitted) ? NodeHarness.WriteMode.LOST : null;

        return h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
    }

    /// Red when: a probe resubmission's Failed(ApplyTimeout) settlement advances the attempt counter or starts a retry.
    @Test
    @DisplayName("NT-134 (c): a probe resubmission's failed settlement neither consumes an attempt nor starts a retry")
    void nt134c_probeSettlementFailing_doesNotAdvanceTheAttempt() {
        var h = builders(new NodeHarness());

        h.writeScript = submitted -> submitted.submit().isProbe() ? NodeHarness.WriteMode.FAIL : ownActive(submitted) ? NodeHarness.WriteMode.LOST : null;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
        h.advance(3L * h.timings.writeAttemptMs());

        assertAttemptSchedule(h);
        assertThat(arm(h, "observed_probe", "failed")).as("nine probe settlements, counted apart").isEqualTo(9L);
        assertThat(arm(h, "failed")).as("the attempt arm never sees them").isZero();
        assertThat(h.effectsOf(NodeFsmEffect.Submit.class).stream().filter(submit -> !submit.isProbe() && submit.attempt() > 1))
            .as("a retry is submitted only at the attempt bound").hasSize(2);
    }

    // ------------------------------------------------------------------ (d)

    /// Red when: arm (a) does not emit exactly one write-probe-resubmit line naming node, artifact, state, version, attempt, probe
    /// index and elapsed time, one probe_resubmit and no confirm_timeout; or a write confirmed in under 2 s emits any line or arm.
    @Test
    @DisplayName("NT-134 (d): the sensor in arm (a), and its control")
    void nt134d_sensor_armAAndControl() {
        var h = lostFirstActive();

        h.advance(probeOffset(h, 1));

        assertThat(lines(h, "write-probe-resubmit")).hasSize(1);
        assertThat(lines(h, "write-probe-resubmit").getFirst().level()).isEqualTo(NodeFsmEffect.Level.INFO);
        assertThat(lines(h, "write-probe-resubmit").getFirst().message())
            .contains("node " + SELF.id(), "artifact " + ARTIFACT, "state ACTIVE", "version " + versionOf(firstActive(h)), "attempt 1", "probe 1")
            .contains("elapsed_ms " + probeOffset(h, 0));
        assertThat(arm(h, "probe_resubmit")).isEqualTo(1L);
        assertThat(arm(h, "confirm_timeout")).isZero();
        assertThat(lines(h, "write-confirm-timeout")).isEmpty();

        var quick = control();

        quick.advance(2L * h.timings.writeAttemptMs());

        assertThat(lines(quick, "write-probe-resubmit")).isEmpty();
        assertThat(lines(quick, "write-confirm-timeout")).isEmpty();
        assertThat(arm(quick, "probe_resubmit")).isZero();
        assertThat(arm(quick, "confirm_timeout")).isZero();
    }

    /// Red when: with every submission lost, the lines and arms are not 9 probe_resubmit and 3 confirm_timeout, each line naming the
    /// node, artifact, state, version, attempt, probe index and elapsed time.
    @Test
    @DisplayName("NT-134 (d): the sensor in arm (c): 9 probe lines, 3 confirm-timeout lines")
    void nt134d_sensor_armC() {
        var h = everythingLost();

        h.advance(3L * h.timings.writeAttemptMs());

        assertThat(arm(h, "probe_resubmit")).isEqualTo(9L);
        assertThat(arm(h, "confirm_timeout")).isEqualTo(3L);
        assertThat(lines(h, "write-probe-resubmit")).hasSize(9);

        var version = versionOf(firstActive(h));
        var expected = new java.util.ArrayList<String>();

        for (var attempt = 1; attempt <= 3; attempt++) {
            for (var probe = 1; probe <= 3; probe++) {
                expected.add("write-probe-resubmit: node " + SELF.id() + " artifact " + ARTIFACT + " state ACTIVE version " + version
                             + " attempt " + attempt + " probe " + probe + " elapsed_ms " + probeOffset(h, probe - 1));
            }
        }

        assertThat(lines(h, "write-probe-resubmit")).as("every probe line names node, artifact, state, version, attempt, probe index and elapsed time")
                                                       .allSatisfy(log -> assertThat(log.level()).isEqualTo(NodeFsmEffect.Level.INFO))
                                                       .extracting(NodeFsmEffect.Log::message)
                                                       .containsExactlyElementsOf(expected);
        assertThat(lines(h, "write-confirm-timeout")).hasSize(3)
                                                       .allSatisfy(log -> assertThat(log.level()).isEqualTo(NodeFsmEffect.Level.WARN))
                                                       .allSatisfy(log -> assertThat(log.message()).contains("node " + SELF.id(),
                                                                                                             "artifact " + ARTIFACT,
                                                                                                             "state ACTIVE",
                                                                                                             "version " + versionOf(firstActive(h)),
                                                                                                             "attempt ",
                                                                                                             "probe 3",
                                                                                                             "elapsed_ms " + h.timings.writeAttemptMs()));
    }

    // ------------------------------------------------------------------ (e)

    /// Red when: the commit lands but the own notification is missed (the applied mirror already holds the row), and the first probe
    /// resubmits instead of confirming from the mirror, or emits a sensor line.
    @Test
    @DisplayName("NT-134 (e): a row already present in the mirror is confirmed by the probe, with no resubmission and no line")
    void nt134e_rowAlreadyPresent_isConfirmedFromTheMirror() {
        var h = lostFirstActive();
        var original = firstActive(h);
        var committed = NodeHarness.putOf(original).or((SliceObservedValue) null);

        h.model = h.model.withInstance(ARTIFACT, h.model.instance(ARTIFACT).withObserved(org.pragmatica.lang.Option.some(committed)));
        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).as("confirmed from the mirror, not resubmitted").isEmpty();
        assertThat(h.localSteps().stream().filter(step -> step == LocalStep.BUILD_LEGS).count())
            .as("and no rebuild step ran for it: only the original chain's BUILD_LEGS").isEqualTo(1L);
        assertThat(lines(h, "write-probe-resubmit")).isEmpty();
        assertThat(arm(h, "probe_resubmit")).isZero();
        assertThat(h.record(ARTIFACT).write().isEmpty()).isTrue();
        assertThat(noProbeTimers(h)).isTrue();
    }

    /// Red when: a rebuild that settles after the row appeared still submits (the mirror is re-checked on settlement), or the
    /// confirmation is not taken from the mirror.
    @Test
    @DisplayName("NT-134 (e): a rebuild that settles after the row appeared is discarded and the write confirms")
    void nt134e_rebuildSettlingAfterTheRowAppeared_isDiscarded() {
        var h = lostFirstActive();
        var committed = NodeHarness.putOf(firstActive(h)).or((SliceObservedValue) null);

        h.hold(LocalStep.BUILD_LEGS);
        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).as("the rebuild is under way, nothing is resubmitted yet").isEmpty();

        h.model = h.model.withInstance(ARTIFACT, h.model.instance(ARTIFACT).withObserved(org.pragmatica.lang.Option.some(committed)));
        h.holdSteps.clear();
        h.settle(LocalStep.BUILD_LEGS, LocalOutcome.done(List.of()));

        assertThat(probes(h)).as("the row appeared meanwhile: discarded").isEmpty();
        assertThat(h.record(ARTIFACT).write().isEmpty()).as("confirmed from the mirror").isTrue();
        assertThat(lines(h, "write-probe-resubmit")).isEmpty();
    }

    /// Red when: a probe fires while a rebuild is under way and starts a second one, or resubmits behind it.
    @Test
    @DisplayName("NT-134: a probe that fires while a rebuild is under way starts no second rebuild")
    void nt134_probeDuringARebuild_startsNoSecond() {
        var h = lostFirstActive();

        h.hold(LocalStep.BUILD_LEGS);
        h.advance(probeOffset(h, 1));

        assertThat(h.localSteps().stream().filter(step -> step == LocalStep.BUILD_LEGS).count())
            .as("one BUILD_LEGS for the probe, plus the original chain's").isEqualTo(2L);
        assertThat(probes(h)).isEmpty();
    }

    /// Red when: a failed rebuild step resubmits anyway, is silent, or stops the series (the next probe must try again).
    @Test
    @DisplayName("NT-134: a failed rebuild step is logged at WARN, resubmits nothing, and the next probe tries again")
    void nt134_failedRebuild_isLoggedAndRetriedByTheNextProbe() {
        var h = lostFirstActive();

        h.script(LocalStep.BUILD_LEGS, LocalOutcome.failed(Causes.cause("legs unavailable")));
        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).isEmpty();
        assertThat(h.effectsOf(NodeFsmEffect.Log.class)).anyMatch(log -> log.level() == NodeFsmEffect.Level.WARN
                                                                       && log.message().contains("could not rebuild")
                                                                       && log.message().contains("legs unavailable"));

        h.script(LocalStep.BUILD_LEGS, LocalOutcome.done(List.of(NodeHarness.leg(LegCommand.LegKind.TOPIC_SUBSCRIPTION, ARTIFACT))));
        h.advance(probeOffset(h, 1) - probeOffset(h, 0));

        assertThat(probes(h)).hasSize(1);
        assertThat(probes(h).getFirst().probe()).as("the second probe").isEqualTo(2);
        assertThat(row(h).state()).isEqualTo(SliceObservedState.ACTIVE);
    }

    /// Red when: a rebuild step that timed out resubmits anyway (with that step's commands missing) or is silent.
    @Test
    @DisplayName("NT-134: a timed-out rebuild step is logged at WARN and resubmits nothing")
    void nt134_timedOutRebuild_isLoggedAndResubmitsNothing() {
        var h = lostFirstActive();

        h.script(LocalStep.BUILD_LEGS, LocalOutcome.timedOut());
        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).isEmpty();
        assertThat(h.effectsOf(NodeFsmEffect.Log.class)).anyMatch(log -> log.level() == NodeFsmEffect.Level.WARN
                                                                       && log.message().contains("could not rebuild")
                                                                       && log.message().contains("timed out"));
    }

    /// Red when: a probe runs any local step other than the builders its batch came from (for N4's ACTIVE batch: BUILD_LEGS, then
    /// DESCRIBE_ACTIVE), e.g. ACTIVATE or REGISTER_BRIDGE again.
    @Test
    @DisplayName("NT-134: a probe runs only the builder steps of its batch, in order")
    void nt134_probeRunsOnlyTheBuilderSteps() {
        var h = lostFirstActive();
        var before = h.localSteps().size();

        h.advance(probeOffset(h, 0));

        assertThat(h.localSteps().subList(before, h.localSteps().size())).containsExactly(LocalStep.BUILD_LEGS, LocalStep.DESCRIBE_ACTIVE);
    }

    /// Red when: the probe series of an attempt is left armed after the write is exhausted (here every own submission settles
    /// Failed at once, so the last attempt's probes are still pending when it is exhausted).
    @Test
    @DisplayName("NT-134 (f): exhaustion cancels the probe series that is still pending")
    void nt134f_probeAfterExhaustion_isCancelled() {
        var h = builders(new NodeHarness());

        h.writeScript = submitted -> ownActive(submitted) ? NodeHarness.WriteMode.FAIL : null;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);

        assertThat(h.effectsOf(NodeFsmEffect.Submit.class).stream()
                    .filter(submit -> !submit.isProbe())
                    .filter(submit -> NodeHarness.putOf(submit).map(row -> row.state() == SliceObservedState.ACTIVE).or(false))
                    .count())
            .as("precondition: three attempts, all failed at once").isEqualTo(3L);
        assertThat(h.record(ARTIFACT).write().map(write -> write.state() == SliceObservedState.ACTIVE).or(false)).as("precondition: exhausted")
                                                                                                                 .isFalse();
        assertThat(h.timersOf(TimerId.TimerKind.WRITE_PROBE)).as("no probe of the exhausted write is left armed")
                                                             .allMatch(timer -> h.record(ARTIFACT)
                                                                                 .write()
                                                                                 .map(write -> write.correlation() == timer.token())
                                                                                 .or(false));
    }

    /// ACTIVE is the goal, every ACTIVE submission (the attempts' and the probes') is lost, and the chain bound is `chainBoundMs`.
    private static NodeHarness orphanable(long chainBoundMs) {
        var d = NodeFsmTimings.nodeFsmTimings();
        var h = builders(new NodeHarness(NodeHarness.SELF_TOKEN,
                                         new NodeFsmTimings(d.loadMs(),
                                                            d.activateMs(),
                                                            d.deactivateMs(),
                                                            d.unloadMs(),
                                                            chainBoundMs,
                                                            d.activatingCheckMs() * 10L,
                                                            d.tombstoneGraceMs(),
                                                            d.parkedBoundMs(),
                                                            d.writeAttemptMs(),
                                                            d.writeAttempts(),
                                                            d.failedRetryMs(),
                                                            d.failedCycles())));

        h.writeScript = submitted -> NodeHarness.putOf(submitted.submit()).map(row -> row.state() == SliceObservedState.ACTIVE).or(false)
                                     ? NodeHarness.WriteMode.LOST
                                     : null;

        return h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
    }

    /// Red when: a probe that fires after the chain bound dropped the chain still resubmits the orphaned chain write.
    @Test
    @DisplayName("NT-134: a probe of an orphaned chain write (the chain bound expired) resubmits nothing")
    void nt134_orphanedChainWrite_laterProbesResubmitNothing() {
        var h = orphanable(40_000L);
        var t0 = h.wall;

        h.advance(h.timings.chainBoundMs() - 1L);

        var before = probes(h).size();

        assertThat(h.record(ARTIFACT).chain().isPresent()).as("precondition: the chain is alive before its bound").isTrue();
        assertThat(before).as("precondition: probes resubmitted while the chain was alive").isPositive();

        h.advance(2L);

        assertThat(h.record(ARTIFACT).chain().isEmpty()).as("precondition: the bound dropped the chain").isTrue();
        assertThat(h.record(ARTIFACT).write().isPresent()).as("precondition: the ACTIVE write is still in flight").isTrue();

        var builds = h.localSteps().stream().filter(step -> step == LocalStep.BUILD_LEGS).count();

        h.advance(3L * h.timings.writeAttemptMs() - (h.wall - t0));

        assertThat(probes(h)).as("no probe resubmits an orphaned chain write").hasSize(before);
        assertThat(h.localSteps().stream().filter(step -> step == LocalStep.BUILD_LEGS).count()).as("and none reruns its builders").isEqualTo(builds);
    }

    /// Red when: a rebuild that started while the chain was alive, and settles after the chain bound dropped it, resubmits the
    /// orphaned write (with the observed command alone, its legs gone).
    @Test
    @DisplayName("NT-134: a rebuild that settles after the chain was dropped resubmits nothing")
    void nt134_rebuildSettlingAfterTheChainWasDropped_resubmitsNothing() {
        var h = orphanable(10_000L);

        h.hold(LocalStep.BUILD_LEGS);
        h.advance(probeOffset(h, 0));

        assertThat(h.record(ARTIFACT).write().flatMap(write -> write.probing().rebuild()).isPresent()).as("precondition: a rebuild is under way")
                                                                                                         .isTrue();

        h.advance(h.timings.chainBoundMs());

        assertThat(h.record(ARTIFACT).chain().isEmpty()).as("precondition: the bound dropped the chain").isTrue();
        assertThat(h.record(ARTIFACT).write().isPresent()).as("precondition: the ACTIVE write is still in flight").isTrue();

        h.holdSteps.clear();
        h.settle(LocalStep.BUILD_LEGS, LocalOutcome.done(List.of(NodeHarness.leg(LegCommand.LegKind.TOPIC_SUBSCRIPTION, ARTIFACT))));

        assertThat(probes(h)).as("the orphaned write is not resubmitted at the rebuild's settlement").isEmpty();
        assertThat(h.record(ARTIFACT).write().flatMap(write -> write.probing().rebuild()).isEmpty()).as("the rebuild is cleared").isTrue();
    }

    /// Red when: a rebuild started by probe 1 that spans probe 2's tick resubmits with probe 2's index (or its line names probe 2).
    @Test
    @DisplayName("NT-134: a rebuild's resubmission carries the index of the probe that started it")
    void nt134_rebuildSpanningALaterTick_carriesTheStartingProbesIndex() {
        var h = lostFirstActive();

        h.hold(LocalStep.BUILD_LEGS);
        h.advance(probeOffset(h, 1));

        assertThat(h.timers.keySet().stream().filter(timer -> timer.kind() == TimerId.TimerKind.WRITE_PROBE)).as("probe 2 has fired, probe 3 is armed").hasSize(1);
        assertThat(probes(h)).as("the rebuild is still under way").isEmpty();

        h.holdSteps.clear();
        h.settle(LocalStep.BUILD_LEGS, LocalOutcome.done(List.of(NodeHarness.leg(LegCommand.LegKind.TOPIC_SUBSCRIPTION, ARTIFACT))));

        assertThat(probes(h)).hasSize(1);
        assertThat(probes(h).getFirst().probe()).as("started by probe 1, settled after probe 2's tick").isEqualTo(1);
        assertThat(lines(h, "write-probe-resubmit")).hasSize(1);
        assertThat(lines(h, "write-probe-resubmit").getFirst().message()).contains("probe 1 elapsed_ms ");
    }

    // ------------------------------------------------------------------ (f)

    /// Red when: a WRITE_PROBE fires into the model after the write is confirmed, superseded or exhausted, or after a quorum cycle
    /// (a new incarnation).
    @Test
    @DisplayName("NT-134 (f): a probe tick after the write is confirmed is dropped")
    void nt134f_probeAfterConfirmation_isDropped() {
        var h = control();
        var stale = TimerId.timerId(TimerId.TimerKind.WRITE_PROBE, ARTIFACT, h.model.incarnation(), 99L);
        var step = h.fsm.step(h.model, new NodeFsmInput.TimerFired(stale));

        assertThat(step.dropped()).isTrue();
        assertThat(step.effects()).noneMatch(effect -> effect instanceof NodeFsmEffect.Submit || effect instanceof NodeFsmEffect.Log);
        assertThat(noProbeTimers(h)).as("confirmation cancelled the series").isTrue();
    }

    @Test
    @DisplayName("NT-134 (f): the probe of a superseded write is cancelled and its tick dropped")
    void nt134f_probeAfterSupersession_isDropped() {
        var h = lostFirstActive();
        var timer = h.timersOf(TimerId.TimerKind.WRITE_PROBE).getFirst();
        var other = new SliceObservedValue(SliceObservedState.LOADED, versionOf(firstActive(h)), G2, h.process, org.pragmatica.lang.Option.none(), false, 0, List.of(), h.wall);

        h.feed(new NodeFsmInput.ObservedPut(SELF, ARTIFACT, other, h.tokens.self()));

        assertThat(h.timers).as("supersession cancels the superseded write's probe series (the re-evaluated pair has its own)").doesNotContainKey(timer);
        assertThat(h.fsm.step(h.model, new NodeFsmInput.TimerFired(timer)).dropped()).isTrue();
    }

    @Test
    @DisplayName("NT-134 (f): a probe armed before a quorum cycle does not fire into the new incarnation")
    void nt134f_probeAcrossAQuorumCycle_isDropped() {
        var h = lostFirstActive();
        var timer = h.timersOf(TimerId.TimerKind.WRITE_PROBE).getFirst();

        h.feed(new NodeFsmInput.QuorumDisappeared());
        h.establish();

        var step = h.fsm.step(h.model, new NodeFsmInput.TimerFired(timer));

        assertThat(step.dropped()).isTrue();
        assertThat(step.effects()).noneMatch(effect -> effect instanceof NodeFsmEffect.Submit || effect instanceof NodeFsmEffect.Log);
    }

    // ------------------------------------------------------------------ (g)

    /// Red when: a FAILED write that is lost at first submission is not resubmitted by the probe, or the resubmission is not the
    /// same Put.
    @Test
    @DisplayName("NT-134 (g): a lost FAILED write is resubmitted by the probe; the N19 cycles are not started")
    void nt134g_failedWrite_isResubmittedByTheProbe() {
        var h = new NodeHarness();

        h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("corrupt jar")));
        h.writeScript = submitted -> !submitted.submit().isProbe() && NodeHarness.putOf(submitted.submit()).map(row -> row.state() == SliceObservedState.FAILED).or(false)
                                     ? NodeHarness.WriteMode.LOST
                                     : null;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);

        var original = h.submitsOf(SliceObservedState.FAILED).getFirst();

        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).hasSize(1);
        assertThat(probes(h).getFirst().commands()).isEqualTo(original.commands());
        assertThat(row(h).state()).isEqualTo(SliceObservedState.FAILED);
        assertThat(h.timersOf(TimerId.TimerKind.FAILED_RETRY)).isEmpty();
        assertThat(h.timersOf(TimerId.TimerKind.WRITE_CONFIRM)).isEmpty();
        assertThat(noProbeTimers(h)).isTrue();
    }

    /// Red when: a lost tombstone Remove is not resubmitted, witnessed by the same tombstone, by the probe (absence confirms).
    /// Red when: a lost teardown write is resubmitted without running its builder (PREPARE_TEARDOWN) again, or with a batch that
    /// differs from the builder's output.
    @Test
    @DisplayName("NT-134 (g): a lost DEACTIVATING write is rebuilt by running PREPARE_TEARDOWN again")
    void nt134g_teardownWrite_isRebuiltByItsBuilder() {
        var h = builders(new NodeHarness());
        var release = NodeHarness.leg(LegCommand.LegKind.TOPIC_SUBSCRIPTION, ARTIFACT);

        h.script(LocalStep.PREPARE_TEARDOWN, LocalOutcome.done(List.of(release)));
        h.writeScript = submitted -> !submitted.submit().isProbe() && NodeHarness.putOf(submitted.submit()).map(row -> row.state() == SliceObservedState.DEACTIVATING).or(false)
                                     ? NodeHarness.WriteMode.LOST
                                     : null;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
        h.goal(ARTIFACT, SliceGoal.LOADED, G2);

        var original = h.submitsOf(SliceObservedState.DEACTIVATING).getFirst();

        assertThat(original.commands()).as("precondition: the teardown batch carries the builder's command").hasSize(2);

        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).hasSize(1);
        assertThat(probes(h).getFirst().commands()).isEqualTo(original.commands());
        assertThat(h.localSteps().stream().filter(step -> step == LocalStep.PREPARE_TEARDOWN).count())
            .as("the builder ran again for the probe").isEqualTo(2L);
        assertThat(row(h).state()).isIn(SliceObservedState.DEACTIVATING, SliceObservedState.LOADED);
    }

    /// Red when: a probe of a reactivation write keeps the stream-ref ACQUIRE (R-3.4.7: the row is already ACTIVE, no ACQUIRE).
    @Test
    @DisplayName("NT-134 (g): a lost reactivation write is rebuilt without the ACQUIRE")
    void nt134g_reactivationWrite_isRebuiltWithoutTheAcquire() {
        var h = builders(new NodeHarness());

        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
        h.feed(new NodeFsmInput.QuorumDisappeared());
        h.writeScript = submitted -> ownActive(submitted) ? NodeHarness.WriteMode.LOST : null;
        h.clearEffects().establish();

        var original = firstActive(h);

        assertThat(original.commands()).as("precondition: no ACQUIRE rides a reactivation").noneMatch(command -> command instanceof KVCommand.Put<?, ?> put
                                                                                                                  && put.key() instanceof StreamRegistryKey);

        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).hasSize(1);
        assertThat(probes(h).getFirst().commands()).isEqualTo(original.commands());
        assertThat(refCount(h)).as("only the first activation's ACQUIRE").isEqualTo(1);
    }

    @Test
    @DisplayName("NT-134 (g): a lost tombstone Remove is resubmitted by the probe; absence confirms")
    void nt134g_tombstoneRemove_isResubmittedByTheProbe() {
        var h = new NodeHarness();

        h.writeScript = submitted -> !submitted.submit().isProbe() && submitted.submit().commands().getFirst() instanceof KVCommand.Remove
                                     ? NodeHarness.WriteMode.LOST
                                     : null;
        h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
        h.goal(ARTIFACT, SliceGoal.UNLOADED, G2);

        var tombstone = row(h);

        h.clearEffects().advance(h.timings.tombstoneGraceMs());

        var remove = h.effectsOf(NodeFsmEffect.Submit.class).getFirst();

        assertThat(remove.commands().getFirst()).isInstanceOf(KVCommand.Remove.class);
        assertThat(h.observed(ARTIFACT).isPresent()).as("the Remove was lost").isTrue();

        h.advance(probeOffset(h, 0));

        assertThat(probes(h)).hasSize(1);
        assertThat(probes(h).getFirst().commands()).isEqualTo(remove.commands());
        assertThat(((KVCommand.Remove<AetherKey>) probes(h).getFirst().commands().getFirst()).witness().or((Object) null)).isEqualTo(tombstone);
        assertThat(h.observed(ARTIFACT).isEmpty()).as("the probe's witnessed Remove applied").isTrue();
        assertThat(h.record(ARTIFACT).write().isEmpty()).isTrue();
        assertThat(noProbeTimers(h)).isTrue();
    }
}
