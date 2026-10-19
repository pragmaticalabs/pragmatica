// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.SharedTierConfig.SharedTierConfigError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #249: `[storage.<instance>.shared]` -- `kind = "dht"` (default) or `"s3"`, with the S3 keys, typed refusals,
/// and inline secrets rejected.
class ConfigLoaderSharedTierTest {
    private static final String S3_SECTION = """
        [storage.content.shared]
        kind = "s3"
        endpoint = "https://s3.eu-central-1.amazonaws.com"
        region = "eu-central-1"
        bucket = "aether-content"
        prefix = "cluster-a/"
        path_style = false
        credentials = "env"
        max_inflight = 16
        request_timeout = "45s"
        """;

    private static AetherConfig load(String toml) {
        return ConfigLoader.loadFromString(toml).fold(cause -> fail(cause.message()), config -> config);
    }

    /// The single refusal behind the load failure. `Result.all`/`allOf` aggregate into a composite (so an
    /// operator sees every missing key at once); each test provokes exactly one, which is returned unwrapped.
    private static Cause refusal(String toml) {
        var cause = ConfigLoader.loadFromString(toml).fold(failure -> failure, config -> fail("expected a refusal, loaded " + config.storage()));
        var leaves = leaves(cause).toList();

        assertThat(leaves).as(cause.message()).hasSize(1);

        return leaves.getFirst();
    }

    private static Stream<Cause> leaves(Cause cause) {
        return cause instanceof Causes.CompositeCause
               ? cause.stream().flatMap(ConfigLoaderSharedTierTest::leaves)
               : Stream.of(cause);
    }

    @Nested
    class Valid {

        @Test
        void s3Section_parsesEveryKey() {
            var shared = load(S3_SECTION).storage().get("content").shared();

            assertThat(shared).isEqualTo(new SharedTierConfig.S3("https://s3.eu-central-1.amazonaws.com",
                                                                 "eu-central-1",
                                                                 "aether-content",
                                                                 "cluster-a/",
                                                                 false,
                                                                 new SharedTierConfig.Credentials.Environment(),
                                                                 16,
                                                                 timeSpan(45).seconds()));
        }

        @Test
        void s3Section_defaults_pathStyleFalse_maxInflight8_timeout30s_noPrefix() {
            var shared = (SharedTierConfig.S3) load("""
                [storage.content.shared]
                kind = "s3"
                endpoint = "http://minio:9000"
                region = "us-east-1"
                bucket = "b"
                credentials = "file:/etc/aether/s3-credentials"
                """).storage().get("content").shared();

            assertThat(shared.pathStyle()).isFalse();
            assertThat(shared.maxInflight()).isEqualTo(8);
            assertThat(shared.requestTimeout()).isEqualTo(timeSpan(30).seconds());
            assertThat(shared.prefix()).isEmpty();
            assertThat(shared.credentials()).isEqualTo(new SharedTierConfig.Credentials.File(Path.of("/etc/aether/s3-credentials")));
        }

        @Test
        void instanceCredentials_parse() {
            var shared = (SharedTierConfig.S3) load(S3_SECTION.replace("credentials = \"env\"", "credentials = \"instance\""))
                .storage().get("content").shared();

            assertThat(shared.credentials()).isEqualTo(new SharedTierConfig.Credentials.InstanceProfile());
        }

        @Test
        void noSection_isTheDhtDefault_andChangesNothing() {
            var storage = load("""
                [storage.artifacts]
                disk_path = "/tmp/a"
                """).storage();

            assertThat(storage.get("artifacts").shared()).isEqualTo(SharedTierConfig.dht());
            assertThat(storage).containsOnlyKeys("artifacts");
        }

        /// C17: a section without `kind` is the DHT. Mutation that reddens it: default `kind` to `s3` (the section then
        /// misses every S3 key).
        @Test
        void absentKind_isTheDht() {
            assertThat(load("[storage.content.shared]\n").storage().get("content").shared()).isEqualTo(SharedTierConfig.dht());
        }

        @Test
        void explicitDht_isTheDefault() {
            assertThat(load("[storage.artifacts.shared]\nkind = \"dht\"\n").storage().get("artifacts").shared())
                .isEqualTo(SharedTierConfig.dht());
        }

        /// The sub-section is not an instance named `content.shared`, and alone it yields a configuration the node
        /// places as if `[storage.content]` were absent.
        @Test
        void sharedOnlySection_attachesToItsInstance_withDerivedPaths() {
            var storage = load(S3_SECTION).storage();

            assertThat(storage).containsOnlyKeys("content");
            assertThat(storage.get("content").derivesPaths()).isTrue();
        }

