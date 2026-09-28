// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyAuditKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AutoHealStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BlueprintStreamBindingsKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterStateKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskPauseKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyAuditValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BlueprintStreamBindingsValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityFoldCheckpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.LogLevelValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ObservabilityConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskPauseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
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
/// Layout, one entry per line after a five-line header:
///
/// ```
/// aether-kv-backup/1
/// revision=<long>
/// incarnation=<escaped text, empty when not yet assigned>
/// entries=<count>
/// sha256=<lowercase hex SHA-256 of every OTHER line of the document>
/// <base64 of the value's generated binary codec> <escaped canonical key string>
/// ```
///
/// The key is its readable [AetherKey#asString] form, so a document can be inspected and grepped. The
/// value is the bytes of the generated `@Codec`, wire-tag pinned in `SystemTags`, written through the
/// canonical codec — the same encoding the KV snapshot and the persisted command log use — so a value
/// round-trips losslessly without a second, hand-written grammar. Escaping covers only `\`, newline and
/// carriage return: enough to keep one entry on one line. Entries are sorted by key.
///
/// **There is exactly one valid rendering of any state.** Decoding accepts only that rendering: the
/// decoded state must re-encode to the identical document, so `+7`, `01`, unpadded base64, reordered
/// entries and every other spelling a lenient parser would tolerate are refused. On top of that the
/// checksum refuses a document ACCIDENTALLY corrupted after it was written — including a value corrupted
/// into another value that still decodes. It is not a tamper guard: anyone who edits the document can
/// recompute it. Each key type is bound to the one value type it holds, in
/// both directions. Every entry is checked and every entry failure is reported with its line number.
public record BackupEntryCodec(SliceCodec codec) {
    public static final int FORMAT_VERSION = 1;
    private static final String MAGIC = "aether-kv-backup/";
    private static final String REVISION = "revision=";
    private static final String INCARNATION = "incarnation=";
    private static final String ENTRIES = "entries=";
    private static final String CHECKSUM = "sha256=";
    private static final int CHECKSUM_LINE = 4;
    private static final int HEADER_LINES = 5;
    private static final String SEPARATOR = " ";

    private static final Comparator<Map.Entry<AetherKey, AetherValue>> BY_KEY = Comparator.comparing(BackupEntryCodec::keyString);

