// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyAuditKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AutoHealStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamMetadataKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.parse.Text;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// The KV backup document: what a whole-cluster cold restart restores after consensus starts from
/// zero. It holds cluster-specific state only — entries whose key is a
/// [ClusterStateKey][AetherKey.ClusterStateKey] that [backs up][AetherKey.ClusterStateKey#isBackedUp];
/// runtime state is filtered out on encode and refused on decode.
///
/// Layout, one entry per line after a four-line header:
///
/// ```
/// aether-kv-backup/1
/// revision=<long>
/// incarnation=<escaped text, empty when not yet assigned>
/// entries=<count>
/// <base64 of the value's generated binary codec> <escaped canonical key string>
/// ```
///
/// The key is its readable [AetherKey#asString] form, so a document can be inspected and grepped. The
/// value is the SAME bytes consensus replicates — the generated `@Codec`, wire-tag pinned in
/// `SystemTags` — so a value round-trips losslessly without a second, hand-written grammar to keep in
/// step with every record. Escaping covers only `\`, newline and carriage return: enough to keep one
/// entry on one line, nothing a reader has to learn. Entries are sorted by key, and values are written
/// with the canonical codec, so the same state always renders to the same bytes.
///
/// Decoding is strict in both directions, because a backup that restores something other than what
/// was taken is worse than one that refuses: every key must parse back to exactly one key type and
/// re-render to the identical string, and every value must re-encode to the identical bytes (which also
/// rejects trailing garbage the binary reader would otherwise ignore). Every entry is checked and every
/// failure is reported with its line number, rather than stopping at the first.
public record BackupEntryCodec(SliceCodec codec) {
    public static final int FORMAT_VERSION = 1;
    private static final String MAGIC = "aether-kv-backup/";
    private static final String REVISION = "revision=";
    private static final String INCARNATION = "incarnation=";
    private static final String ENTRIES = "entries=";
    private static final int HEADER_LINES = 4;
    private static final String SEPARATOR = " ";

    private static final Comparator<Map.Entry<AetherKey, AetherValue>> BY_KEY = Comparator.comparing(BackupEntryCodec::keyString);

    /// One parser per backed-up key type, each accepting only its own prefix. Kept exhaustive by
    /// `BackupEntryCodecTest`, which requires a round-tripping fixture for every
    /// [ClusterStateKey][AetherKey.ClusterStateKey] type — a type missing here fails that test.
    private static final List<Fn1<Result<? extends ClusterStateKey>, String>> KEY_PARSERS = List.of(SliceTargetKey::sliceTargetKey,
                                                                                                    AppBlueprintKey::appBlueprintKey,
                                                                                                    DeploymentOutcomeKey::deploymentOutcomeKey,
                                                                                                    VersionRoutingKey::versionRoutingKey,
                                                                                                    DeploymentKey::parseDeploymentKey,
                                                                                                    PreviousVersionKey::previousVersionKey,
                                                                                                    LogLevelKey::logLevelKey,
                                                                                                    ObservabilityConfigKey::observabilityConfigKey,
                                                                                                    AlertThresholdKey::alertThresholdKey,
                                                                                                    ConfigKey::configKey,
                                                                                                    key -> SchemaVersionKey.schemaVersionKey(key,
                                                                                                                                             true),
                                                                                                    CommunityKey::parseCommunityKey,
                                                                                                    AbTestKey::abTestKey,
                                                                                                    AbTestRoutingKey::abTestRoutingKey,
                                                                                                    key -> StreamMetadataKey.streamMetadataKey(key,
                                                                                                                                               true),
                                                                                                    ClusterConfigKey::clusterConfigKey,
                                                                                                    key -> StreamConfigKey.streamConfigKey(key,
                                                                                                                                           true),
                                                                                                    ApiKeyKey::parseApiKeyKey,
                                                                                                    ApiKeyAuditKey::parseApiKeyAuditKey,
                                                                                                    AutoHealStateKey::autoHealStateKey,
                                                                                                    BlueprintStreamBindingsKey::blueprintStreamBindingsKey,
                                                                                                    EntityCheckpointKey::entityCheckpointKey);

    /// `codec` must carry every value type a backed-up key can hold — in a node, the assembled node
    /// codec. Values are written through its canonical form so collection order cannot vary the bytes.
    public static BackupEntryCodec backupEntryCodec(SliceCodec codec) {
        return new BackupEntryCodec(codec.canonical());
    }