        /// N3: a section of another name that sets neither path leaves them to the node; `artifacts` and `content`,
        /// and a section that sets a path, keep what they resolve to. Mutations that redden it: never mark; mark
        /// `artifacts` too; mark a section that sets `disk_path` or `snapshot_path`.
        @Test
        void pathlessSection_ofAnotherName_derivesItsPaths() {
            var storage = load("""
                [storage.vault]
                memory_max_bytes = 1234

                [storage.archive]
                disk_path = "/srv/archive/blocks"

                [storage.journal]
                snapshot_path = "/srv/journal/snapshots"

                [storage.artifacts]
                encrypted = false
                """).storage();

            assertThat(storage.get("vault").derivesPaths()).isTrue();
            assertThat(storage.get("archive").derivesPaths()).isFalse();
            assertThat(storage.get("journal").derivesPaths()).isFalse();
            assertThat(storage.get("artifacts").derivesPaths()).isFalse();
        }

        @Test
        void sharedSection_keepsTheParentSectionsSettings() {
            var content = load("""
                [storage.content]
                disk_path = "/srv/content/blocks"
                snapshot_path = "/srv/content/snapshots"
                encrypted = false
                """ + S3_SECTION).storage().get("content");

            assertThat(content.diskPath()).isEqualTo("/srv/content/blocks");
            assertThat(content.derivesPaths()).isFalse();
            assertThat(content.shared()).isInstanceOf(SharedTierConfig.S3.class);
        }
    }

    @Nested
    class Refused {

        /// Mutation that reddens it: drop `refuseSecretKeys` (the key then falls to UnknownKey, a different type).
        @Test
        void inlineSecretKey_isRejected_withoutEchoingTheValue() {
            var cause = refusal(S3_SECTION + "secret_access_key = \"wJalrXUtnFEMI/K7MDENG\"\n");

            assertThat(cause).isInstanceOf(SharedTierConfigError.InlineSecretRejected.class);
            assertThat(cause.message()).contains("secret_access_key").doesNotContain("wJalrXUtnFEMI");
        }

        @Test
        void inlineSecretKey_isRejected_evenForKindDht() {
            assertThat(refusal("[storage.content.shared]\naccess_key_id = \"AKIA123\"\n"))
                .isInstanceOf(SharedTierConfigError.InlineSecretRejected.class);
        }

        @Test
        void inlineSecretAsCredentialsValue_isRejected_withoutEchoingIt() {
            var cause = refusal(S3_SECTION.replace("credentials = \"env\"", "credentials = \"AKIA123:wJalrXUtnFEMI\""));

            assertThat(cause).isInstanceOf(SharedTierConfigError.InvalidCredentials.class);
            assertThat(cause.message()).doesNotContain("wJalrXUtnFEMI").doesNotContain("AKIA123");
        }

        @Test
        void missingBucket_isRefusedNamingIt() {
            var cause = refusal(S3_SECTION.replace("bucket = \"aether-content\"\n", ""));

            assertThat(cause).isInstanceOf(SharedTierConfigError.MissingKey.class);
            assertThat(cause.message()).contains("bucket").contains("content");
        }

        @Test
        void missingCredentials_isRefused() {
            assertThat(refusal(S3_SECTION.replace("credentials = \"env\"\n", "")))
                .isInstanceOf(SharedTierConfigError.MissingKey.class);
        }

        @Test
        void unknownKind_isRefused() {
            var cause = refusal("[storage.content.shared]\nkind = \"nfs\"\n");

            assertThat(cause).isInstanceOf(SharedTierConfigError.UnknownKind.class);
            assertThat(cause.message()).contains("nfs");
        }

        /// Reserved for the durable DHT phase; until it lands it must not be silently read as the in-memory DHT.
        @Test
        void dhtDurable_isNotYetAKind() {
            assertThat(refusal("[storage.content.shared]\nkind = \"dht-durable\"\n"))
                .isInstanceOf(SharedTierConfigError.UnknownKind.class);
        }

        @Test
        void unknownKey_isRefused() {
            assertThat(refusal(S3_SECTION + "multipart = true\n")).isInstanceOf(SharedTierConfigError.UnknownKey.class);
        }

