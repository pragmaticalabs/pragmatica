// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.LogLevelValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupDocument;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupError;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.aether.slice.kvstore.BackupFixtures.Fixture;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.kvstore.BackupFixtures.AWKWARD;
import static org.pragmatica.aether.slice.kvstore.BackupFixtures.FIXTURES;

/// The backup document round-trips every backed-up key type exactly, filters runtime state out,
/// accepts only its own canonical rendering, and reports every malformed line precisely instead of
/// throwing.
class BackupEntryCodecTest {
    private static final BackupEntryCodec CODEC = BackupEntryCodec.backupEntryCodec(BackupFixtures.codec());
    private static final BackupHeader HEADER = BackupHeader.backupHeader(AWKWARD, 3L, 42L);
    private static final NodeId NODE = NodeId.nodeId("node-1")
                                             .unwrap();
    private static final int FIRST_ENTRY_LINE = 7;

    @Nested
    class RoundTrip {
        /// Every [ClusterStateKey] type needs a fixture. A new cluster-state key with none fails HERE,
        /// by name — which is what keeps the codec's binding table and this coverage exhaustive.
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

        /// Byte-exact: the restored state renders to the identical document.
        @Test
        void encode_ofDecodedDocument_isByteIdentical() {
            var first = CODEC.encode(HEADER, fixtureMap())
                             .unwrap();
            var second = CODEC.decode(first)
                              .flatMap(document -> CODEC.encode(document.header(), document.entries()))
                              .unwrap();

            assertThat(second).isEqualTo(first);
        }

        /// The value column carries the same bytes the KV store persists: `KVStore.makeSnapshot`
        /// encodes its map through `serializer.canonical()`, and `DurableRabiaPersistence` writes
        /// commands through the canonical serializer. Both must contain the backup's bytes verbatim.
        @Test
        void encodedValue_isEmbeddedVerbatimInTheCanonicalPutAndSnapshotBytes() {
            var fixture = fixtureOf(AppBlueprintKey.class);
            var backupBytes = valueBytes(CODEC.encode(HEADER, Map.of(fixture.key(), fixture.value()))
                                              .unwrap());
            var persisted = BackupFixtures.codec()
                                          .canonical();
            var putBytes = persisted.encode(new KVCommand.Put<>(fixture.key(), fixture.value()));
            var snapshotBytes = persisted.encode(new HashMap<>(Map.of(fixture.key(), fixture.value())));

            assertThat(indexOf(putBytes, backupBytes)).as("backup value bytes inside the persisted Put")
                                                      .isNotNegative();
            assertThat(indexOf(snapshotBytes, backupBytes)).as("backup value bytes inside the KV snapshot")
                                                           .isNotNegative();
        }

        /// Handed a plain codec, the factory must still write canonical bytes. The value holds a set whose
        /// insertion order differs from the canonical order — the precondition proves the plain and
        /// canonical encodings really differ for it, so this test can see a missing `.canonical()`.
        @Test
        void backupEntryCodec_writesCanonicalBytes_whenGivenAPlainCodec() {
            var plain = BackupFixtures.codec();
            var value = unorderedBlueprintValue(plain);
            var key = AppBlueprintKey.appBlueprintKey(value.blueprint()
                                                           .id());

            assertThat(plain.encode(value)).as("precondition: plain and canonical bytes differ for this value")
                                           .isNotEqualTo(plain.canonical()
                                                              .encode(value));

            var written = valueBytes(BackupEntryCodec.backupEntryCodec(plain)
                                                     .encode(HEADER, Map.of(key, value))
                                                     .unwrap());

            assertThat(written).isEqualTo(plain.canonical()
                                               .encode(value));
        }

        @Test
        void encode_escapesNewlinesAndBackslashes_soEveryEntryStaysOnOneLine() {
            var document = CODEC.encode(HEADER, fixtureMap())
                                .unwrap();

            assertThat(entryLines(document)).hasSize(FIXTURES.size());
            assertThat(document).contains("\\nand a newline\\\\");
        }

        @Test
        void header_roundTrips_forAnEmptyStore() {
            var header = BackupHeader.backupHeader("lineage-a", 1L, 0L);

            CODEC.encode(header, Map.of())
                 .flatMap(CODEC::decode)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(document -> assertThat(document.header()).isEqualTo(header))
                 .onSuccess(document -> assertThat(document.entries()).isEmpty());
        }

