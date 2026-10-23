// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.JsonText;
import org.pragmatica.postgres.Oid;
import org.pragmatica.postgres.conversion.DataConverter;
import org.pragmatica.postgres.net.ConnectibleBuilder;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// `JsonText` on the async driver (#1779): bound as text with an unspecified parameter type (so one value fits a
/// `json` and a `jsonb` column), read back as text, and registered by both async connector factories. The live
/// binding against PostgreSQL is pinned by pg-codegen's `JsonbPostgresRoundTripTest`.
class JsonTextConverterTest {
    private static final String DOCUMENT = "{\"name\":\"Ann\"}";

    private final DataConverter converter = new DataConverter(List.of(JsonTextConverter.INSTANCE), StandardCharsets.UTF_8);

    /// Tripwire. A binary `jsonb` value starts with a format version byte (0x01). Neither `JsonTextConverter.to` nor a
    /// `String` read of the column (`DataConverter.binaryText`, default branch) strips it, so making `json` or `jsonb`
    /// binary in `Oid.supportsBinary` would put that byte at the front of every document read.
    @Test
    void jsonAndJsonbResults_stayTextFormat() {
        assertThat(Oid.JSONB.supportsBinary()).as("jsonb became binary: strip the 0x01 version byte in JsonTextConverter.to "
                                                  + "and in DataConverter.binaryText before enabling it")
                                              .isFalse();
        assertThat(Oid.JSON.supportsBinary()).as("json became binary: update JsonTextConverter.to and DataConverter.binaryText")
                                             .isFalse();
    }

    @Test
    void jsonTextParameter_isTextWithUnspecifiedType() {
        var param = JsonText.jsonText(DOCUMENT);

        assertThat(converter.assumeTypes(param)).containsExactly(Oid.UNSPECIFIED);
        assertThat(new String(converter.fromParameters(new Object[]{param})[0], StandardCharsets.UTF_8)).isEqualTo(DOCUMENT);
    }

    @Test
    void jsonAndJsonbColumns_readAsJsonText() {
        var bytes = DOCUMENT.getBytes(StandardCharsets.UTF_8);

        assertThat(converter.toObject(Oid.JSONB, bytes, JsonText.class)).isEqualTo(JsonText.jsonText(DOCUMENT));
        assertThat(converter.toObject(Oid.JSON, bytes, JsonText.class)).isEqualTo(JsonText.jsonText(DOCUMENT));
    }

    @Test
    void nonAsciiDocument_readsAsUtf8() {
        var document = "{\"city\":\"Київ 😀\"}";

        assertThat(converter.toObject(Oid.JSONB, document.getBytes(StandardCharsets.UTF_8), JsonText.class))
            .isEqualTo(JsonText.jsonText(document));
    }

    @Test
    void pgSqlConnectorFactory_registersTheConverter() {
        assertRegistered(configured(PgSqlConnectorFactory.class));
    }

    @Test
    void asyncSqlConnectorFactory_registersTheConverter() {
        assertRegistered(configured(AsyncSqlConnectorFactory.class));
    }

    private static void assertRegistered(DataConverter configured) {
        assertThat(configured.toObject(Oid.JSONB, DOCUMENT.getBytes(StandardCharsets.UTF_8), JsonText.class))
            .isEqualTo(JsonText.jsonText(DOCUMENT));
    }

    // The factories' private configureConnection, by reflection as in AsyncSqlConnectorFactoryConfigureConnectionTest.
    private static DataConverter configured(Class<?> factory) {
        var config = databaseConnectorConfigBuilder().withName("db")
                                                     .withAsyncUrl("postgresql://localhost:5432/db")
                                                     .build()
                                                     .unwrap();
        var builder = new InspectableBuilder();

        try {
            Method method = factory.getDeclaredMethod("configureConnection",
                                                      NettyConnectibleBuilder.class,
                                                      DatabaseConnectorConfig.class);
            method.setAccessible(true);
            method.invoke(null, builder, config);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("configureConnection invocation failed", e);
        }

        return builder.configuration().dataConverter();
    }

    private static final class InspectableBuilder extends NettyConnectibleBuilder {
        ConnectibleBuilder.ConnectibleConfiguration configuration() {
            return properties;
        }
    }
}