    /// Whether an entry under `key` belongs in a backup.
    public static boolean isBackedUp(AetherKey key) {
        return key instanceof ClusterStateKey clusterState && clusterState.isBackedUp();
    }

    /// Render the backed-up subset of `entries`. Fails, naming every offending key, if a value cannot
    /// be encoded or a key would not parse back to itself — a backup that cannot be restored as taken
    /// is refused at the moment it is taken, not discovered at restore.
    public Result<String> encode(BackupHeader header, Map<AetherKey, AetherValue> entries) {
        return Result.allOf(entries.entrySet()
                                   .stream()
                                   .filter(BackupEntryCodec::isBackedUpEntry)
                                   .sorted(BY_KEY)
                                   .map(this::encodeEntry)
                                   .toList()).map(lines -> render(header, lines));
    }

    /// Parse a document produced by [#encode]. Header faults fail fast; entry faults are collected so
    /// the failure names every bad line at once.
    public Result<BackupDocument> decode(String document) {
        var lines = document.lines().toList();

        return parseHeader(lines).flatMap(header -> decodeEntries(header, lines));
    }

    /// Header of a backup document. `clusterIncarnation` is a placeholder until cluster incarnations
    /// exist: it is carried and round-tripped, and nothing reads it yet.
    public record BackupHeader(long revision, Option<String> clusterIncarnation) {
        /// A blank incarnation is normalised to absent, which is how it renders.
        public static BackupHeader backupHeader(long revision, Option<String> clusterIncarnation) {
            return new BackupHeader(revision, clusterIncarnation.filter(Verify.Is::notBlank));
        }
    }

    public record BackupDocument(BackupHeader header, Map<AetherKey, AetherValue> entries) {
        public static BackupDocument backupDocument(BackupHeader header, Map<AetherKey, AetherValue> entries) {
            return new BackupDocument(header, Map.copyOf(entries));
        }
    }

    /// Every way a document can fail to encode or decode. Line numbers are 1-based document lines.
    public sealed interface BackupError extends Cause {
        record ValueEncodingFailed(String key, Cause origin, String message) implements BackupError, Cause.Wrapped {
            static final Fn2<ValueEncodingFailed, String, Cause> FACTORY = Causes.forTwoValues("Value of '%s' could not be encoded: %s",
                                                                                               ValueEncodingFailed::new);
        }

        record KeyNotRoundTrippable(String key, String message) implements BackupError {
            static final Fn1<KeyNotRoundTrippable, String> FACTORY = Causes.forOneValue("Key '%s' does not parse back to itself from its canonical string",
                                                                                        KeyNotRoundTrippable::new);
        }

        record MissingHeaderLine(int lineNumber, String expected, String message) implements BackupError {
            static final Fn2<MissingHeaderLine, Integer, String> FACTORY = Causes.forTwoValues("Line %d: expected header field '%s'",
                                                                                               MissingHeaderLine::new);
        }

        record MalformedHeaderValue(int lineNumber, String line, String message) implements BackupError {
            static final Fn2<MalformedHeaderValue, Integer, String> FACTORY = Causes.forTwoValues("Line %d: malformed header value in '%s'",
                                                                                                  MalformedHeaderValue::new);
        }

        record UnsupportedFormatVersion(int version, String message) implements BackupError {
            static final Fn1<UnsupportedFormatVersion, Integer> FACTORY = Causes.forOneValue("Unsupported backup format version %d",
                                                                                             UnsupportedFormatVersion::new);
        }

        record EntryCountMismatch(int declared, int found, String message) implements BackupError {
            static final Fn2<EntryCountMismatch, Integer, Integer> FACTORY = Causes.forTwoValues("Header declares %d entries but the document holds %d",
                                                                                                 EntryCountMismatch::new);
        }

        record MalformedEntry(int lineNumber, String line, String message) implements BackupError {
            static final Fn2<MalformedEntry, Integer, String> FACTORY = Causes.forTwoValues("Line %d: malformed entry '%s'",
                                                                                            MalformedEntry::new);
        }

        record UnrecognisedKey(int lineNumber, String key, String message) implements BackupError {
            static final Fn2<UnrecognisedKey, Integer, String> FACTORY = Causes.forTwoValues("Line %d: '%s' is not a backed-up key type",
                                                                                             UnrecognisedKey::new);
        }

