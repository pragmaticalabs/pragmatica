// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.HotWindowDeclaration;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionDeclaration;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.TruncationDeclaration;
import org.pragmatica.aether.slice.TruncationPolicy;
import org.pragmatica.aether.slice.stream.DeclarationDefaults;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.slice.blueprint.StreamConfigParser.parseResources;

/// The `[retention]`, `[truncation]` and `[hot]` sub-sections of a stream: parsed, resolved against `DeclarationDefaults`
/// and stored in `StreamConfig.declaration` (#3333, S2a1 rows 9, 10, 17). Test ids (T-13, ...) are the plan's.
class StreamConfigParserDeclarationTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long MIB = 1024L * 1024;

    private static String section(String body) {
        return "[streams.orders]\nversion = \"1.0.0\"\n" + body;
    }

    private static StreamConfig parsed(String body) {
        return ((StreamResource.Owned) parseResources(section(body), ReplicationFactors.BUILT_IN).unwrap().get("orders")).config();
    }

    private static Cause refusal(String body) {
        return parseResources(section(body), ReplicationFactors.BUILT_IN).fold(cause -> cause,
                                                                               _ -> fail("expected a refusal for: " + body));
    }

    private static StreamDeclaration declaration(String body) {
        return parsed(body).declaration().or(() -> fail("a parsed stream must carry its resolved declaration"));
    }

    private static RetentionDeclaration retention(String body) {
        return declaration(body).retention().or(() -> fail("resolved declaration carries a retention"));
    }

    private static HotWindowDeclaration hot(String body) {
        return declaration(body).hot().or(() -> fail("resolved declaration carries a hot window"));
    }

    private static TruncationDeclaration truncation(String body) {
        return declaration(body).truncation().or(() -> fail("resolved declaration carries a truncation"));
    }

    /// T-13: no `[retention]` block resolves to 24 h and `max(1 GiB, P x 2 x seal_merge_bytes)`.
    @Test
    void noRetentionBlock_resolvesTheDefaultBounds_perPartitionCount() {
        var single = retention("partitions = 1\n");
        var wide = retention("partitions = 1024\n");

        assertThat(single.maxAgeMs().or(0L)).isEqualTo(24L * 60 * 60 * 1000);
        assertThat(single.maxBytes().or(0L)).isEqualTo(GIB);
        assertThat(single.maxCount().isEmpty()).isTrue();
        assertThat(single.mode().or(RetentionMode.UNKNOWN)).isEqualTo(RetentionMode.ANY);
        assertThat(wide.maxBytes().or(0L)).isEqualTo(8 * GIB);
    }

    /// T-18: a `[retention]` block that names no bound is refused, empty or carrying only a `mode`.
    @Test
    void retentionBlockWithNoBound_isRefused() {
        assertThat(refusal("[streams.orders.retention]\n")).isEqualTo(new StreamDeclarationError.RetentionUndeclared("orders"));
        assertThat(refusal("[streams.orders.retention]\nmode = \"all\"\n")).isEqualTo(new StreamDeclarationError.RetentionUndeclared("orders"));
    }

    /// T-23: `max_count` without `max_bytes` is refused (also beside `max_age`); with `max_bytes` it is accepted.
    @Test
    void maxCountWithoutMaxBytes_isRefused_andAcceptedBesideMaxBytes() {
        var countOnly = new StreamDeclarationError.RetentionCountOnly("orders", 100, StreamConfig.DEFAULT.maxEventSizeBytes());

        assertThat(refusal("[streams.orders.retention]\nmax_count = 100\n")).isEqualTo(countOnly);
        assertThat(refusal("[streams.orders.retention]\nmax_count = 100\nmax_age = \"1h\"\n")).isEqualTo(countOnly);
        assertThat(retention("[streams.orders.retention]\nmax_count = 100\nmax_bytes = \"2GB\"\n").maxBytes().or(0L)).isEqualTo(2 * GIB);
    }

    /// A declared block stores exactly the bounds it names, plus the default mode; a named mode wins.
    @Test
    void declaredRetentionBlock_isStoredAsDeclared() {
        var declared = retention("[streams.orders.retention]\nmax_age = \"1h\"\nmode = \"all\"\n");

        assertThat(declared.maxAgeMs().or(0L)).isEqualTo(3_600_000L);
        assertThat(declared.maxBytes().isEmpty()).isTrue();
        assertThat(declared.mode().or(RetentionMode.UNKNOWN)).isEqualTo(RetentionMode.ALL);
        assertThat(retention("[streams.orders.retention]\nmax_age = \"1h\"\n").mode().or(RetentionMode.UNKNOWN)).isEqualTo(RetentionMode.ANY);
    }

    /// T-152: an unknown key inside `[retention]`, `[truncation]` or `[hot]` is refused naming the key and the nearest known one.
    @Test
    void unknownKeyInsideASubSection_isRefused_namingTheNearestKey() {
        assertUnknown("[streams.orders.retention]\nmax_bites = \"1GB\"\n", "retention.max_bites", "retention.max_bytes");
        assertUnknown("[streams.orders.truncation]\npolcy = \"window\"\n", "truncation.polcy", "truncation.policy");
        assertUnknown("[streams.orders.hot]\nmax_cuont = 5\n", "hot.max_cuont", "hot.max_count");
    }

    /// T-152 control: a misspelt sub-section name under `[streams.X]` is still an unknown key, and today's keys still parse.
    @Test
    void misspeltSubSection_isUnknown_andTodaysKeysStillParse() {
        assertUnknown("retension = \"count\"\n", "retension", "retention");
        assertThat(parsed("retention = \"count\"\nretention_value = \"7\"\n").retention().maxCount()).isEqualTo(7L);
    }

    /// T-249: the default `[hot] max_bytes` is `max(4 MiB, max_event_size)`; `max_count` takes the ring default.
    @Test
    void hotWindowDefaults_followMaxEventSize() {
        assertThat(hot("max_event_size = \"8MB\"\n").maxBytes().or(0L)).isEqualTo(8 * MIB);
        assertThat(hot("max_event_size = \"1MB\"\n").maxBytes().or(0L)).isEqualTo(4 * MIB);
        assertThat(hot("max_event_size = \"1MB\"\n").maxCount().or(0L)).isEqualTo(DeclarationDefaults.defaultHotMaxCount());
    }

    @Test
    void declaredHotWindow_isStored_andDefaultsTheKeyItDoesNotName() {
        var declared = hot("[streams.orders.hot]\nmax_count = 20000\n");

        assertThat(declared.maxCount().or(0L)).isEqualTo(20_000L);
        assertThat(declared.maxBytes().or(0L)).isEqualTo(DeclarationDefaults.defaultHotMaxBytes(StreamConfig.DEFAULT.maxEventSizeBytes()));
    }

    @Test
    void truncation_defaultsToWindow_andStoresTheIdleExpiry() {
        assertThat(truncation("partitions = 4\n").policy().or(TruncationPolicy.UNKNOWN)).isEqualTo(TruncationPolicy.WINDOW);
        assertThat(truncation("[streams.orders.truncation]\npolicy = \"window\"\ngroup_idle_expiry = \"1h\"\n").groupIdleExpiryMs().or(0L)).isEqualTo(3_600_000L);
    }

    @Test
    void malformedSubSectionValue_isRefusedAsAMalformedValue() {
        assertThat(refusal("[streams.orders.retention]\nmax_bytes = \"1.5GB\"\n")).isInstanceOf(StreamDeclarationError.MalformedValue.class);
        assertThat(refusal("[streams.orders.retention]\nmax_age = \"1h\"\nmode = \"some\"\n")).isInstanceOf(StreamDeclarationError.MalformedValue.class);
        assertThat(refusal("[streams.orders.truncation]\npolicy = \"forever\"\n")).isInstanceOf(StreamDeclarationError.MalformedValue.class);
    }

    /// T-146 tripwire (asserts CURRENT behaviour, enabled): a well-formed `until_consumed` is refused, never stored.
    @Test
    void untilConsumed_isRefusedAsNotYetAvailable() {
        var message = "S3 landed: delete this tripwire and the S4a test-scoped gate override, and enable T-75..T-98";

        assertThat(refusal("[streams.orders.truncation]\npolicy = \"until_consumed\"\n")).as(message)
                                                                                         .isEqualTo(new StreamDeclarationError.NotYetAvailable("orders",
                                                                                                                                              "truncation.policy",
                                                                                                                                              "S3"));
    }

    /// The gate is checked after every other refusal: a malformed sibling key is reported first.
    @Test
    void untilConsumed_isCheckedAfterEveryOtherRefusal() {
        assertThat(refusal("[streams.orders.truncation]\npolicy = \"until_consumed\"\n[streams.orders.retention]\nmax_age = \"soon\"\n"))
                .isInstanceOf(StreamDeclarationError.MalformedValue.class);
        assertThat(refusal("partitions = 2000\n[streams.orders.truncation]\npolicy = \"until_consumed\"\n"))
                .isInstanceOf(StreamDeclarationError.PartitionsOverCeiling.class);
    }

    private static void assertUnknown(String body, String key, String nearest) {
        assertThat(refusal(body)).isInstanceOf(StreamDeclarationError.UnknownStreamKeys.class);
        var unknown = (StreamDeclarationError.UnknownStreamKeys) refusal(body);

        assertThat(unknown.keys()).containsExactly(key);
        assertThat(unknown.suggestions()).containsEntry(key, nearest);
    }
}
