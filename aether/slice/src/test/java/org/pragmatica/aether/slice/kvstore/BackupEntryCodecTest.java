// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupDocument;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupError;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.aether.slice.kvstore.BackupFixtures.Fixture;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.kvstore.BackupFixtures.AWKWARD;
import static org.pragmatica.aether.slice.kvstore.BackupFixtures.FIXTURES;

/// The backup document round-trips every backed-up key type exactly, filters runtime state out, and
/// reports every malformed line precisely instead of throwing.
class BackupEntryCodecTest {
    private static final BackupEntryCodec CODEC = BackupEntryCodec.backupEntryCodec(BackupFixtures.codec());
    private static final BackupHeader HEADER = BackupHeader.backupHeader(42L, Option.some(AWKWARD));
    private static final NodeId NODE = NodeId.nodeId("node-1")
                                             .unwrap();

    @Nested
    class RoundTrip {
        /// Every [ClusterStateKey] type needs a fixture. A new cluster-state key with none fails HERE,
        /// by name — which is what keeps the codec's parser table and this coverage exhaustive.
        @Test
        void fixtures_coverEveryBackedUpKeyType() {
            var covered = FIXTURES.stream()
                                  .map(fixture -> fixture.key()
                                                         .getClass())
                                  .collect(Collectors.toSet());
            var missing = Arrays.stream(ClusterStateKey.class.getPermittedSubclasses())
                                .filter(type -> !covered.contains(type))
                                .map(Class::getSimpleName)
                                .sorted()
                                .toList();

            assertThat(missing).as("backed-up key types without a round-trip fixture")
                               .isEmpty();
        }

        @Test
        void encode_thenDecode_restoresEveryEntryExactly() {
            var entries = fixtureMap();

            CODEC.encode(HEADER, entries)
                 .flatMap(CODEC::decode)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(document -> assertThat(document.entries()).containsExactlyInAnyOrderEntriesOf(entries))
                 .onSuccess(document -> assertThat(document.header()).isEqualTo(HEADER));
        }

        /// Each type on its own, so a failure names the type rather than drowning in one big map.
        @Test
        void encode_thenDecode_restoresEachKeyTypeOnItsOwn() {
            var failures = FIXTURES.stream()
                                   .filter(fixture -> !roundTripsAlone(fixture))
                                   .map(fixture -> fixture.key()
                                                          .asString())
                                   .toList();

            assertThat(failures).as("fixtures that did not round-trip alone")
                                .isEmpty();
        }

        /// Byte-exact: the restored state renders to the identical document, so nothing was lost or
        /// normalised on the way through.
        @Test
        void encode_ofDecodedDocument_isByteIdentical() {
            var first = CODEC.encode(HEADER, fixtureMap())
                             .unwrap();
            var second = CODEC.decode(first)
                              .flatMap(document -> CODEC.encode(document.header(), document.entries()))
                              .unwrap();

            assertThat(second).isEqualTo(first);
        }

        /// The value column IS the generated wire codec's bytes — not a second encoding of the record.
        @Test
        void encodedValue_isTheConsensusWireBytes() {
            var fixture = FIXTURES.getFirst();
            var expected = Base64.getEncoder()
                                 .encodeToString(BackupFixtures.codec()
                                                               .canonical()
                                                               .encode(fixture.value()));
            var document = CODEC.encode(HEADER, Map.of(fixture.key(), fixture.value()))
                                .unwrap();

            assertThat(entryLines(document)).containsExactly(expected + " " + fixture.key()
                                                                                    .asString());
        }

        @Test
        void encode_escapesNewlinesAndBackslashes_soEveryEntryStaysOnOneLine() {
            var document = CODEC.encode(HEADER, fixtureMap())
                                .unwrap();

            assertThat(entryLines(document)).hasSize(FIXTURES.size());
            assertThat(document).contains("\\nand a newline\\\\");
        }

        @Test
        void header_roundTrips_withoutAnIncarnation() {
            var header = BackupHeader.backupHeader(0L, Option.none());

            CODEC.encode(header, Map.of())
                 .flatMap(CODEC::decode)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(document -> assertThat(document.header()).isEqualTo(header))
                 .onSuccess(document -> assertThat(document.entries()).isEmpty());
        }

        @Test
        void header_blankIncarnation_isNormalisedToAbsent() {
            assertThat(BackupHeader.backupHeader(1L, Option.some("  "))
                                   .clusterIncarnation()).isEqualTo(Option.none());
        }
    }

    @Nested
    class Filtering {
        /// Runtime keys and node-scoped config never reach the document. If a runtime key were
        /// reclassified as cluster state, the codec would try to back it up and this test would fail.
        @Test
        void encode_excludesRuntimeAndNodeScopedEntries() {
            var entries = new HashMap<AetherKey, AetherValue>(fixtureMap());

            entries.putAll(runtimeEntries());

            CODEC.encode(HEADER, entries)
                 .flatMap(CODEC::decode)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(document -> assertThat(document.entries()).containsExactlyInAnyOrderEntriesOf(fixtureMap()));
        }

