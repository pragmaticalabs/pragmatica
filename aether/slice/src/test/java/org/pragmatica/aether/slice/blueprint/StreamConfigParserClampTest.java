// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.List;

import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.aether.slice.stream.DeclarationBounds.Arm;
import org.pragmatica.aether.slice.stream.DeclarationWarning;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.slice.blueprint.StreamConfigParser.parseResources;

/// The hot-window clamp and its warnings, `expected_ingress` and the per-group `auto_offset_reset` gates, and the IEC size
/// spellings (#3333, S2a1-6c; stream-storage-design 2.2). Test ids (T-27, ...) are the plan's.
class StreamConfigParserClampTest {
    private static final long KIB = 1024L;
    private static final long MIB = 1024L * KIB;
    private static final long GIB = 1024L * MIB;
    private static final long DEFAULT_EVENT = StreamConfig.DEFAULT.maxEventSizeBytes();

    private static String section(String body) {
        return "[streams.orders]\nversion = \"1.0.0\"\n" + body;
    }

    private static StreamConfigParser.DeclaredStream declared(String body) {
        var doc = TomlParser.parse(section(body)).unwrap();

        return StreamConfigParser.parseStreamDeclaration(StreamSection.tomlSection(doc, "streams.orders", "orders"),
                                                         ReplicationFactors.BUILT_IN)
                                 .unwrap();
    }

    private static Cause refusal(String body) {
        return parseResources(section(body), ReplicationFactors.BUILT_IN).fold(cause -> cause,
                                                                               _ -> fail("expected a refusal for: " + body));
    }

    private static long hotBytes(String body) {
        return declared(body).config()
                             .declaration()
                             .flatMap(StreamDeclaration::hot)
                             .flatMap(hot -> hot.maxBytes())
                             .or(() -> fail("resolved declaration carries a hot max_bytes"));
    }

    private static long hotCount(String body) {
        return declared(body).config()
                             .declaration()
                             .flatMap(StreamDeclaration::hot)
                             .flatMap(hot -> hot.maxCount())
                             .or(() -> fail("resolved declaration carries a hot max_count"));
    }

    private static long retentionBytes(String body) {
        return declared(body).config()
                             .declaration()
                             .flatMap(StreamDeclaration::retention)
                             .flatMap(retention -> retention.maxBytes())
                             .or(() -> fail("resolved declaration carries a retention max_bytes"));
    }

    private static List<String> codes(String body) {
        return declared(body).declarationWarnings().stream().map(DeclarationWarning::code).toList();
    }

    /// T-27: the refusals run on the declared values, before the clamp. P = 1, 16 MiB events, a share of 8 MiB and a
    /// `hot.max_bytes` of 16 MiB: the share is refused on the event arm; a clamp that ran first would lower the hot window to
    /// 8 MiB and report `HotWindowBelowMaxEvent` instead.
    @Test
    void refusalsPrecedeTheClamp_theEventArmIsReported_andNothingIsWarned() {
        var body = "partitions = 1\nmax_event_size = \"16MiB\"\n[streams.orders.hot]\nmax_bytes = \"16MiB\"\n"
                   + "[streams.orders.retention]\nmax_bytes = \"8MiB\"\n";

        assertThat(refusal(body)).isEqualTo(new StreamDeclarationError.RetentionShareTooSmall("orders",
                                                                                              Arm.EVENT,
                                                                                              "retention.max_bytes",
                                                                                              8 * MIB,
                                                                                              16 * MIB,
                                                                                              16 * MIB));
    }

