// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.JsonTypeCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.utils.Causes.cause;

/// The runtime half of json/jsonb columns (#1779): `JsonText` encodes and decodes through a codec, a nullable read
/// decodes only a present value, and an accessor that cannot tell NULL from a failed read refuses `getOptional`.
class JsonColumnSupportTest {
    private static final JsonTypeCodec<String> STRING_CODEC = JsonTypeCodec.jsonTypeCodec((out, value) -> out.writeString(value),
                                                                                          in -> in.readString());
    private static final Cause BAD = cause("bad value");

    @Test
    void jsonText_encodesWithTheCodec_andDecodesBack() {
        var text = JsonText.jsonText(STRING_CODEC, "a\"b");

        assertThat(text.value()).isEqualTo("\"a\\\"b\"");
        assertThat(text.decode(STRING_CODEC)).isEqualTo(Result.success("a\"b"));
    }

    @Test
    void jsonText_keepsNonAsciiText() {
        var text = JsonText.jsonText(STRING_CODEC, "Київ 😀");

        assertThat(text.value()).isEqualTo("\"Київ 😀\"");
        assertThat(text.decode(STRING_CODEC)).isEqualTo(Result.success("Київ 😀"));
    }

    @Test
    void jsonText_decodeFailure_isTheCodecsFailure() {
        assertThat(JsonText.jsonText("42").decode(STRING_CODEC).isFailure()).isTrue();
    }

    @Test
    void liftOptional_leavesEmptyUndecoded() {
        var calls = new AtomicInteger();

        var lifted = RowDecodeError.liftOptional(Option.<String>none(), raw -> {
            calls.incrementAndGet();
            return BAD.<Integer>result();
        });

        assertThat(lifted).isEqualTo(Result.success(Option.none()));
        assertThat(calls).as("an empty read is never decoded").hasValue(0);
    }

    @Test
    void liftOptional_liftsPresentValue_andPropagatesItsFailure() {
        assertThat(RowDecodeError.liftOptional(Option.some("7"), raw -> Result.success(Integer.parseInt(raw))))
            .isEqualTo(Result.success(Option.some(7)));
        assertThat(RowDecodeError.liftOptional(Option.some("x"), _ -> BAD.<Integer>result()))
            .isEqualTo(BAD.<Option<Integer>>result());
    }

    @Test
    void defaultGetOptional_refusesInsteadOfGuessing() {
        RowMapper.RowAccessor accessor = new MinimalAccessor();

        var read = accessor.getOptional("doc", JsonText.class);

        assertThat(read.isFailure()).isTrue();
        read.onFailure(failure -> assertThat(failure.message()).contains("does not support nullable reads")
                                                               .contains(MinimalAccessor.class.getName()));
    }

    private static final class MinimalAccessor implements RowMapper.RowAccessor {
        @Override public Result<String> getString(String column) { return Result.success(""); }
        @Override public Result<Integer> getInt(String column) { return Result.success(0); }
        @Override public Result<Long> getLong(String column) { return Result.success(0L); }
        @Override public Result<Double> getDouble(String column) { return Result.success(0.0); }
        @Override public Result<Boolean> getBoolean(String column) { return Result.success(false); }
        @Override public Result<byte[]> getBytes(String column) { return Result.success(new byte[0]); }
        @Override public <V> Result<V> getObject(String column, Class<V> type) { return BAD.result(); }
    }
}
