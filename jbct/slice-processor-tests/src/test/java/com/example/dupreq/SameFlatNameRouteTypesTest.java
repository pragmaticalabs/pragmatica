// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.dupreq;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.pragmatica.serialization.json.JsonTypeCodec;

import com.example.dupreq.y.Req;

import static org.assertj.core.api.Assertions.assertThat;

/// Route types of two packages sharing a simple name (`y.Req`, `z.Req`) share a flat name, so the routes holder
/// numbers their nested codecs: it compiles, lists each under its own class, and round-trips both.
class SameFlatNameRouteTypesTest {
    @Test
    void routesHolder_givesSameFlatNameTypesDistinctCodecs_andRoundTripsBoth() throws IOException {
        var named = codec(Req.class);
        var counted = codec(com.example.dupreq.z.Req.class);

        assertThat(DupReqSliceRoutesJsonCodecs.CODECS).containsOnlyKeys(Req.class,
                                                                        com.example.dupreq.z.Req.class);
        assertThat(new String(named.encode(new Req("a")), StandardCharsets.UTF_8)).isEqualTo("{\"name\":\"a\"}");
        assertThat(named.decode("{\"name\":\"b\"}").unwrap()).isEqualTo(new Req("b"));
        assertThat(new String(counted.encode(new com.example.dupreq.z.Req(4)), StandardCharsets.UTF_8)).isEqualTo("{\"count\":4}");
        assertThat(counted.decode("{\"count\":5}").unwrap()).isEqualTo(new com.example.dupreq.z.Req(5));
        assertThat(Files.readString(generatedFile("DupReqSliceRoutesJsonCodecs.java"))).contains("interface ReqJson {")
                                                                                     .contains("interface ReqJson2 {");
    }

    @SuppressWarnings("unchecked")
    private static <T> JsonTypeCodec<T> codec(Class<T> type) {
        return (JsonTypeCodec<T>) DupReqSliceRoutesJsonCodecs.CODECS.get(type);
    }

    private static Path generatedFile(String name) {
        var relative = Paths.get("target", "generated-sources", "annotations", "com", "example", "dupreq", name);
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var candidate = moduleDir.resolve(relative);

        return Files.exists(candidate)
               ? candidate
               : moduleDir.resolve(Path.of("jbct", "slice-processor-tests")).resolve(relative);
    }
}