        @Test
        void isBackedUp_isFalse_forEveryRuntimeFixture() {
            assertThat(runtimeEntries().keySet()).noneMatch(BackupEntryCodec::isBackedUp);
            assertThat(FIXTURES).allMatch(fixture -> BackupEntryCodec.isBackedUp(fixture.key()));
        }

        /// A hand-edited document cannot smuggle node-scoped state into a restore.
        @Test
        void decode_refusesANodeScopedConfigEntry() {
            var key = ConfigKey.forKey("orders.banner", NODE);
            var document = documentWithLines(line(ConfigValue.configValue("orders.banner", "x"), key.asString()));

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOfSatisfying(BackupError.KeyNotBackedUp.class,
                                                                                error -> assertThat(error.lineNumber()).isEqualTo(5));
        }

        @Test
        void decode_refusesARuntimeKeyType() {
            var key = topicSubscriptionKey();
            var document = documentWithLines(line(TopicSubscriptionValue.topicSubscriptionValue(NODE), key.asString()));

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.UnrecognisedKey.class);
        }
    }

    @Nested
    class Failures {
        @Test
        void decode_reportsEveryBadEntry_withItsLineNumber() {
            var good = FIXTURES.getFirst();
            var document = documentWithLines(line(good.value(), good.key()
                                                                    .asString()),
                                             "!!!notbase64 config/orders.banner",
                                             line(good.value(), "no-such-section/x"),
                                             "missing-separator");
            var failures = failures(CODEC.decode(document));

            assertThat(failures).extracting(Cause::getClass)
                                .containsExactlyInAnyOrder(BackupError.ValueDecodingFailed.class,
                                                           BackupError.UnrecognisedKey.class,
                                                           BackupError.MalformedEntry.class);
            assertThat(failures).extracting(Cause::message)
                                .anyMatch(message -> message.startsWith("Line 6:"))
                                .anyMatch(message -> message.startsWith("Line 7:"))
                                .anyMatch(message -> message.startsWith("Line 8:"));
        }

        @Test
        void decode_entryCountMismatch_isRefused() {
            var document = CODEC.encode(HEADER, fixtureMap())
                                .unwrap();
            var truncated = document.substring(0, document.lastIndexOf('\n', document.length() - 2) + 1);

            assertThat(failures(CODEC.decode(truncated))).singleElement()
                                                         .isInstanceOf(BackupError.EntryCountMismatch.class);
        }

        @Test
        void decode_unsupportedFormatVersion_isRefused() {
            var document = CODEC.encode(HEADER, Map.of())
                                .unwrap()
                                .replace("aether-kv-backup/1", "aether-kv-backup/2");

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.UnsupportedFormatVersion.class);
        }

        @Test
        void decode_missingHeader_namesEveryMissingField() {
            assertThat(failures(CODEC.decode(""))).hasSize(4)
                                                  .allMatch(BackupError.MissingHeaderLine.class::isInstance);
        }

        @Test
        void decode_malformedHeaderValues_areReported() {
            var document = "aether-kv-backup/1\nrevision=abc\nincarnation=bad\\q\nentries=-1\n";

            assertThat(failures(CODEC.decode(document))).hasSize(3)
                                                        .allMatch(BackupError.MalformedHeaderValue.class::isInstance);
        }

        /// A key that parses but would re-render differently is not the key that was backed up.
        @Test
        void decode_nonCanonicalKey_isRefused() {
            var fixture = FIXTURES.stream()
                                  .filter(candidate -> candidate.key() instanceof ClusterConfigKey)
                                  .findFirst()
                                  .orElseThrow();
            var document = documentWithLines(line(fixture.value(), "cluster-config/000"));

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.NonCanonicalKey.class);
        }

        /// Bytes past the end of a value would be ignored by the binary reader; re-encoding catches them.
        @Test
        void decode_valueWithTrailingBytes_isRefused() {
            var fixture = FIXTURES.getFirst();
            var bytes = BackupFixtures.codec()
                                      .canonical()
                                      .encode(fixture.value());
            var padded = Arrays.copyOf(bytes, bytes.length + 1);
            var document = documentWithLines(Base64.getEncoder()
                                                   .encodeToString(padded) + " " + fixture.key()
                                                                                          .asString());

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.ValueNotCanonical.class);
        }

        @Test
        void decode_valueThatIsNotAKvValue_isRefused() {
            var bytes = BackupFixtures.codec()
                                      .encode("just a string");
            var document = documentWithLines(Base64.getEncoder()
                                                   .encodeToString(bytes) + " config/orders.banner");

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.ValueDecodingFailed.class);
        }

        @Test
        void decode_duplicateKey_isRefused() {
            var fixture = FIXTURES.getFirst();
            var entry = line(fixture.value(), fixture.key()
                                                     .asString());

            assertThat(failures(CODEC.decode(documentWithLines(entry, entry)))).singleElement()
                                                                                 .isInstanceOf(BackupError.DuplicateKey.class);
        }

        @Test
        void decode_invalidEscapeInKey_isRefused() {
            var fixture = FIXTURES.getFirst();
            var unescapedLine = line(fixture.value(), "") + "config/bad\\escape";
            var document = documentWithLines(unescapedLine);

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.MalformedEntry.class);
        }

        /// Hostile input surfaces as a failure value, never as an exception out of `decode`.
        @Test
        void decode_hostileInput_neverThrows() {
            var inputs = List.of("\u0000\u0001",
                                 "aether-kv-backup/1\nrevision=1\nincarnation=\nentries=1\n \n",
                                 "aether-kv-backup/1\nrevision=1\nincarnation=\nentries=1\nAAAA slices/\n",
                                 "aether-kv-backup/1\nrevision=1\nincarnation=\nentries=1\n//// topic-sub/a/b/c/d/e\n",
                                 "aether-kv-backup/99999999999\n");

            assertThat(inputs).allMatch(input -> Result.lift(() -> CODEC.decode(input))
                                                       .flatMap(result -> result)
                                                       .isFailure());
        }

        @Test
        void encode_valueWithoutARegisteredCodec_failsNamingEveryKey() {
            var bare = BackupEntryCodec.backupEntryCodec(FrameworkCodecs.frameworkCodecs());
            var failures = failures(bare.encode(HEADER, fixtureMap()));

            assertThat(failures).hasSize(FIXTURES.size())
                                .allMatch(BackupError.ValueEncodingFailed.class::isInstance);
        }

        /// A cluster-wide config key named like a node-scoped one renders to a string that parses as the
        /// node-scoped key. Taking that backup would restore a different key, so it is refused up front.
        @Test
        void encode_keyThatDoesNotParseBackToItself_isRefused() {
            var key = ConfigKey.forKey("node/node-1/orders.banner");

            assertThat(failures(CODEC.encode(HEADER, Map.of(key, ConfigValue.configValue("k", "v"))))).singleElement()
                                                                                                    .isInstanceOf(BackupError.KeyNotRoundTrippable.class);
        }
    }

    // --- helpers ---

    private static Map<AetherKey, AetherValue> fixtureMap() {
        return FIXTURES.stream()
                       .collect(Collectors.toMap(Fixture::key, Fixture::value, (first, _) -> first, LinkedHashMap::new));
    }

    private static Map<AetherKey, AetherValue> runtimeEntries() {
        return Map.of(topicSubscriptionKey(),
                      TopicSubscriptionValue.topicSubscriptionValue(NODE),
                      ConfigKey.forKey("orders.banner", NODE),
                      ConfigValue.configValue("orders.banner", "node override"),
                      GossipKeyRotationKey.gossipKeyRotationKey(),
                      GossipKeyRotationValue.gossipKeyRotationValue(2, "k2", 1, "k1"));
    }

    private static TopicSubscriptionKey topicSubscriptionKey() {
        return TopicSubscriptionKey.topicSubscriptionKey(ResourceAddress.resourceAddress("io.acme.inventory:stock-updates:2.0.0")
                                                                        .unwrap(),
                                                         Artifact.artifact("org.example:orders-slice:1.2.3")
                                                                 .unwrap(),
                                                         MethodName.methodName("onStock")
                                                                   .unwrap(),
                                                         NODE);
    }

    private static boolean roundTripsAlone(Fixture fixture) {
        return CODEC.encode(HEADER, Map.of(fixture.key(), fixture.value()))
                    .flatMap(CODEC::decode)
                    .map(BackupDocument::entries)
                    .map(entries -> entries.equals(Map.of(fixture.key(), fixture.value())))
                    .or(false);
    }

    private static String line(AetherValue value, String key) {
        return Base64.getEncoder()
                     .encodeToString(BackupFixtures.codec()
                                                   .canonical()
                                                   .encode(value)) + " " + BackupEntryCodec.escape(key);
    }

    private static String documentWithLines(String... lines) {
        return Stream.concat(Stream.of("aether-kv-backup/1", "revision=1", "incarnation=", "entries=" + lines.length),
                             Arrays.stream(lines))
                     .collect(Collectors.joining("\n", "", "\n"));
    }

    private static List<String> entryLines(String document) {
        return document.lines()
                       .skip(4)
                       .toList();
    }

    private static List<Cause> failures(Result<?> result) {
        return result.fold(BackupEntryCodecTest::leaves, _ -> List.of());
    }

    /// The individual causes inside any nesting of composites.
    private static List<Cause> leaves(Cause cause) {
        var parts = cause.stream()
                         .toList();

        return parts.equals(List.of(cause))
               ? parts
               : parts.stream()
                      .flatMap(part -> leaves(part).stream())
                      .toList();
    }
}