    /// One binding per backed-up key type: the value type it holds and the parser accepting only its own
    /// prefix. Kept exhaustive by `BackupEntryCodecTest`, which requires a round-tripping fixture for
    /// every [ClusterStateKey][AetherKey.ClusterStateKey] type — a type missing here fails that test.
    private static final List<KeyBinding> BINDINGS = List.of(KeyBinding.keyBinding(SliceTargetKey.class,
                                                                                   SliceTargetValue.class,
                                                                                   SliceTargetKey::sliceTargetKey),
                                                             KeyBinding.keyBinding(AppBlueprintKey.class,
                                                                                   AppBlueprintValue.class,
                                                                                   AppBlueprintKey::appBlueprintKey),
                                                             KeyBinding.keyBinding(DeploymentOutcomeKey.class,
                                                                                   DeploymentOutcomeValue.class,
                                                                                   DeploymentOutcomeKey::deploymentOutcomeKey),
                                                             KeyBinding.keyBinding(VersionRoutingKey.class,
                                                                                   VersionRoutingValue.class,
                                                                                   VersionRoutingKey::versionRoutingKey),
                                                             KeyBinding.keyBinding(DeploymentKey.class,
                                                                                   DeploymentValue.class,
                                                                                   DeploymentKey::parseDeploymentKey),
                                                             KeyBinding.keyBinding(PreviousVersionKey.class,
                                                                                   PreviousVersionValue.class,
                                                                                   PreviousVersionKey::previousVersionKey),
                                                             KeyBinding.keyBinding(LogLevelKey.class,
                                                                                   LogLevelValue.class,
                                                                                   LogLevelKey::logLevelKey),
                                                             KeyBinding.keyBinding(ObservabilityConfigKey.class,
                                                                                   ObservabilityConfigValue.class,
                                                                                   ObservabilityConfigKey::observabilityConfigKey),
                                                             KeyBinding.keyBinding(AlertThresholdKey.class,
                                                                                   AlertThresholdValue.class,
                                                                                   AlertThresholdKey::alertThresholdKey),
                                                             KeyBinding.keyBinding(ConfigKey.class,
                                                                                   ConfigValue.class,
                                                                                   ConfigKey::configKey),
                                                             KeyBinding.keyBinding(SchemaVersionKey.class,
                                                                                   SchemaVersionValue.class,
                                                                                   key -> SchemaVersionKey.schemaVersionKey(key,
                                                                                                                            true)),
                                                             KeyBinding.keyBinding(CommunityKey.class,
                                                                                   CommunityValue.class,
                                                                                   CommunityKey::parseCommunityKey),
                                                             KeyBinding.keyBinding(AbTestKey.class,
                                                                                   AbTestValue.class,
                                                                                   AbTestKey::abTestKey),
                                                             KeyBinding.keyBinding(ClusterConfigKey.class,
                                                                                   ClusterConfigValue.class,
                                                                                   ClusterConfigKey::clusterConfigKey),
                                                             KeyBinding.keyBinding(StreamConfigKey.class,
                                                                                   StreamConfigValue.class,
                                                                                   key -> StreamConfigKey.streamConfigKey(key,
                                                                                                                          true)),
                                                             KeyBinding.keyBinding(ApiKeyKey.class,
                                                                                   ApiKeyValue.class,
                                                                                   ApiKeyKey::parseApiKeyKey),
                                                             KeyBinding.keyBinding(ApiKeyAuditKey.class,
                                                                                   ApiKeyAuditValue.class,
                                                                                   ApiKeyAuditKey::parseApiKeyAuditKey),
                                                             KeyBinding.keyBinding(AutoHealStateKey.class,
                                                                                   AutoHealStateValue.class,
                                                                                   AutoHealStateKey::autoHealStateKey),
                                                             KeyBinding.keyBinding(BlueprintStreamBindingsKey.class,
                                                                                   BlueprintStreamBindingsValue.class,
                                                                                   BlueprintStreamBindingsKey::blueprintStreamBindingsKey),
                                                             KeyBinding.keyBinding(EntityCheckpointKey.class,
                                                                                   EntityFoldCheckpointValue.class,
                                                                                   EntityCheckpointKey::entityCheckpointKey),
                                                             KeyBinding.keyBinding(ScheduledTaskPauseKey.class,
                                                                                   ScheduledTaskPauseValue.class,
                                                                                   ScheduledTaskPauseKey::scheduledTaskPauseKey),
                                                             KeyBinding.keyBinding(ClusterIncarnationKey.class,
                                                                                   ClusterIncarnationValue.class,
                                                                                   ClusterIncarnationKey::clusterIncarnationKey));

    private static final Map<Class<?>, Class<?>> VALUE_TYPES = BINDINGS.stream().collect(Collectors.toMap(KeyBinding::keyType,
                                                                                                          KeyBinding::valueType));

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
    /// be encoded, is not the type its key holds, or a key would not parse back to itself — a backup
    /// that cannot be restored as taken is refused at the moment it is taken, not discovered at restore.
    public Result<String> encode(BackupHeader header, Map<AetherKey, AetherValue> entries) {
        return Result.allOf(entries.entrySet()
                                   .stream()
                                   .filter(BackupEntryCodec::isBackedUpEntry)
                                   .sorted(BY_KEY)
                                   .map(this::encodeEntry)
                                   .toList()).flatMap(lines -> seal(header, lines));
    }

    /// Parse a document produced by [#encode], and only such a document. Header faults and a checksum
    /// mismatch fail fast; entry faults are collected so the failure names every bad line at once.
    public Result<BackupDocument> decode(String document) {
        return option(document).toResult(BackupError.General.MISSING_DOCUMENT)
                     .flatMap(this::decodeDocument);
    }

