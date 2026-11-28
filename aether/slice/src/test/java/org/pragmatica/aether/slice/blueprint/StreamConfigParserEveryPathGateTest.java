// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #3865: every key the PR refuses as `NotYetAvailable` is refused on EVERY path that admits a stream, owned and external. The
/// gates of `truncation.policy = "until_consumed"` (S3) and `expected_ingress` (S5) sit in `parseStreamDeclaration`, which only the
/// owned branch reaches, so an external stream accepted and ignored both (`expected_ingress` is a regression against the base,
/// `until_consumed` was already accepted there). The per-group `auto_offset_reset = "latest"` (S1a) is read by the external branch
/// but not by the resources parse of an OWNED alias, whose consumer tables are read by `parseConsumers` (refused downstream, pinned).
///
/// Fixed: the external branch runs the same gate as the owned one (`refuseNotYetAvailable`), so the external cells assert the
/// refusal on every parse entry point.
class StreamConfigParserEveryPathGateTest {
    private static final String EXTERNAL = "[streams.orders]\nsource = \"io.acme.inventory:stock-updates:2.0.0\"\n";
    private static final String OWNED = "[streams.orders]\nversion = \"1.0.0\"\n";
    private static final String UNTIL_CONSUMED = "[streams.orders.truncation]\npolicy = \"until_consumed\"\n";
    private static final String LATEST_GROUP = "[streams.orders.consumers.billing]\nauto_offset_reset = \"latest\"\n";
    private static final String DASHED_GROUP = "[streams.orders.consumers.billing]\nauto-offset-reset = \"earliest\"\n";
    private static final ReplicationFactors DEFAULTS = ReplicationFactors.BUILT_IN;

    /// The three entry points that turn a document into streams, each reduced to the cause it refuses with (none: accepted).
    private static List<Option<Cause>> parseEntries(String toml) {
        return List.of(StreamConfigParser.parseResources(toml, DEFAULTS).fold(Option::some, _ -> Option.none()),
                       StreamConfigParser.parseResourcesAggregating(toml, Map.of(), DEFAULTS).fold(Option::some, _ -> Option.none()),
                       StreamConfigParser.parseResourcesPartitioned(toml, Map.of(), DEFAULTS)
                                         .fold(Option::some,
                                               parsed -> Option.from(parsed.rejected().stream().<Cause> map(refused -> refused).findFirst())));
    }

    private static List<String> entryNames() {
        return List.of("parseResources", "parseResourcesAggregating", "parseResourcesPartitioned");
    }

    private static void assertAcceptedOnEveryParseEntry(String toml, String message) {
        var entries = parseEntries(toml);

        for (var i = 0; i < entries.size(); i++) {
            assertThat(entries.get(i)).as(message + " (" + entryNames().get(i) + ")").isEqualTo(Option.none());
        }
    }