        record AmbiguousKey(int lineNumber, String key, String message) implements BackupError {
            static final Fn2<AmbiguousKey, Integer, String> FACTORY = Causes.forTwoValues("Line %d: '%s' parses as more than one key type",
                                                                                          AmbiguousKey::new);
        }

        record NonCanonicalKey(int lineNumber, String key, String message) implements BackupError {
            static final Fn2<NonCanonicalKey, Integer, String> FACTORY = Causes.forTwoValues("Line %d: '%s' is not in canonical form",
                                                                                             NonCanonicalKey::new);
        }

        record KeyNotBackedUp(int lineNumber, String key, String message) implements BackupError {
            static final Fn2<KeyNotBackedUp, Integer, String> FACTORY = Causes.forTwoValues("Line %d: '%s' is runtime state and is never restored",
                                                                                            KeyNotBackedUp::new);
        }

        record ValueDecodingFailed(int lineNumber, String key, Cause origin, String message) implements BackupError, Cause.Wrapped {
            static final Fn3<ValueDecodingFailed, Integer, String, Cause> FACTORY = Causes.forThreeValues("Line %d: value of '%s' could not be decoded: %s",
                                                                                                          ValueDecodingFailed::new);
        }

        record ValueNotCanonical(int lineNumber, String key, String message) implements BackupError {
            static final Fn2<ValueNotCanonical, Integer, String> FACTORY = Causes.forTwoValues("Line %d: value of '%s' does not re-encode to the stored bytes",
                                                                                               ValueNotCanonical::new);
        }

        record NotAKvValue(String decodedType, String message) implements BackupError {
            static final Fn1<NotAKvValue, String> FACTORY = Causes.forOneValue("Decoded a %s, which is not a KV value",
                                                                               NotAKvValue::new);
        }

        record DuplicateKey(String key, String message) implements BackupError {
            static final Fn1<DuplicateKey, String> FACTORY = Causes.forOneValue("Key '%s' appears more than once",
                                                                                DuplicateKey::new);
        }
    }

    private record ParsedHeader(BackupHeader header, int declaredEntries) {
        static ParsedHeader parsedHeader(BackupHeader header, int declaredEntries) {
            return new ParsedHeader(header, declaredEntries);
        }
    }

    private record EncodedValue(AetherValue value, byte[] bytes) {
        static EncodedValue encodedValue(AetherValue value, byte[] bytes) {
            return new EncodedValue(value, bytes);
        }
    }

    // --- encode ---
    private Result<String> encodeEntry(Map.Entry<AetherKey, AetherValue> entry) {
        return Result.all(ensureRoundTrips(entry.getKey()),
                          encodeValue(entry.getKey(),
                                      entry.getValue()))
                     .map(BackupEntryCodec::formatEntry);
    }

    private static Result<AetherKey> ensureRoundTrips(AetherKey key) {
        return success(key).filter(BackupError.KeyNotRoundTrippable.FACTORY.apply(key.asString()),
                                   BackupEntryCodec::isRoundTrippable);
    }

    private static boolean isRoundTrippable(AetherKey key) {
        return parseCandidates(key.asString()).equals(List.of(key));
    }

    private Result<String> encodeValue(AetherKey key, AetherValue value) {
        return Result.lift(cause -> BackupError.ValueEncodingFailed.FACTORY.apply(key.asString(),
                                                                                  Causes.fromThrowable(cause)),
                           () -> codec.encode(value))
                     .map(Base64.getEncoder()::encodeToString);
    }

    private static String formatEntry(AetherKey key, String encodedValue) {
        return encodedValue + SEPARATOR + escape(key.asString());
    }

    private static String render(BackupHeader header, List<String> entryLines) {
        var headerLines = Stream.of(MAGIC + FORMAT_VERSION,
                                    REVISION + header.revision(),
                                    INCARNATION + escape(header.clusterIncarnation().or("")),
                                    ENTRIES + entryLines.size());

        return Stream.concat(headerLines,
                             entryLines.stream())
                     .collect(Collectors.joining("\n", "", "\n"));
    }