    /// Header of a backup document.
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
        enum General implements BackupError {
            MISSING_DOCUMENT("No backup document was supplied");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        record ValueEncodingFailed(String key, Cause origin, String message) implements BackupError, Cause.Wrapped {
            static final Fn2<ValueEncodingFailed, String, Cause> FACTORY = Causes.forTwoValues("Value of '%s' could not be encoded: %s",
                                                                                               ValueEncodingFailed::new);
        }

        record KeyNotRoundTrippable(String key, String message) implements BackupError {
            static final Fn1<KeyNotRoundTrippable, String> FACTORY = Causes.forOneValue("Key '%s' does not parse back to itself from its canonical string",
                                                                                        KeyNotRoundTrippable::new);
        }

        record ValueTypeRefused(String key, String valueType, String message) implements BackupError {
            static final Fn2<ValueTypeRefused, String, String> FACTORY = Causes.forTwoValues("Key '%s' does not hold a %s",
                                                                                             ValueTypeRefused::new);
        }

        record ChecksumUnavailable(Cause origin, String message) implements BackupError, Cause.Wrapped {
            static final Fn1<ChecksumUnavailable, Cause> FACTORY = Causes.forOneValue("SHA-256 is unavailable: %s",
                                                                                      ChecksumUnavailable::new);
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

        record ChecksumMismatch(String declared, String computed, String message) implements BackupError {
            static final Fn2<ChecksumMismatch, String, String> FACTORY = Causes.forTwoValues("Checksum mismatch: header declares %s, document hashes to %s",
                                                                                             ChecksumMismatch::new);
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

        record ValueTypeMismatch(int lineNumber, String key, String valueType, String message) implements BackupError {
            static final Fn3<ValueTypeMismatch, Integer, String, String> FACTORY = Causes.forThreeValues("Line %d: key '%s' does not hold a %s",
                                                                                                         ValueTypeMismatch::new);
        }

        record NotAKvValue(String decodedType, String message) implements BackupError {
            static final Fn1<NotAKvValue, String> FACTORY = Causes.forOneValue("Decoded a %s, which is not a KV value",
                                                                               NotAKvValue::new);
        }

        record DuplicateKey(int lineNumber, String key, String message) implements BackupError {
            static final Fn2<DuplicateKey, Integer, String> FACTORY = Causes.forTwoValues("Line %d: key '%s' appears more than once",
                                                                                          DuplicateKey::new);
        }

        record NonCanonicalDocument(int lineNumber, String message) implements BackupError {
            static final Fn1<NonCanonicalDocument, Integer> FACTORY = Causes.forOneValue("Line %d: the document is not in canonical form",
                                                                                         NonCanonicalDocument::new);
        }
    }

    private record KeyBinding(Class<? extends ClusterStateKey> keyType,
                              Class<? extends AetherValue> valueType,
                              Fn1<Result<? extends ClusterStateKey>, String> parser) {
        static KeyBinding keyBinding(Class<? extends ClusterStateKey> keyType,
                                     Class<? extends AetherValue> valueType,
                                     Fn1<Result<? extends ClusterStateKey>, String> parser) {
            return new KeyBinding(keyType, valueType, parser);
        }
    }

    private record ParsedHeader(BackupHeader header, int declaredEntries, String checksum) {
        static ParsedHeader parsedHeader(BackupHeader header, int declaredEntries, String checksum) {
            return new ParsedHeader(header, declaredEntries, checksum);
        }
    }

    private record EncodedValue(AetherValue value, byte[] bytes) {
        static EncodedValue encodedValue(AetherValue value, byte[] bytes) {
            return new EncodedValue(value, bytes);
        }
    }

    private record NumberedEntry(int lineNumber, AetherKey key, AetherValue value) {
        static NumberedEntry numberedEntry(int lineNumber, AetherKey key, AetherValue value) {
            return new NumberedEntry(lineNumber, key, value);
        }
    }

