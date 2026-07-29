// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import static org.assertj.core.api.Assertions.assertThat;

/// #542 — `SchemaVersionValue` gained a REQUIRED `owningBlueprint` component. This pins the wire
/// format on the path that actually carries the record in production: the generated `@Codec`
/// registry (`KvstoreCodecsSlice.CODECS`), which `KVStore` uses as both `Serializer` and
/// `Deserializer` for consensus replication and snapshot transfer.
///
/// The guard matters because ownership is not decorative — `ClusterDeploymentState.areSchemasReady`
/// decides whether a slice may activate by matching this field, and a follower that decoded it as
/// anything other than what the leader encoded would gate on the wrong blueprint. This is the #530
/// failure shape (a type that serialized but did not survive the return trip, unnoticed because
/// nothing read it back) applied to a field that IS read back.
class SchemaVersionCodecTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(KvstoreCodecsSlice.CODECS);
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final String DATASOURCE = "database.orders";

    @Nested
    class ValueRoundTrip {
        @Test
        void schemaVersionValue_roundTrips_withOwningBlueprint() {
            var original = SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                                 3,
                                                                 "V003__add_index.sql",
                                                                 SchemaStatus.FAILED,
                                                                 "org.example:orders-app:1.0.0",
                                                                 OWNER,
                                                                 2);

            var decoded = roundTrip(original);

            assertThat(decoded).as("every component must survive the replication round-trip")
                               .isEqualTo(original);
            assertThat(decoded.owningBlueprint()).as("the gate matches slices to records by this field")
                                                 .isEqualTo(OWNER);
        }

        /// The owner is compared on `ArtifactBase`, so the version segment has to survive too — a
        /// codec that dropped it would silently widen every ownership match.
        @Test
        void schemaVersionValue_roundTrips_preservingOwnerVersionSegment() {
            var owner = BlueprintId.blueprintId("org.example:orders-app:2.5.1").unwrap();
            var original = SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                                 1,
                                                                 "V001__init.sql",
                                                                 SchemaStatus.PENDING,
                                                                 "org.example:orders-app:2.5.1",
                                                                 owner);

            var decoded = roundTrip(original);

            assertThat(decoded.owningBlueprint().asString()).isEqualTo("org.example:orders-app:2.5.1");
            assertThat(decoded.owningBlueprint().base()).isEqualTo(owner.base());
        }

        @Test
        void schemaVersionValue_roundTrips_forEveryStatus() {
            for (var status : SchemaStatus.values()) {
                var original = SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                                     1,
                                                                     "V001__init.sql",
                                                                     status,
                                                                     "org.example:orders-app:1.0.0",
                                                                     OWNER);

                assertThat(roundTrip(original)).as("status %s must survive the round-trip", status)
                                               .isEqualTo(original);
            }
        }
    }

    @Nested
    class KeyRoundTrip {
        @Test
        void schemaVersionKey_roundTrips_forDatasourceName() {
            var original = SchemaVersionKey.schemaVersionKey(DATASOURCE);

            assertThat(roundTripKey(original)).isEqualTo(original);
        }
    }

    private static SchemaVersionValue roundTrip(SchemaVersionValue original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }

    private static SchemaVersionKey roundTripKey(SchemaVersionKey original) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, original);

        return CODEC.read(buffer);
    }
}