    // --- decode: header ---
    private static Result<ParsedHeader> parseHeader(List<String> lines) {
        return Result.all(headerField(lines, 0, MAGIC).flatMap(raw -> parseFormatVersion(raw, 1)),
                          headerField(lines, 1, REVISION).flatMap(raw -> parseRevision(raw, 2)),
                          headerField(lines, 2, INCARNATION).flatMap(raw -> parseIncarnation(raw, 3)),
                          headerField(lines, 3, ENTRIES).flatMap(raw -> parseEntryCount(raw, 4)))
                     .map((_, revision, incarnation, count) -> ParsedHeader.parsedHeader(BackupHeader.backupHeader(revision,
                                                                                                                   incarnation),
                                                                                         count));
    }

    /// The text after `prefix` on header line `index`, or a failure naming the field that is missing.
    private static Result<String> headerField(List<String> lines, int index, String prefix) {
        return option(index < lines.size()
                      ? lines.get(index)
                      : null).filter(line -> line.startsWith(prefix))
                     .map(line -> line.substring(prefix.length()))
                     .toResult(BackupError.MissingHeaderLine.FACTORY.apply(index + 1, prefix));
    }

    private static Result<Integer> parseFormatVersion(String raw, int lineNumber) {
        return Number.parseInt(raw)
                     .mapError(_ -> BackupError.MalformedHeaderValue.FACTORY.apply(lineNumber, MAGIC + raw))
                     .filter(BackupError.UnsupportedFormatVersion.FACTORY, version -> version == FORMAT_VERSION);
    }

    private static Result<Long> parseRevision(String raw, int lineNumber) {
        return Number.parseLong(raw).mapError(_ -> BackupError.MalformedHeaderValue.FACTORY.apply(lineNumber,
                                                                                                  REVISION + raw));
    }

    private static Result<Option<String>> parseIncarnation(String raw, int lineNumber) {
        return unescape(raw).toResult(BackupError.MalformedHeaderValue.FACTORY.apply(lineNumber, INCARNATION + raw))
                       .map(Option::some);
    }

    private static Result<Integer> parseEntryCount(String raw, int lineNumber) {
        return Number.parseInt(raw)
                     .mapError(_ -> BackupError.MalformedHeaderValue.FACTORY.apply(lineNumber, ENTRIES + raw))
                     .filter(_ -> BackupError.MalformedHeaderValue.FACTORY.apply(lineNumber, ENTRIES + raw),
                             Verify.Is::nonNegative);
    }

    // --- decode: entries ---
    private Result<BackupDocument> decodeEntries(ParsedHeader parsed, List<String> lines) {
        var entryLines = lines.subList(HEADER_LINES, lines.size());

        return ensureEntryCount(parsed.declaredEntries(),
                                entryLines.size()).flatMap(_ -> decodeEntryLines(entryLines))
                               .flatMap(BackupEntryCodec::toEntryMap)
                               .map(entries -> BackupDocument.backupDocument(parsed.header(),
                                                                             entries));
    }

    /// Every entry line decoded, with all failures collected rather than the first.
    private Result<List<Map.Entry<AetherKey, AetherValue>>> decodeEntryLines(List<String> entryLines) {
        return Result.allOf(IntStream.range(0,
                                            entryLines.size())
                                     .mapToObj(index -> decodeEntry(HEADER_LINES + index + 1,
                                                                    entryLines.get(index)))
                                     .toList());
    }

    private static Result<Unit> ensureEntryCount(int declared, int found) {
        return declared == found
               ? Result.unitResult()
               : BackupError.EntryCountMismatch.FACTORY.apply(declared, found).result();
    }

    private Result<Map.Entry<AetherKey, AetherValue>> decodeEntry(int lineNumber, String line) {
        var separator = line.indexOf(SEPARATOR);

        if (separator <= 0) {
            return BackupError.MalformedEntry.FACTORY.apply(lineNumber, line).result();
        }

        return unescape(line.substring(separator + 1)).toResult(BackupError.MalformedEntry.FACTORY.apply(lineNumber,
                                                                                                         line))
                       .flatMap(key -> decodeKeyAndValue(lineNumber,
                                                         key,
                                                         line.substring(0, separator)));
    }

    private Result<Map.Entry<AetherKey, AetherValue>> decodeKeyAndValue(int lineNumber,
                                                                        String key,
                                                                        String encodedValue) {
        return Result.all(decodeKey(lineNumber, key), decodeValue(lineNumber, key, encodedValue)).map(Map::entry);
    }