    // --- encode ---
    private Result<String> encodeEntry(Map.Entry<AetherKey, AetherValue> entry) {
        return Result.all(ensureRoundTrips(entry.getKey()),
                          ensureHeldType(entry.getKey(),
                                         entry.getValue()),
                          encodeValue(entry.getKey(),
                                      entry.getValue()))
                     .map((key, _, encodedValue) -> formatEntry(key, encodedValue));
    }

    private static Result<AetherKey> ensureRoundTrips(AetherKey key) {
        return success(key).filter(BackupError.KeyNotRoundTrippable.FACTORY.apply(key.asString()),
                                   BackupEntryCodec::isRoundTrippable);
    }

    private static boolean isRoundTrippable(AetherKey key) {
        return parseCandidates(key.asString()).equals(List.of(key));
    }

    private static Result<AetherValue> ensureHeldType(AetherKey key, AetherValue value) {
        return success(value).filter(BackupError.ValueTypeRefused.FACTORY.apply(key.asString(),
                                                                                value.getClass().getSimpleName()),
                                     candidate -> isHeldBy(key, candidate));
    }

    /// Whether `value` is the one value type `key`'s type holds. A key type without a binding holds nothing.
    private static boolean isHeldBy(AetherKey key, AetherValue value) {
        return option(VALUE_TYPES.get(key.getClass())).filter(type -> type == value.getClass())
                     .isPresent();
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

    /// Header fields, then entries, then the checksum over both inserted as the last header line.
    private static Result<String> seal(BackupHeader header, List<String> entryLines) {
        var unsealed = Stream.concat(Stream.of(MAGIC + FORMAT_VERSION,
                                               REVISION + header.revision(),
                                               INCARNATION + escape(header.clusterIncarnation().or("")),
                                               ENTRIES + entryLines.size()),
                                     entryLines.stream())
                             .toList();

        return checksum(unsealed).map(sum -> joinLines(withChecksumLine(unsealed, sum)));
    }

    private static List<String> withChecksumLine(List<String> unsealed, String sum) {
        var lines = new ArrayList<>(unsealed);

        lines.add(CHECKSUM_LINE, CHECKSUM + sum);

        return lines;
    }

    private static String joinLines(List<String> lines) {
        return lines.stream()
                    .collect(Collectors.joining("\n", "", "\n"));
    }

    /// SHA-256, lowercase hex, over `lines` joined as a document is joined.
    static Result<String> checksum(List<String> lines) {
        return Result.lift(cause -> BackupError.ChecksumUnavailable.FACTORY.apply(Causes.fromThrowable(cause)),
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> HexFormat.of().formatHex(digest.digest(joinLines(lines).getBytes(StandardCharsets.UTF_8))));
    }

    // --- decode: document ---
    private Result<BackupDocument> decodeDocument(String document) {
        var lines = document.lines().toList();

        return parseHeader(lines).flatMap(parsed -> verifyChecksum(parsed, lines))
                          .flatMap(parsed -> decodeEntries(parsed, lines))
                          .flatMap(decoded -> ensureCanonical(document, decoded));
    }

    private static Result<ParsedHeader> verifyChecksum(ParsedHeader parsed, List<String> lines) {
        return checksum(withoutChecksumLine(lines)).flatMap(computed -> matchChecksum(parsed, computed));
    }

    private static List<String> withoutChecksumLine(List<String> lines) {
        return IntStream.range(0,
                               lines.size())
                        .filter(index -> index != CHECKSUM_LINE)
                        .mapToObj(lines::get)
                        .toList();
    }

    private static Result<ParsedHeader> matchChecksum(ParsedHeader parsed, String computed) {
        return parsed.checksum()
                     .equals(computed)
               ? success(parsed)
               : BackupError.ChecksumMismatch.FACTORY.apply(parsed.checksum(),
                                                            computed)
                                                     .result();
    }

    /// The decoded state must render back to exactly the document it came from.
    private Result<BackupDocument> ensureCanonical(String document, BackupDocument decoded) {
        return encode(decoded.header(), decoded.entries()).flatMap(rendered -> matchRendering(document,
                                                                                              rendered,
                                                                                              decoded));
    }

    private static Result<BackupDocument> matchRendering(String document, String rendered, BackupDocument decoded) {
        return document.equals(rendered)
               ? success(decoded)
               : BackupError.NonCanonicalDocument.FACTORY.apply(firstDifferingLine(document, rendered)).result();
    }

    private static int firstDifferingLine(String document, String rendered) {
        var left = document.split("\n", -1);
        var right = rendered.split("\n", -1);

        return IntStream.range(0,
                               Math.min(left.length, right.length))
                        .filter(index -> !left[index].equals(right[index]))
                        .findFirst()
                        .orElse(Math.min(left.length, right.length)) + 1;
    }

    // --- decode: header ---
    private static Result<ParsedHeader> parseHeader(List<String> lines) {
        return Result.all(headerField(lines, 0, MAGIC).flatMap(raw -> parseFormatVersion(raw, 1)),
                          headerField(lines, 1, REVISION).flatMap(raw -> parseRevision(raw, 2)),
                          headerField(lines, 2, INCARNATION).flatMap(raw -> parseIncarnation(raw, 3)),
                          headerField(lines, 3, ENTRIES).flatMap(raw -> parseEntryCount(raw, 4)),
                          headerField(lines, CHECKSUM_LINE, CHECKSUM))
                     .map((_, revision, incarnation, count, sum) -> ParsedHeader.parsedHeader(BackupHeader.backupHeader(revision,
                                                                                                                        incarnation),
                                                                                              count,
                                                                                              sum));
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

    private static Result<Unit> ensureEntryCount(int declared, int found) {
        return declared == found
               ? Result.unitResult()
               : BackupError.EntryCountMismatch.FACTORY.apply(declared, found).result();
    }

    /// Every entry line decoded, with all failures collected rather than the first.
    private Result<List<NumberedEntry>> decodeEntryLines(List<String> entryLines) {
        return Result.allOf(IntStream.range(0,
                                            entryLines.size())
                                     .mapToObj(index -> decodeEntry(HEADER_LINES + index + 1,
                                                                    entryLines.get(index)))
                                     .toList());
    }

    private Result<NumberedEntry> decodeEntry(int lineNumber, String line) {
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

    private Result<NumberedEntry> decodeKeyAndValue(int lineNumber, String key, String encodedValue) {
        return Result.all(decodeKey(lineNumber, key), decodeValue(lineNumber, key, encodedValue)).flatMap((decodedKey, value) -> ensureHeldType(lineNumber,
                                                                                                                                                decodedKey,
                                                                                                                                                value));
    }

    private static Result<NumberedEntry> ensureHeldType(int lineNumber, AetherKey key, AetherValue value) {
        return isHeldBy(key, value)
               ? success(NumberedEntry.numberedEntry(lineNumber, key, value))
               : BackupError.ValueTypeMismatch.FACTORY.apply(lineNumber,
                                                             key.asString(),
                                                             value.getClass().getSimpleName())
                                                      .result();
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

    /// Every repeated key is reported at the line that repeats it.
    private static Result<Map<AetherKey, AetherValue>> toEntryMap(List<NumberedEntry> entries) {
        var map = new HashMap<AetherKey, AetherValue>();
        var checked = new ArrayList<Result<NumberedEntry>>();

        for (var entry : entries) {
            checked.add(map.putIfAbsent(entry.key(), entry.value()) == null
                        ? success(entry)
                        : BackupError.DuplicateKey.FACTORY.apply(entry.lineNumber(),
                                                                 entry.key().asString()).result());
        }

        return Result.allOf(checked).map(_ -> Map.copyOf(map));
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
        return BINDINGS.stream()
                       .flatMap(binding -> tryParse(binding.parser(),
                                                    key).stream())
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
