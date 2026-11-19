// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaReach;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue_SchemaReachCodec;
import org.pragmatica.aether.slice.kvstore.AetherValue_SchemaVersionValueCodec;
import org.pragmatica.aether.slice.kvstore.LegacySchemaVersionValue;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;

/// #834: `SchemaVersionValue` gained `reachedBy`, and a record is a positional concatenation, so the new shape cannot read the old
/// bytes. A snapshot decodes as one unit, so one old-shape row under the tag that now meant the new shape would fail the whole
/// restore. The change is therefore ADDITIVE: the new shape has its own tag (2980), and tag 1638 keeps decoding the old shape, as the
/// current type with `reachedBy = MIGRATED` (absent = the pre-#834 behaviour: no republish guard, since nothing recorded an undo or
/// baseline). Encoding always writes the new tag.
class SchemaVersionValueWireCompatibilityTest {
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final SchemaVersionValue MIGRATED_ROW = SchemaVersionValue.schemaVersionValue("database.orders",
                                                                                              3,
                                                                                              "V003__add_index.sql",
                                                                                              SchemaStatus.COMPLETED,
                                                                                              "org.example:orders-app:1.0.0",
                                                                                              OWNER,
                                                                                              2,
                                                                                              SchemaReach.MIGRATED);
    private static final LegacySchemaVersionValue OLD_SHAPE = new LegacySchemaVersionValue(MIGRATED_ROW.datasourceName(),
                                                                                           MIGRATED_ROW.currentVersion(),
                                                                                           MIGRATED_ROW.lastMigration(),
                                                                                           MIGRATED_ROW.status(),
                                                                                           MIGRATED_ROW.artifactCoords(),
                                                                                           MIGRATED_ROW.owningBlueprint(),
                                                                                           MIGRATED_ROW.attemptCount(),
                                                                                           MIGRATED_ROW.updatedAt());
    /// Tag 1638 (VLQ e60c) and tag 2980 (VLQ a417), then, after the eight shared components, the new shape's `reachedBy`: the enum's
    /// tag 2981 (VLQ a517) and its ordinal.
    private static final String OLD_TAG = "e60c";
    private static final String NEW_TAG = "a417";
    private static final String REACH_TAG = "a517";

    private static String hex(Object value) {
        return HexFormat.of().formatHex(CODEC.encode(value));
    }

    /// The old bytes are not only what this test's own writer emits: the generated codec of the current shape, an independent
    /// producer, writes the same eight components and then `reachedBy`; stripping the new tag and that tail leaves the old body.
    private static String oldBytes() {
        var current = hex(MIGRATED_ROW);

        return OLD_TAG + current.substring(NEW_TAG.length(), current.length() - REACH_TAG.length() - 2);
    }

    @Test
    void theTagsAreAssigned_theOldTagKeepsTheOldShape_theCurrentShapeTakesANewOne() {
        assertThat(LegacySchemaVersionValue.TAG).isEqualTo(1638);
        assertThat(AetherValue_SchemaVersionValueCodec.TAG).isEqualTo(2980);
        assertThat(AetherValue_SchemaReachCodec.TAG).isEqualTo(2981);
    }

    @Test
    void anOldTagRow_decodesAsTheCurrentTypeReachedByMigrated() {
        var decoded = (SchemaVersionValue) CODEC.decode(HexFormat.of().parseHex(oldBytes()));

        assertThat(decoded).isEqualTo(MIGRATED_ROW);
        assertThat(decoded.reachedBy().guardsRepublish()).as("absent = the pre-#834 behaviour: no guard").isFalse();
    }

    @Test
    void theOldShapeEncodesToTheBytesAnOlderBuildWrote() {
        assertThat(hex(OLD_SHAPE)).isEqualTo(oldBytes());
    }

    @Test
    void aCurrentRow_alwaysEncodesUnderTheNewTag_andRoundTripsItsReach() {
        for (var reach : SchemaReach.values()) {
            var row = SchemaVersionValue.schemaVersionValue("database.orders",
                                                            3,
                                                            "V003__add_index.sql",
                                                            SchemaStatus.COMPLETED,
                                                            "org.example:orders-app:1.0.0",
                                                            OWNER,
                                                            2,
                                                            reach);
            var bytes = CODEC.encode(row);
            Object decoded = CODEC.decode(bytes);

            assertThat(HexFormat.of().formatHex(bytes)).startsWith(NEW_TAG).contains(REACH_TAG);
            assertThat(decoded).as("reach %s must survive the round-trip", reach).isEqualTo(row);
        }
    }
}
