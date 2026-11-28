// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #3865, the deploy entry points: every key refused as `NotYetAvailable` is refused by `validate` and by `partition` for an
/// external stream as for an owned one. The gates of `expected_ingress` and `truncation.policy = "until_consumed"` are not on the
/// external branch of the parser, so both entry points accepted them (the parse-level cells are in
/// `StreamConfigParserEveryPathGateTest`). Each slipping cell is an ENABLED tripwire asserting today's acceptance with the real
/// assertion `@Disabled` beside it.
class NotYetAvailableEveryPathTripwireTest {
    private static final Artifact APP_ARTIFACT = Artifact.artifact("com.example:my-app:1.0.0").unwrap();
    private static final String EXTERNAL = "[streams.orders]\nsource = \"io.acme.inventory:stock-updates:2.0.0\"\n";
    private static final String OWNED = "[streams.orders]\nversion = \"1.0.0\"\n";
    private static final String UNTIL_CONSUMED = "[streams.orders.truncation]\npolicy = \"until_consumed\"\n";
    private static final String LATEST_GROUP = "[streams.orders.consumers.billing]\nauto_offset_reset = \"latest\"\n";
    private static final String GATE = "[streams.orders]::" + StreamResourceValidator.RULE_NOT_YET_AVAILABLE;

    /// `validate`'s refusals as `field::rule`; empty when it accepts.
    private static List<String> validateRefusals(String toml) {
        return StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT)
                                      .fold(cause -> refusals(((StreamValidationFailures) cause).failures()), _ -> List.of());
    }

    /// `partition`'s rejected sections (or gating failures) as `field::rule`; empty when it accepts every section.
    private static List<String> partitionRefusals(String toml) {
        return StreamResourceValidator.partition(Option.some(toml), APP_ARTIFACT, Map.of(), ReplicationContext.BUILT_IN)
                                      .fold(cause -> refusals(((StreamValidationFailures) cause).failures()),
                                            partition -> refusals(partition.rejected()));
    }

    private static List<String> refusals(List<StreamValidationFailure> failures) {
        return failures.stream().map(failure -> failure.field() + "::" + failure.rule()).toList();
    }

    private static Stream<String> externalExpectedIngress() {
        return Stream.of("expected_ingress = \"2MiB/s\"\n",
                         "expected_ingress = 5\n",
                         "expected_ingress = \"\"\n",
                         "role = \"consumer\"\nexpected_ingress = \"2MiB/s\"\n")
                     .map(lines -> EXTERNAL + lines);
    }

    private static Stream<String> externalUntilConsumed() {
        return Stream.of(EXTERNAL + UNTIL_CONSUMED,
                         EXTERNAL + "role = \"consumer\"\n" + UNTIL_CONSUMED,
                         EXTERNAL + "truncation.policy = \"until_consumed\"\n");
    }

    private static void assertAccepted(String toml, String message) {
        assertThat(validateRefusals(toml)).as(message + " (validate)").isEmpty();
        assertThat(partitionRefusals(toml)).as(message + " (partition)").isEmpty();
    }

    private static void assertRefusedAsNotYetAvailable(String toml) {
        assertThat(validateRefusals(toml)).as("validate refuses: " + toml).contains(GATE);
        assertThat(partitionRefusals(toml)).as("partition refuses: " + toml).contains(GATE);
    }

    /// Tripwire (ENABLED, current behaviour): `validate` and `partition` accept `expected_ingress` on an external stream; the base
    /// refused it (an unknown key). Fixed: delete this and enable the test below.
    @Test
    void externalExpectedIngress_isAcceptedTodayByValidateAndPartition_tripwire() {
        externalExpectedIngress().forEach(toml -> assertAccepted(toml,
                                                                 "#3865 fixed: delete this tripwire and enable externalExpectedIngress_isRefusedByValidateAndPartition for: "
                                                                 + toml));
    }

    @Disabled("#3865 lane B: the external branch must run the NotYetAvailable gate for expected_ingress; enable this, delete the tripwire above")
    @Test
    void externalExpectedIngress_isRefusedByValidateAndPartition() {
        externalExpectedIngress().forEach(NotYetAvailableEveryPathTripwireTest::assertRefusedAsNotYetAvailable);
    }

    /// Tripwire (ENABLED, current behaviour): `validate` and `partition` accept `truncation.policy = "until_consumed"` on an
    /// external stream, as the base did. Fixed: delete this and enable the test below.
    @Test
    void externalUntilConsumed_isAcceptedTodayByValidateAndPartition_tripwire() {
        externalUntilConsumed().forEach(toml -> assertAccepted(toml,
                                                               "#3865 fixed: delete this tripwire and enable externalUntilConsumed_isRefusedByValidateAndPartition for: "
                                                               + toml));
    }

    @Disabled("#3865 lane B: the external branch must run the NotYetAvailable gate for truncation.policy; enable this, delete the tripwire above")
    @Test
    void externalUntilConsumed_isRefusedByValidateAndPartition() {
        externalUntilConsumed().forEach(NotYetAvailableEveryPathTripwireTest::assertRefusedAsNotYetAvailable);
    }

    /// #3865 ruling 2, "refused downstream": the resources parse of an OWNED alias admits a per-group `latest` (it never reads
    /// the alias's consumer tables), so the refusal that keeps the stream out is `guardConsumerConfigs` (via `parseConsumers`) in
    /// `partition` (`acceptOrReject`) and in `validate` (`guardInertConfig`). `BlueprintService` is the only production caller and
    /// uses `partition`; the stream is admitted only through `accepted`, which the guard precedes. Pinned: the alias is not
    /// admitted by either entry point, and the refusal is the NotYetAvailable gate.
    @Test
    void ownedConsumerLatest_isRefusedDownstream_andTheStreamIsNotAdmitted() {
        var toml = OWNED + LATEST_GROUP;
        var admittedByValidate = StreamResourceValidator.validate(Option.some(toml), APP_ARTIFACT)
                                                        .fold(_ -> Map.<String, StreamResource> of(), validated -> validated.resources());
        var admittedByPartition = StreamResourceValidator.partition(Option.some(toml),
                                                                    APP_ARTIFACT,
                                                                    Map.of(),
                                                                    ReplicationContext.BUILT_IN)
                                                         .fold(_ -> Map.<String, StreamResource> of(), partition -> partition.accepted());

        assertThat(admittedByValidate).as("validate admits no stream").isEmpty();
        assertThat(admittedByPartition).as("partition admits no stream").isEmpty();
        assertThat(validateRefusals(toml)).contains(GATE);
        assertThat(partitionRefusals(toml)).contains(GATE);
    }

    /// Controls: the refusals that already hold on both entry points, and the plain streams that must stay accepted, so the
    /// tripwires above are not artefacts of the fixture.
    @Test
    void refusalsThatHold_andPlainStreams() {
        assertRefusedAsNotYetAvailable(OWNED + "expected_ingress = \"2MiB/s\"\n");
        assertRefusedAsNotYetAvailable(OWNED + UNTIL_CONSUMED);
        assertRefusedAsNotYetAvailable(EXTERNAL + LATEST_GROUP);
        assertRefusedAsNotYetAvailable(OWNED + LATEST_GROUP);
        assertAccepted(EXTERNAL, "plain external");
        assertAccepted(OWNED, "plain owned");
    }
}