        @Test
        void s3KeysUnderKindDht_areRefused() {
            assertThat(refusal("[storage.content.shared]\nkind = \"dht\"\nbucket = \"b\"\n"))
                .isInstanceOf(SharedTierConfigError.UnknownKey.class);
        }

        @Test
        void nonPositiveMaxInflight_isRefused() {
            assertThat(refusal(S3_SECTION.replace("max_inflight = 16", "max_inflight = 0")))
                .isInstanceOf(SharedTierConfigError.InvalidValue.class);
        }

        /// C18: a required key holding only blanks is missing. Mutation that reddens it: keep blank values.
        @Test
        void blankEndpoint_isRefusedAsMissing() {
            var cause = refusal(S3_SECTION.replace("endpoint = \"https://s3.eu-central-1.amazonaws.com\"", "endpoint = \"  \""));

            assertThat(cause).isInstanceOf(SharedTierConfigError.MissingKey.class);
            assertThat(cause.message()).contains("endpoint");
        }

        /// C21: `file:` with no path names no file. Mutation that reddens it: accept an empty path.
        @Test
        void fileCredentialsWithoutAPath_areRefused() {
            assertThat(refusal(S3_SECTION.replace("credentials = \"env\"", "credentials = \"file:  \"")))
                .isInstanceOf(SharedTierConfigError.InvalidCredentials.class);
        }

        /// C23: a zero timeout would expire every request. Mutation that reddens it: accept a zero duration.
        @Test
        void zeroTimeout_isRefused() {
            var cause = refusal(S3_SECTION.replace("request_timeout = \"45s\"", "request_timeout = \"0s\""));

            assertThat(cause).isInstanceOf(SharedTierConfigError.InvalidValue.class);
            assertThat(cause.message()).contains("request_timeout");
        }

        @Test
        void malformedTimeout_isRefused() {
            assertThat(refusal(S3_SECTION.replace("request_timeout = \"45s\"", "request_timeout = \"soon\"")))
                .isInstanceOf(SharedTierConfigError.InvalidValue.class);
        }

        @Test
        void nonBooleanPathStyle_isRefused() {
            assertThat(refusal(S3_SECTION.replace("path_style = false", "path_style = \"yes\"")))
                .isInstanceOf(SharedTierConfigError.InvalidValue.class);
        }

        /// Stream storage is redesigned separately; the refusal says why. Mutation that reddens it: drop
        /// `refuseStreamsOffDht`.
        @Test
        void streamsSharedS3_isRefused_namingTheReason() {
            var cause = refusal(S3_SECTION.replace("[storage.content.shared]", "[storage.streams.shared]"));

            assertThat(cause).isInstanceOf(SharedTierConfigError.StreamsSharedTierUnsupported.class);
            assertThat(cause.message()).contains("streams").contains("redesigned").contains("DHT");
        }

        @Test
        void streamsSharedDht_isAccepted() {
            assertThat(load("[storage.streams.shared]\nkind = \"dht\"\n").storage().get("streams").shared())
                .isEqualTo(SharedTierConfig.dht());
        }

        /// Mutation that reddens it: drop `refuseReserved` (an instance named `encryption` is then created).
        @Test
        void encryptionShared_isReserved_notAnInstance() {
            assertThat(refusal(S3_SECTION.replace("[storage.content.shared]", "[storage.encryption.shared]")))
                .isInstanceOf(SharedTierConfigError.ReservedInstance.class);
        }
    }

    /// Secrets and unknown keys nested under the shared section: sub-tables, dotted keys, any case. Mutations that
    /// redden them: inspect only the section's own keys (sub-tables load), or compare secret names case-sensitively.
    @Nested
    class NestedUnderShared {

        @Test
        void secretInASubTable_isRejected() {
            var cause = refusal(S3_SECTION + "\n[storage.content.shared.creds]\nsecret_access_key = \"wJalrXUtnFEMI\"\n");

            assertThat(cause).isInstanceOf(SharedTierConfigError.InlineSecretRejected.class);
            assertThat(cause.message()).contains("creds.secret_access_key").doesNotContain("wJalrXUtnFEMI");
        }

        @Test
        void secretAsADottedKey_isRejected() {
            var cause = refusal(S3_SECTION + "aws.secret_access_key = \"wJalrXUtnFEMI\"\n");

            assertThat(cause).isInstanceOf(SharedTierConfigError.InlineSecretRejected.class);
            assertThat(cause.message()).contains("aws.secret_access_key").doesNotContain("wJalrXUtnFEMI");
        }