    /// T-28: `hot.max_bytes` above the replay-window share is lowered to it, stored in the resolved declaration, with exactly
    /// one WARN naming both values and the key that sets the share.
    @Test
    void hotMaxBytes_aboveTheShare_isClampedToIt_withOneWarning() {
        var body = "partitions = 1\n[streams.orders.hot]\nmax_bytes = \"16MiB\"\n[streams.orders.retention]\nmax_bytes = \"8MiB\"\n";
        var warnings = declared(body).declarationWarnings();

        assertThat(hotBytes(body)).isEqualTo(8 * MIB);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().code()).isEqualTo(DeclarationWarning.HOT_WINDOW_CLAMPED);
        assertThat(warnings.getFirst().message()).contains("stream 'orders'")
                                                 .contains("hot.max_bytes = " + 16 * MIB)
                                                 .contains("share of " + 8 * MIB)
                                                 .contains("bytes set by retention.max_bytes")
                                                 .contains("declare hot.max_bytes at or below " + 8 * MIB);
        assertThat(retentionBytes(body)).isEqualTo(8 * MIB);
    }

    /// The boundary: a `hot.max_bytes` equal to the share is not above it, so it is neither lowered nor warned.
    @Test
    void hotMaxBytes_equalToTheShare_isNotClamped() {
        var body = "partitions = 1\n[streams.orders.hot]\nmax_bytes = \"8MiB\"\n[streams.orders.retention]\nmax_bytes = \"8MiB\"\n";

        assertThat(hotBytes(body)).isEqualTo(8 * MIB);
        assertThat(codes(body)).isEmpty();
    }

    /// T-28 (control): a stream with no `[hot]` and no `[retention]` parses with no WARN, also at `partitions = 1024`, where the
    /// scaled default share is 8 MiB per partition and the default hot window is 4 MiB.
    @Test
    void defaults_produceNoWarning() {
        assertThat(codes("")).isEmpty();
        assertThat(codes("partitions = 1024\n")).isEmpty();
        assertThat(hotBytes("partitions = 1024\n")).isEqualTo(4 * MIB);
    }

    /// With no share computable at parse (an age bound only) nothing is clamped, whatever `hot.max_bytes` says.
    @Test
    void ageOnlyRetention_clampsNothing() {
        var body = "partitions = 1\n[streams.orders.hot]\nmax_bytes = \"64MiB\"\n[streams.orders.retention]\nmax_age = \"1h\"\n";

        assertThat(hotBytes(body)).isEqualTo(64 * MIB);
        assertThat(codes(body)).isEmpty();
    }

    /// The share is the one the refusals use: a count bound under `any` sets it through `max_event_size`. 8 192 records of 1 MiB
    /// is 8 GiB, above a 1 GiB byte bound, so the byte bound sets 1 GiB and a 2 GiB hot window is lowered to it and the WARN names
    /// `retention.max_bytes`.
    @Test
    void theClamp_usesTheSameShareAsTheRefusals() {
        var body = "partitions = 1\n[streams.orders.hot]\nmax_bytes = \"2GiB\"\n"
                   + "[streams.orders.retention]\nmax_bytes = \"1GiB\"\nmax_count = 8192\n";

        assertThat(hotBytes(body)).isEqualTo(GIB);
        assertThat(declared(body).declarationWarnings().getFirst().message()).contains("set by retention.max_bytes");
    }

    /// T-150: `hot.max_count` is never clamped by a count share. P = 1, `max_count` = 10 000 (the plan's 1 000 is below the
    /// count arm 6b refuses), `hot.max_count` = 16 384: stored as written, and no WARN names the count.
    @Test
    void hotMaxCount_isNeverClamped() {
        var body = "partitions = 1\n[streams.orders.hot]\nmax_count = 16384\n"
                   + "[streams.orders.retention]\nmax_bytes = \"1GiB\"\nmax_count = 10000\n";

        assertThat(hotCount(body)).isEqualTo(16_384L);
        assertThat(codes(body)).isEmpty();
    }

    /// T-150, reachable form: when the bytes clamp fires beside a `hot.max_count`, the count is stored as written.
    @Test
    void hotMaxCount_staysAsWritten_whenTheBytesAreClamped() {
        var body = "partitions = 1\n[streams.orders.hot]\nmax_bytes = \"16MiB\"\nmax_count = 16384\n"
                   + "[streams.orders.retention]\nmax_bytes = \"8MiB\"\n[streams.orders.truncation]\npolicy = \"window\"\n";

        assertThat(hotBytes(body)).isEqualTo(8 * MIB);
        assertThat(hotCount(body)).isEqualTo(16_384L);
        assertThat(declared(body).config()
                                 .declaration()
                                 .flatMap(StreamDeclaration::truncation)
                                 .flatMap(TruncationDeclaration::policy)).isEqualTo(Option.some(TruncationPolicy.WINDOW));
        assertThat(codes(body)).containsExactly(DeclarationWarning.HOT_WINDOW_CLAMPED);
    }

    /// T-151: `mode = "all"` with `max_count` raises exactly one WARN naming `(max_count / P) x max_event_size`; the same
    /// bounds under `any` raise none.
    @Test
    void modeAll_withMaxCount_warnsOnce_underAnyNever() {
        var bounds = "partitions = 2\n[streams.orders.retention]\nmax_bytes = \"4GiB\"\nmax_count = 20000\n";
        var warnings = declared(bounds + "mode = \"all\"\n").declarationWarnings();

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().code()).isEqualTo(DeclarationWarning.RETENTION_ALL_COUNT_SIZED_BY_MAX_EVENT);
        assertThat(warnings.getFirst().message()).contains("(max_count / P) x max_event_size")
                                                 .contains("10000 x " + DEFAULT_EVENT + " = " + 10_000L * DEFAULT_EVENT);
        assertThat(codes(bounds + "mode = \"any\"\n")).isEmpty();
        assertThat(codes(bounds)).isEmpty();
    }

    /// `all` without `max_count` has nothing to size by `max_event_size`: no WARN.
    @Test
    void modeAll_withoutMaxCount_doesNotWarn() {
        assertThat(codes("partitions = 1\n[streams.orders.retention]\nmax_bytes = \"4GiB\"\nmax_age = \"1h\"\nmode = \"all\"\n")).isEmpty();
    }

    /// The warnings of a TOML document are reported by alias, for the deploy validator to map.
    @Test
    void declarationWarnings_ofADocument_areKeyedByAlias() {
        var toml = section("partitions = 1\n[streams.orders.hot]\nmax_bytes = \"16MiB\"\n[streams.orders.retention]\nmax_bytes = \"8MiB\"\n")
                   + "\n[streams.billing]\nversion = \"1.0.0\"\n";
        var byAlias = StreamConfigParser.declarationWarnings(toml, ReplicationFactors.BUILT_IN);

        assertThat(byAlias.keySet()).containsExactly("orders");
        assertThat(byAlias.get("orders")).extracting(DeclarationWarning::code).containsExactly(DeclarationWarning.HOT_WINDOW_CLAMPED);
    }

    /// Row 17 tripwire (ENABLED, current behaviour): `expected_ingress` is a known key that is refused whatever its value,
    /// naming the footprint step that reads it, and nothing is stored.
    @Test
    void expectedIngress_isRefusedNotYetAvailable_naming_theFootprintStep() {
        List.of("\"2MiB/s\"", "\"2MiB\"", "\"0MiB/s\"", "\"not a rate\"", "17").forEach(value -> {
            var cause = refusal("expected_ingress = " + value + "\n");

            assertThat(cause).as("reader landed: delete this tripwire and enable T-153 (value " + value + ")")
                             .isEqualTo(new StreamDeclarationError.NotYetAvailable("orders", "expected_ingress", "S5"));
            assertThat(cause.message()).contains("expected_ingress").contains("S5");
        });
    }

    /// The gate runs after every other refusal, as the T-146 gate does: a section malformed another way reports that first.
    @Test
    void expectedIngress_gateRunsAfterTheOtherRefusals() {
        assertThat(refusal("partitions = \"many\"\nexpected_ingress = \"2MiB/s\"\n")).isInstanceOf(StreamDeclarationError.NotAnInteger.class);
    }

    /// T-153 (`@Disabled` beside the tripwire above): once the footprint step reads the key, `<size>/s` parses to bytes per second
    /// and a size without `/s`, zero and a negative rate are refused naming the key.
    @Disabled("the footprint step (S5) reads expected_ingress: enable this, delete the tripwire above")
    @Test
    void expectedIngress_parsesBytesPerSecond_andRefusesTheMalformed() {
        assertThat(declared("expected_ingress = \"2MiB/s\"\n").config()
                                                              .declaration()
                                                              .flatMap(StreamDeclaration::expectedIngressBytesPerSecond))
                .isEqualTo(Option.some(2_097_152L));
        List.of("\"2MiB\"", "\"0MiB/s\"", "\"-1MiB/s\"").forEach(value -> assertThat(refusal("expected_ingress = " + value + "\n").message())
                .contains("expected_ingress"));
    }

    /// The stored declaration never carries the key: `expectedIngressBytesPerSecond` is `none` for every accepted section.
    @Test
    void acceptedSection_storesNoExpectedIngress() {
        assertThat(declared("").config().declaration().flatMap(StreamDeclaration::expectedIngressBytesPerSecond)).isEqualTo(Option.none());
    }

    private static String consumerTable(String line) {
        return "[streams.orders]\nversion = \"1.0.0\"\n\n[streams.orders.consumers.billing]\n" + line + "\n";
    }

    private static String externalConsumer(String line) {
        return "[streams.orders]\nsource = \"io.acme.inventory:stock-updates:2.0.0\"\n\n[streams.orders.consumers.billing]\n" + line + "\n";
    }

    private static void assertMalformedOnEveryPath(String line) {
        assertThat(refused(documentPath(line))).as(line).isInstanceOf(StreamDeclarationError.MalformedValue.class);
        assertThat(refused(sectionPath(line))).as(line).isInstanceOf(StreamDeclarationError.MalformedValue.class);
        assertThat(refused(externalPath(line))).as(line).isInstanceOf(StreamDeclarationError.MalformedValue.class);
    }

    private static Cause refused(Option<Cause> outcome) {
        return outcome.fold(() -> fail("expected a refusal"), cause -> cause);
    }

    private static Option<Cause> documentPath(String line) {
        return StreamConfigParser.parseConsumers(consumerTable(line), "orders").fold(Option::some, _ -> Option.none());
    }

    private static Option<Cause> sectionPath(String line) {
        var doc = TomlParser.parse(consumerTable(line)).unwrap();

        return StreamConfigParser.parseConsumerSection(StreamSection.tomlSection(doc, "streams.orders.consumers.billing", "billing"))
                                 .fold(Option::some, _ -> Option.none());
    }

    private static Option<Cause> externalPath(String line) {
        return parseResources(externalConsumer(line), ReplicationFactors.BUILT_IN).fold(Option::some, _ -> Option.none());
    }

    /// T-147 tripwire (ENABLED, current behaviour): a per-group `auto_offset_reset = "latest"` is refused `NotYetAvailable`,
    /// naming S1a, on every path that reads a consumer table: the document path, the section path and an external stream's table.
    @Test
    void consumerLatest_isRefusedNotYetAvailable_onEveryPath() {
        var line = "auto_offset_reset = \"latest\"";
        var message = "S1a landed: delete this tripwire and enable T-44, T-48";

        assertThat(documentPath(line)).as(message)
                                      .isEqualTo(Option.some(new StreamDeclarationError.NotYetAvailable("orders",
                                                                                                        "consumers.billing.auto_offset_reset",
                                                                                                        "S1a")));
        assertThat(sectionPath(line)).as(message)
                                     .isEqualTo(Option.some(new StreamDeclarationError.NotYetAvailable("billing", "auto_offset_reset", "S1a")));
        assertThat(externalPath(line)).as(message)
                                      .isEqualTo(Option.some(new StreamDeclarationError.NotYetAvailable("orders",
                                                                                                        "consumers.billing.auto_offset_reset",
                                                                                                        "S1a")));
    }

    /// T-147: `earliest` (any case) parses on every path and is not stored; anything else is a malformed value.
    @Test
    void consumerEarliest_parses_andAnythingElseIsMalformed() {
        List.of("auto_offset_reset = \"earliest\"", "auto_offset_reset = \"EARLIEST\"").forEach(line -> {
            assertThat(documentPath(line)).isEqualTo(Option.none());
            assertThat(sectionPath(line)).isEqualTo(Option.none());
            assertThat(externalPath(line)).isEqualTo(Option.none());
        });
        // #3865 p21: "none" is the adjacent value; an offset-reset set widened to it would accept and ignore it.
        List.of("auto_offset_reset = \"newest\"", "auto_offset_reset = \"none\"")
            .forEach(StreamConfigParserClampTest::assertMalformedOnEveryPath);
    }

    /// The key is stored nowhere: a group declaring `earliest` parses to the default consumer configuration.
    @Test
    void consumerEarliest_changesNothingInTheConsumerConfig() {
        var plain = StreamConfigParser.parseConsumers(consumerTable("batch_size = 1"), "orders").unwrap().get("billing");
        var reset = StreamConfigParser.parseConsumers(consumerTable("auto_offset_reset = \"earliest\""), "orders").unwrap().get("billing");

        assertThat(reset).isEqualTo(plain);
    }

    /// The dashed spelling is refused like every consumer key, naming the snake_case one.
    @Test
    void consumerDashedOffsetReset_isRefused_namingTheSnakeCaseSpelling() {
        assertThat(refused(documentPath("auto-offset-reset = \"earliest\"")).message())
                .contains("'auto-offset-reset' (did you mean 'auto_offset_reset'?)");
    }

    /// The IEC spellings are the same sizes as `KB`/`MB`/`GB` (1 KiB = 1 024 B), in any case, on every size key.
    @Test
    void iecSizeSpellings_areTheSameSizesAsKbMbGb() {
        assertThat(StreamValues.size("orders", "max_event_size", "64KiB").unwrap()).isEqualTo(64 * KIB);
        assertThat(StreamValues.size("orders", "max_event_size", "2MiB").unwrap()).isEqualTo(2 * MIB);
        assertThat(StreamValues.size("orders", "max_event_size", "3gib").unwrap()).isEqualTo(3 * GIB);
        assertThat(StreamValues.size("orders", "max_event_size", "2 MiB").unwrap()).isEqualTo(StreamValues.size("orders", "k", "2MB").unwrap());
        assertThat(StreamValues.size("orders", "max_event_size", "1TiB").isFailure()).isTrue();
        assertThat(StreamValues.SIZE_FORM).contains("KiB/MiB/GiB");
    }
}