        /// Position is `(incarnation, revision)`: a later incarnation is ahead however low its revision,
        /// because the revision restarts with every cold restart.
        @Test
        void isAhead_ordersByIncarnationThenRevision() {
            var restarted = BackupHeader.backupHeader("l", 2L, 5L);
            var beforeRestart = BackupHeader.backupHeader("l", 1L, 900L);
            var laterSameIncarnation = BackupHeader.backupHeader("l", 2L, 6L);

            assertThat(restarted.isAhead(beforeRestart)).isTrue();
            assertThat(beforeRestart.isAhead(restarted)).isFalse();
            assertThat(laterSameIncarnation.isAhead(restarted)).isTrue();
            assertThat(restarted.isAhead(restarted)).isFalse();
        }

        @Test
        void decode_blankLineage_isRefused() {
            var document = seal(List.of("aether-kv-backup/1", "lineage=", "incarnation=1", "revision=1", "entries=0"));

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.MalformedHeaderValue.class);
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
            var document = sealedDocument(line(ConfigValue.configValue("orders.banner", "x"), key.asString()));

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOfSatisfying(BackupError.KeyNotBackedUp.class,
                                                                                error -> assertThat(error.lineNumber()).isEqualTo(FIRST_ENTRY_LINE));
        }

        @Test
        void decode_refusesARuntimeKeyType() {
            var key = topicSubscriptionKey();
            var document = sealedDocument(line(TopicSubscriptionValue.topicSubscriptionValue(NODE), key.asString()));

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.UnrecognisedKey.class);
        }
    }

    @Nested
    class ValueTypeBinding {
        @Test
        void encode_refusesAValueOfTheWrongTypeForItsKey() {
            var entries = Map.<AetherKey, AetherValue> of(ApiKeyKey.apiKeyKey("key-1"),
                                                          LogLevelValue.logLevelValue("root", "INFO"),
                                                          SliceTargetKey.sliceTargetKey(ArtifactBase.artifactBase("org.example:orders-slice")
                                                                                                    .unwrap()),
                                                          ConfigValue.configValue("k", "v"));

            assertThat(failures(CODEC.encode(HEADER, entries))).hasSize(2)
                                                               .allMatch(BackupError.ValueTypeRefused.class::isInstance);
        }

        @Test
        void decode_refusesAValueOfTheWrongTypeForItsKey() {
            var document = sealedDocument(line(LogLevelValue.logLevelValue("root", "INFO"), "api-key/key-1"),
                                          line(ConfigValue.configValue("k", "v"), "slice-target/org.example:orders-slice"));

            assertThat(failures(CODEC.decode(document))).hasSize(2)
                                                        .allMatch(BackupError.ValueTypeMismatch.class::isInstance)
                                                        .extracting(cause -> ((BackupError.ValueTypeMismatch) cause).lineNumber())
                                                        .containsExactlyInAnyOrder(FIRST_ENTRY_LINE, FIRST_ENTRY_LINE + 1);
        }

        @Test
        void encode_acceptsTheBoundValueType() {
            var key = SliceTargetKey.sliceTargetKey(ArtifactBase.artifactBase("org.example:orders-slice")
                                                                .unwrap());
            var value = SliceTargetValue.sliceTargetValue(Version.version("1.0.0")
                                                                 .unwrap(),
                                                          1);

            assertThat(CODEC.encode(HEADER, Map.of(key, value))
                            .isSuccess()).isTrue();
        }
    }

    @Nested
    class CanonicalForm {
        @Test
        void decode_refusesSwappedEntryOrder() {
            var lines = entryLines(CODEC.encode(HEADER, fixtureMap())
                                        .unwrap());
            var swapped = new ArrayList<>(lines);

            Collections.swap(swapped, 0, 1);

            assertNonCanonical(sealedDocument(swapped.toArray(String[]::new)));
        }

        @Test
        void decode_refusesASignedRevision() {
            assertNonCanonical(resealed(line -> line.equals("revision=42")
                                                ? "revision=+42"
                                                : line));
        }

        @Test
        void decode_refusesASignedEntryCount() {
            assertNonCanonical(resealed(line -> line.startsWith("entries=")
                                                ? "entries=+" + line.substring("entries=".length())
                                                : line));
        }

        @Test
        void decode_refusesAZeroPaddedFormatVersion() {
            assertNonCanonical(resealed(line -> line.equals("aether-kv-backup/1")
                                                ? "aether-kv-backup/01"
                                                : line));
        }

        @Test
        void decode_refusesUnpaddedBase64() {
            var padded = FIXTURES.stream()
                                 .map(fixture -> line(fixture.value(), fixture.key()
                                                                              .asString()))
                                 .filter(entry -> entry.contains("= "))
                                 .findFirst()
                                 .orElseThrow();

            assertNonCanonical(sealedDocument(padded.replace("= ", " ")
                                                    .replace("= ", " ")));
        }

        /// Property: whatever a mutation of a valid document does, decode either refuses it or the
        /// decoded state renders back to exactly that document. Resealed, so the checksum is not what
        /// refuses — this exercises the canonical-form rule alone.
        @Test
        void decode_acceptsOnlyDocumentsThatReEncodeToThemselves() {
            var original = CODEC.encode(HEADER, fixtureMap())
                                .unwrap();
            var lines = original.lines()
                                .toList();
            var mutations = List.<UnaryOperator<String>> of(line -> "+" + line,
                                                            line -> "0" + line,
                                                            line -> line + " ",
                                                            line -> " " + line,
                                                            line -> line.replace("=", ""),
                                                            line -> line.replace("=", "=0"),
                                                            line -> line.replace("/", "//"),
                                                            line -> line.toUpperCase(),
                                                            line -> line.replace(" ", "  "),
                                                            line -> line + "\r");
            var accepted = new ArrayList<String>();

            IntStream.range(0, lines.size())
                     .filter(index -> index != 5)
                     .forEach(index -> mutations.forEach(mutation -> collectAccepted(resealedAt(lines, index, mutation), accepted)));

            assertThat(accepted).as("mutated documents that decoded yet did not re-encode to themselves")
                                .allMatch(document -> CODEC.decode(document)
                                                           .flatMap(decoded -> CODEC.encode(decoded.header(), decoded.entries()))
                                                           .map(document::equals)
                                                           .or(false));
        }
    }

    @Nested
    class Integrity {
        /// A value corrupted into ANOTHER valid value decodes cleanly and is canonical — only the
        /// checksum can tell. The resealed control proves the same edit is otherwise accepted.
        @Test
        void decode_refusesAValueCorruptedIntoAnotherValidValue() {
            var key = ConfigKey.forKey("orders.banner");
            var original = CODEC.encode(HEADER, Map.of(key, ConfigValue.configValue("orders.banner", "genuine")))
                                .unwrap();
            var forged = original.replace(valueColumn(original), valueColumn(line(ConfigValue.configValue("orders.banner", "forged"), "")));

            assertThat(failures(CODEC.decode(forged))).singleElement()
                                                      .isInstanceOf(BackupError.ChecksumMismatch.class);
            assertThat(CODEC.decode(reseal(forged))
                            .isSuccess()).as("control: the same edit with a recomputed checksum decodes")
                                         .isTrue();
        }

        @Test
        void decode_truncatedDocument_isRefusedByTheChecksum() {
            var document = CODEC.encode(HEADER, fixtureMap())
                                .unwrap();
            var truncated = document.substring(0, document.lastIndexOf('\n', document.length() - 2) + 1);

            assertThat(failures(CODEC.decode(truncated))).singleElement()
                                                         .isInstanceOf(BackupError.ChecksumMismatch.class);
        }
    }

    @Nested
    class Failures {
        @Test
        void decode_null_isATypedFailure() {
            assertThat(failures(CODEC.decode(null))).containsExactly(BackupError.General.MISSING_DOCUMENT);
        }

        @Test
        void decode_reportsEveryBadEntry_withItsLineNumber() {
            var good = FIXTURES.getFirst();
            var document = sealedDocument(line(good.value(), good.key()
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
                                .anyMatch(message -> message.startsWith("Line 8:"))
                                .anyMatch(message -> message.startsWith("Line 9:"))
                                .anyMatch(message -> message.startsWith("Line 10:"));
        }

        @Test
        void decode_entryCountMismatch_isRefused() {
            var fixture = FIXTURES.getFirst();
            var entry = line(fixture.value(), fixture.key()
                                                     .asString());
            var document = seal(List.of("aether-kv-backup/1", "lineage=l", "incarnation=1", "revision=1", "entries=2", entry));

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.EntryCountMismatch.class);
        }

        @Test
        void decode_unsupportedFormatVersion_isRefused() {
            var document = resealed(line -> line.equals("aether-kv-backup/1")
                                            ? "aether-kv-backup/2"
                                            : line);

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.UnsupportedFormatVersion.class);
        }

        @Test
        void decode_missingHeader_namesEveryMissingField() {
            assertThat(failures(CODEC.decode(""))).hasSize(6)
                                                  .allMatch(BackupError.MissingHeaderLine.class::isInstance);
        }

        @Test
        void decode_malformedHeaderValues_areReported() {
            var document = seal(List.of("aether-kv-backup/1", "lineage=bad\\q", "incarnation=x", "revision=abc", "entries=-1"));

            assertThat(failures(CODEC.decode(document))).hasSize(4)
                                                        .allMatch(BackupError.MalformedHeaderValue.class::isInstance);
        }

        /// A key that parses but would re-render differently is not the key that was backed up.
        @Test
        void decode_nonCanonicalKey_isRefused() {
            var fixture = fixtureOf(ClusterConfigKey.class);
            var document = sealedDocument(line(fixture.value(), "cluster-config/000"));

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
            var document = sealedDocument(Base64.getEncoder()
                                                .encodeToString(padded) + " " + fixture.key()
                                                                                       .asString());

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.ValueNotCanonical.class);
        }

        @Test
        void decode_valueThatIsNotAKvValue_isRefused() {
            var bytes = BackupFixtures.codec()
                                      .encode("just a string");
            var document = sealedDocument(Base64.getEncoder()
                                                .encodeToString(bytes) + " config/orders.banner");

            assertThat(failures(CODEC.decode(document))).singleElement()
                                                        .isInstanceOf(BackupError.ValueDecodingFailed.class);
        }

        @Test
        void decode_duplicateKey_isRefused_atTheRepeatingLine() {
            var fixture = FIXTURES.getFirst();
            var entry = line(fixture.value(), fixture.key()
                                                     .asString());

            assertThat(failures(CODEC.decode(sealedDocument(entry, entry)))).singleElement()
                                                                              .isInstanceOfSatisfying(BackupError.DuplicateKey.class,
                                                                                                      error -> assertThat(error.lineNumber()).isEqualTo(FIRST_ENTRY_LINE + 1));
        }

        @Test
        void decode_invalidEscapeInKey_isRefused() {
            var fixture = FIXTURES.getFirst();
            var unescapedLine = line(fixture.value(), "") + "config/bad\\escape";

            assertThat(failures(CODEC.decode(sealedDocument(unescapedLine)))).singleElement()
                                                                               .isInstanceOf(BackupError.MalformedEntry.class);
        }

        /// Hostile input surfaces as a failure value, never as an exception out of `decode`.
        @Test
        void decode_hostileInput_neverThrows() {
            var inputs = List.of("\u0000\u0001",
                                 seal(List.of("aether-kv-backup/1", "lineage=l", "incarnation=1", "revision=1", "entries=1", " ")),
                                 seal(List.of("aether-kv-backup/1", "lineage=l", "incarnation=1", "revision=1", "entries=1", "AAAA slices/")),
                                 seal(List.of("aether-kv-backup/1", "lineage=l", "incarnation=1", "revision=1", "entries=1", "//// topic-sub/a/b/c/d/e")),
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

        /// The generic guard: a key whose canonical string does not parse back to it is refused at
        /// encode. An empty config key renders as `config/`, which no parser accepts.
        @Test
        void encode_keyThatDoesNotParseBackToItself_isRefused() {
            var key = ConfigKey.forKey("");

            assertThat(failures(CODEC.encode(HEADER, Map.of(key, ConfigValue.configValue("", "v"))))).singleElement()
                                                                                                   .isInstanceOf(BackupError.KeyNotRoundTrippable.class);
        }

        /// Cluster-wide and node-scoped config keys use disjoint prefixes, so a cluster-wide key named
        /// like a node-scoped one — `node/a/b`, or even `config-node/a/b` — round-trips as itself.
        @Test
        void configKey_everyNonEmptyName_roundTripsThroughTheBackup() {
            var names = List.of("node/a/b", "node/node-1/orders.banner", "config-node/a/b", "config/x", "a", AWKWARD);
            var entries = names.stream()
                               .collect(Collectors.toMap(ConfigKey::forKey,
                                                         name -> (AetherValue) ConfigValue.configValue(name, "v")));

            CODEC.encode(HEADER, Map.copyOf(entries))
                 .flatMap(CODEC::decode)
                 .onFailure(cause -> Assertions.fail(cause.message()))
                 .onSuccess(document -> assertThat(document.entries()).containsExactlyInAnyOrderEntriesOf(entries));
            assertThat(names).allMatch(name -> ConfigKey.configKey(ConfigKey.forKey(name)
                                                                            .asString())
                                                        .map(ConfigKey.forKey(name)::equals)
                                                        .or(false));
        }
    }

    // --- helpers ---

    private static Map<AetherKey, AetherValue> fixtureMap() {
        return FIXTURES.stream()
                       .collect(Collectors.toMap(Fixture::key, Fixture::value, (first, _) -> first, LinkedHashMap::new));
    }

    private static Fixture fixtureOf(Class<?> keyType) {
        return FIXTURES.stream()
                       .filter(fixture -> keyType.isInstance(fixture.key()))
                       .findFirst()
                       .orElseThrow();
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

    /// A blueprint whose dependency set iterates in a non-canonical order under `codec`.
    private static AppBlueprintValue unorderedBlueprintValue(SliceCodec codec) {
        var dependencies = IntStream.range(0, 12)
                                    .mapToObj(index -> Artifact.artifact("org.example:dep-" + index + ":1.0.0")
                                                               .unwrap())
                                    .toList();
        var reversed = new ArrayList<>(dependencies);

        Collections.reverse(reversed);

        return Stream.of(dependencies, reversed)
                     .map(order -> blueprintValueWith(new LinkedHashSet<>(order)))
                     .filter(value -> !Arrays.equals(codec.encode(value), codec.canonical()
                                                                               .encode(value)))
                     .findFirst()
                     .orElseThrow();
    }

    private static AppBlueprintValue blueprintValueWith(LinkedHashSet<Artifact> dependencies) {
        var slice = ResolvedSlice.resolvedSlice(Artifact.artifact("org.example:orders-slice:1.2.3")
                                                        .unwrap(),
                                                1,
                                                1,
                                                false,
                                                dependencies)
                                 .unwrap();

        return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(BlueprintId.blueprintId("org.example:orders-app:1.2.3")
                                                                                                  .unwrap(),
                                                                                       List.of(slice)));
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

    private static String valueColumn(String documentOrLine) {
        var last = documentOrLine.lines()
                                 .toList()
                                 .getLast();

        return last.substring(0, last.indexOf(' '));
    }

    private static byte[] valueBytes(String document) {
        return Base64.getDecoder()
                     .decode(valueColumn(document));
    }

    /// Header fields for `entries`, with a correct checksum, so a test exercises exactly the rule it names.
    private static String sealedDocument(String... entries) {
        return seal(Stream.concat(Stream.of("aether-kv-backup/1", "lineage=l", "incarnation=1", "revision=1", "entries=" + entries.length),
                                  Arrays.stream(entries))
                          .toList());
    }

    /// Joins `unsealed` (five header fields, then entries) with a correct `sha256=` line inserted.
    private static String seal(List<String> unsealed) {
        var lines = new ArrayList<>(unsealed);

        lines.add(Math.min(5, lines.size()), "sha256=" + BackupEntryCodec.checksum(unsealed)
                                                                         .unwrap());

        return lines.stream()
                    .collect(Collectors.joining("\n", "", "\n"));
    }

    private static String reseal(String document) {
        var lines = new ArrayList<>(document.lines()
                                            .toList());

        lines.remove(5);

        return seal(lines);
    }

    /// The fixture document with `edit` applied to every line, then resealed.
    private static String resealed(UnaryOperator<String> edit) {
        var lines = new ArrayList<>(CODEC.encode(HEADER, fixtureMap())
                                         .unwrap()
                                         .lines()
                                         .toList());

        lines.remove(5);

        return seal(lines.stream()
                         .map(edit)
                         .toList());
    }

    private static String resealedAt(List<String> lines, int index, UnaryOperator<String> mutation) {
        var mutated = new ArrayList<>(lines);

        mutated.set(index, mutation.apply(mutated.get(index)));
        mutated.remove(5);

        return seal(mutated);
    }

    private static void collectAccepted(String document, List<String> accepted) {
        CODEC.decode(document)
             .onSuccess(_ -> accepted.add(document));
    }

    private static void assertNonCanonical(String document) {
        assertThat(failures(CODEC.decode(document))).singleElement()
                                                    .isInstanceOf(BackupError.NonCanonicalDocument.class);
    }

    private static List<String> entryLines(String document) {
        return document.lines()
                       .skip(6)
                       .toList();
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return IntStream.rangeClosed(0, haystack.length - needle.length)
                        .filter(start -> Arrays.equals(haystack, start, start + needle.length, needle, 0, needle.length))
                        .findFirst()
                        .orElse(-1);
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