    private static Result<AetherKey> decodeKey(int lineNumber, String key) {
        return singleCandidate(lineNumber,
                               key,
                               parseCandidates(key)).filter(BackupError.NonCanonicalKey.FACTORY.apply(lineNumber, key),
                                                            parsed -> parsed.asString()
                                                                            .equals(key))
                              .filter(BackupError.KeyNotBackedUp.FACTORY.apply(lineNumber, key),
                                      BackupEntryCodec::isBackedUp);
    }

    private static Result<AetherKey> singleCandidate(int lineNumber, String key, List<ClusterStateKey> candidates) {
        return switch (candidates.size()) {
            case 0 -> BackupError.UnrecognisedKey.FACTORY.apply(lineNumber, key).result();
            case 1 -> success(candidates.getFirst());
            default -> BackupError.AmbiguousKey.FACTORY.apply(lineNumber, key).result();
        };
    }

    private Result<AetherValue> decodeValue(int lineNumber, String key, String encodedValue) {
        return Text.decodeBase64(encodedValue)
                   .flatMap(this::readValue)
                   .mapError(cause -> BackupError.ValueDecodingFailed.FACTORY.apply(lineNumber, key, cause))
                   .filter(BackupError.ValueNotCanonical.FACTORY.apply(lineNumber, key),
                           this::isCanonical)
                   .map(EncodedValue::value);
    }

    private Result<EncodedValue> readValue(byte[] bytes) {
        return Result.lift(() -> codec.<Object> decode(bytes))
                     .flatMap(BackupEntryCodec::asAetherValue)
                     .map(value -> EncodedValue.encodedValue(value, bytes));
    }

    private static Result<AetherValue> asAetherValue(Object decoded) {
        return decoded instanceof AetherValue value
               ? success(value)
               : BackupError.NotAKvValue.FACTORY.apply(decoded.getClass().getName()).result();
    }

    /// Re-encoding must reproduce the stored bytes exactly; anything else means the document was not
    /// written by this codec from this value — including bytes past the end of the value. A value
    /// that cannot be re-encoded at all is, by the same test, not canonical.
    private boolean isCanonical(EncodedValue encoded) {
        return Arrays.equals(Result.lift(() -> codec.encode(encoded.value())).or(new byte[0]),
                             encoded.bytes());
    }

    private static Result<Map<AetherKey, AetherValue>> toEntryMap(List<Map.Entry<AetherKey, AetherValue>> entries) {
        var map = new HashMap<AetherKey, AetherValue>();

        for (var entry : entries) {
            if (map.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                return BackupError.DuplicateKey.FACTORY.apply(entry.getKey().asString()).result();
            }
        }

        return success(Map.copyOf(map));
    }

    // --- keys ---
    private static boolean isBackedUpEntry(Map.Entry<AetherKey, AetherValue> entry) {
        return isBackedUp(entry.getKey());
    }

    private static String keyString(Map.Entry<AetherKey, AetherValue> entry) {
        return entry.getKey()
                    .asString();
    }

    /// Every backed-up key type that accepts `key`. Exactly one for a well-formed document; a parser that
    /// throws on hostile input counts as not accepting it.
    static List<ClusterStateKey> parseCandidates(String key) {
        return KEY_PARSERS.stream()
                          .flatMap(parser -> tryParse(parser, key).stream())
                          .toList();
    }

    private static Option<ClusterStateKey> tryParse(Fn1<Result<? extends ClusterStateKey>, String> parser, String key) {
        return Result.lift(() -> parser.apply(key))
                     .flatMap(parsed -> parsed.map(ClusterStateKey.class::cast))
                     .option();
    }

    // --- escaping ---
    static String escape(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }

    /// Inverse of [#escape]; absent when `text` holds an escape [#escape] never produces.
    static Option<String> unescape(String text) {
        var out = new StringBuilder(text.length());
        var index = 0;

        while (index < text.length()) {
            var current = text.charAt(index++);

            if (current != '\\') {
                out.append(current);
                continue;
            }

            if (index == text.length()) {
                return none();
            }

            switch (text.charAt(index++)) {
                case '\\' -> out.append('\\');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                default -> {
                    return none();
                }
            }
        }

        return some(out.toString());
    }
}
