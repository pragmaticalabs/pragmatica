/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pragmatica.postgres.r2dbc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pragmatica.postgres.Oid;
import org.pragmatica.postgres.conversion.DataConverter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2501: a read with no target (or the `Object` target jOOQ asks for) of a column type the driver has no Java type for
/// yields the server's text instead of being refused. Needs no server (the module's own tests are off by default, this module's run): the text is what the server sends for these types.
class UntypedTextReadTest {
    private static final List<Oid> TEXT_TYPES = List.of(Oid.JSON, Oid.JSONB, Oid.INTERVAL, Oid.INTERVAL_ARRAY, Oid.XML, Oid.XML_ARRAY, Oid.MONEY, Oid.MONEY_ARRAY, Oid.BIT, Oid.BIT_ARRAY, Oid.VARBIT, Oid.VARBIT_ARRAY, Oid.NAME, Oid.NAME_ARRAY, Oid.OID, Oid.OID_ARRAY, Oid.POINT, Oid.BOX, Oid.HSTORE, Oid.VOID, Oid.UNSPECIFIED);
    private final DataConverter converter = new DataConverter(List.of(), StandardCharsets.UTF_8);

    @Test void toObject_noTargetOrObjectTarget_ofATypeWithoutAJavaType_isItsText() {
        var text = "{\"a\": 1}".getBytes(StandardCharsets.UTF_8);

        for (var oid : TEXT_TYPES) {
            assertThat((Object) converter.toObject(oid, text, null, false)).as("%s, no target", oid).isEqualTo("{\"a\": 1}");
            assertThat(converter.toObject(oid, text, Object.class, false)).as("%s, Object target", oid).isEqualTo("{\"a\": 1}");
        }
    }

    @Test void toObject_ofATypeWithoutAJavaType_whenNull_isNull() {
        for (var oid : TEXT_TYPES) {
            assertThat((Object) converter.toObject(oid, null, null, false)).as("%s", oid).isNull();
        }
    }

    @Test void toObject_aTargetWithoutAConversion_isStillRefused() {
        var text = "{}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> converter.toObject(Oid.JSONB, text, java.util.Map.class, false)).isInstanceOf(IllegalArgumentException.class)
                                                                                                 .hasMessageContaining("java.util.Map");
    }

    @Test void toObject_aBinaryValueOfATypeWithoutAJavaType_isRefusedNamingTheType() {
        var bytes = new byte[]{0, 1, 2, 3};

        assertThatThrownBy(() -> converter.toObject(Oid.JSONB, bytes, null, true)).isInstanceOf(IllegalArgumentException.class)
                                                                                  .hasMessage(DataConverter.BINARY_UNTYPED_REFUSAL + Oid.JSONB);
        assertThatThrownBy(() -> converter.toObject(Oid.UNSPECIFIED, bytes, Object.class, true)).isInstanceOf(IllegalArgumentException.class)
                                                                                                .hasMessage(DataConverter.BINARY_UNTYPED_REFUSAL + Oid.UNSPECIFIED);
    }

    @Test void toObject_everyBinaryCapableType_neverReachesTheTextBranch() {
        var bytes = new byte[]{0, 1, 2, 3};

        for (var oid : Oid.values()) {
            if (oid.supportsBinary()) {
                assertThat(refusalOf(oid, bytes)).as("%s is binary-capable and needs a case of its own in toObject", oid).doesNotContain(DataConverter.BINARY_UNTYPED_REFUSAL);
            }
        }
    }

    /// The message of whatever the untyped binary read throws (a malformed payload may fail in the type's own decoder, which is fine here); empty when it returns.
    private String refusalOf(Oid oid, byte[] bytes) {
        try {
            converter.toObject(oid, bytes, null, true);
            return "";
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
    }
}