    private static void assertRefusedOnEveryParseEntry(String toml, String key, String step) {
        var entries = parseEntries(toml);

        for (var i = 0; i < entries.size(); i++) {
            var entry = entryNames().get(i);
            var cause = entries.get(i).fold(() -> fail(entry + " accepted: " + toml), found -> found);

            assertThat(cause.message()).as(entry + " refuses with the gate for " + key).contains(key).contains(step);
        }
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

    @Test
    void externalExpectedIngress_isRefusedNotYetAvailable_onEveryParseEntry() {
        externalExpectedIngress().forEach(toml -> assertRefusedOnEveryParseEntry(toml, "expected_ingress", "S5"));
    }

    @Test
    void externalUntilConsumed_isRefusedNotYetAvailable_onEveryParseEntry() {
        externalUntilConsumed().forEach(toml -> assertRefusedOnEveryParseEntry(toml, "truncation.policy", "S3"));
    }

    /// Refused downstream (#3865 ruling 2, not a slip): the resources parse of an OWNED alias never reads its consumer tables, so
    /// every parse entry point admits a per-group `latest`; the refusal is `parseConsumers`, which the deploy validator runs for
    /// every owned alias before admitting it (`NotYetAvailableEveryPathTripwireTest` pins that the stream is then not admitted).
    /// The external branch reads its consumer tables at parse and refuses there.
    @Test
    void ownedConsumerLatest_isAdmittedByTheParse_andRefusedByParseConsumers() {
        assertAcceptedOnEveryParseEntry(OWNED + LATEST_GROUP, "owned alias, consumer tables are read by parseConsumers");

        var refusal = StreamConfigParser.parseConsumers(OWNED + LATEST_GROUP, "orders").<Option<Cause>> fold(Option::some, _ -> Option.none());

        assertThat(refusal.map(Cause::message).or("accepted")).contains("auto_offset_reset").contains("S1a");
    }

    /// Controls that keep the tripwires honest: the refusals that already hold, on the same entry points.
    @Test
    void ownedKeys_andExternalConsumerLatest_areRefusedOnEveryParseEntry() {
        assertRefusedOnEveryParseEntry(OWNED + "expected_ingress = \"2MiB/s\"\n", "expected_ingress", "S5");
        assertRefusedOnEveryParseEntry(OWNED + UNTIL_CONSUMED, "truncation.policy", "S3");
        assertRefusedOnEveryParseEntry(EXTERNAL + LATEST_GROUP, "auto_offset_reset", "S1a");
    }

    /// Controls: a plain external and a plain owned stream are accepted by every entry point, so a refusal above is not an
    /// artefact of the fixture.
    @Test
    void plainStreams_areAcceptedOnEveryParseEntry() {
        assertAcceptedOnEveryParseEntry(EXTERNAL, "plain external");
        assertAcceptedOnEveryParseEntry(OWNED, "plain owned");
    }

    /// #3865 round 2: the gate reads the policy the way the parse does ([StreamValues#oneOf] trims and lower-cases), so a
    /// spelling the consumer reads as `until_consumed` is refused on both branches, in the sub-table and the dotted form.
    private static Stream<String> untilConsumedSpellings() {
        return Stream.of("UNTIL_CONSUMED", " Until_Consumed ", "until_consumed ");
    }

    @Test
    void untilConsumed_inAnySpelling_isRefusedOnBothBranches_onEveryParseEntry() {
        untilConsumedSpellings().forEach(spelling -> {
            assertRefusedOnEveryParseEntry(EXTERNAL + "[streams.orders.truncation]\npolicy = \"" + spelling + "\"\n",
                                           "truncation.policy",
                                           "S3");
            assertRefusedOnEveryParseEntry(EXTERNAL + "truncation.policy = \"" + spelling + "\"\n", "truncation.policy", "S3");
            assertRefusedOnEveryParseEntry(OWNED + "[streams.orders.truncation]\npolicy = \"" + spelling + "\"\n",
                                           "truncation.policy",
                                           "S3");
        });
    }

    /// Control for the spellings: `window` in any spelling is not refused, so the refusal above is the policy and not the key.
    @Test
    void windowPolicy_inAnySpelling_isAcceptedOnBothBranches() {
        assertAcceptedOnEveryParseEntry(EXTERNAL + "truncation.policy = \" WINDOW \"\n", "external window");
        assertAcceptedOnEveryParseEntry(OWNED + "[streams.orders.truncation]\npolicy = \" Window \"\n", "owned window");
    }

    /// The gate reads the parsed value: a policy the parse cannot read is refused as malformed on the external branch, as it
    /// is on the owned one, and is never accepted and ignored.
    @Test
    void externalMalformedPolicy_isRefusedAsMalformed_onEveryParseEntry() {
        assertRefusedOnEveryParseEntry(EXTERNAL + "truncation.policy = \"bogus\"\n", "truncation.policy", "window, until_consumed");
    }

    /// F4: on the external branch a structural defect (a dashed consumer key) is reported before the not-yet-available
    /// refusal, as on the owned branch where the gate runs last.
    @Test
    void externalDashedConsumerSection_isReportedBeforeNotYetAvailable() {
        for (var gated : List.of("expected_ingress = \"2MiB/s\"\n", "truncation.policy = \"until_consumed\"\n")) {
            parseEntries(EXTERNAL + gated + DASHED_GROUP).forEach(cause -> assertThat(cause.map(Cause::message).or("accepted"))
                                                                                                    .contains("auto-offset-reset")
                                                                                                    .doesNotContain("not available before release"));
        }
    }
}