        @Test
        void secretKey_isRejectedCaseInsensitively() {
            assertThat(refusal(S3_SECTION + "Secret_Access_Key = \"wJalrXUtnFEMI\"\n"))
                .isInstanceOf(SharedTierConfigError.InlineSecretRejected.class);
            assertThat(refusal(S3_SECTION + "AWS.SESSION_TOKEN = \"t\"\n"))
                .isInstanceOf(SharedTierConfigError.InlineSecretRejected.class);
        }

        @Test
        void secretSubTableWithoutTheSharedSection_isRejected() {
            assertThat(refusal("[storage.content.shared.creds]\npassword = \"p\"\n"))
                .isInstanceOf(SharedTierConfigError.InlineSecretRejected.class);
        }

        @Test
        void nonSecretSubTable_isAnUnknownKey() {
            var cause = refusal(S3_SECTION + "\n[storage.content.shared.tuning]\nretries = 3\n");

            assertThat(cause).isInstanceOf(SharedTierConfigError.UnknownKey.class);
            assertThat(cause.message()).contains("tuning");
        }
    }

    /// A `[storage.<name>.<sub>]` section that is neither the keyring nor a shared tier is refused, naming every such
    /// section, sorted. Mutation that reddens it: drop `refuseUnknownStorageSubSections` (the sections are then
    /// silently dropped and the load succeeds).
    @Nested
    class SubSections {

        @Test
        void typoForShared_isRefused_namingIt() {
            assertThat(refusal(S3_SECTION.replace("[storage.content.shared]", "[storage.foo.shard]")).message())
                .contains("[storage.foo.shard]")
                .contains("only [storage.<name>.shared] and [storage.encryption.keys] sub-sections are accepted");
        }

        /// O1: the keyring accepts only the `keys` table the loader reads; a typo there would boot with no keys.
        /// Mutation that reddens it: accept every `[storage.encryption.*]` section again.
        @Test
        void typoUnderTheKeyring_isRefused_namingIt() {
            assertThat(refusal("[storage.encryption]\nactive_key_id = \"k1\"\n[storage.encryption.kyes]\nk1 = \"${secrets:k1}\"\n")
                           .message()).contains("[storage.encryption.kyes]");
        }

        @Test
        void tableNestedUnderTheKeyringKeys_isRefused() {
            assertThat(refusal("[storage.encryption.keys]\nk1 = \"${secrets:k1}\"\n[storage.encryption.keys.more]\nk2 = \"x\"\n")
                           .message()).contains("[storage.encryption.keys.more]");
        }

        @Test
        void dottedLegacyInstanceName_isRefused() {
            assertThat(refusal("[storage.a.b]\ndisk_path = \"/srv/ab\"\n").message()).contains("[storage.a.b]");
        }

        @Test
        void everyUnknownSubSection_isNamed_sorted() {
            assertThat(refusal("[storage.z.y]\nk = 1\n[storage.a.b]\nk = 1\n[storage.m.n.o]\nk = 1\n").message())
                .contains("[storage.a.b], [storage.m.n.o], [storage.z.y]:");
        }

        /// CONTROL: the keyring with its sub-table, and an instance with its shared section, load. Nothing nested under
        /// a shared section loads today; it is left to the shared-tier refusals, which [NestedUnderShared] pins by type.
        @Test
        void keyringAndSharedShapes_load() {
            var config = load("""
                [storage.encryption]
                active_key_id = "k1"

                [storage.encryption.keys]
                k1 = "${secrets:k1}"

                [storage.content]
                disk_path = "/srv/content/blocks"

                [storage.content.shared]
                kind = "dht"

                [storage.artifacts.shared]
                kind = "dht"
                """);

            assertThat(config.storage()).containsOnlyKeys("content", "artifacts");
            assertThat(config.storageEncryption().isPresent()).isTrue();
        }
    }

    /// An explicit blank path names no directory; the node would place the instance on it as written. Mutation that
    /// reddens it: drop `refuseBlankStoragePaths`.
    @Nested
    class BlankPaths {

        @Test
        void blankDiskPath_isRefused() {
            assertThat(refusal("[storage.foo]\ndisk_path = \"\"\nmemory_max_bytes = 1234\n").message())
                .contains("[storage.foo] disk_path must not be blank");
        }

        @Test
        void blankSnapshotPath_isRefused() {
            assertThat(refusal("[storage.foo]\nsnapshot_path = \" \"\n").message()).contains("snapshot_path");
        }
    }
}
