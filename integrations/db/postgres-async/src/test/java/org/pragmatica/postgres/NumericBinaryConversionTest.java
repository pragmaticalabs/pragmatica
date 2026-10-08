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

package org.pragmatica.postgres;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.pragmatica.postgres.conversion.DataConverter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Binary NUMERIC (`ndigits, weight, sign, dscale`, then base-10000 digits) read through the generic path as a float, a
/// double or a BigDecimal. The driver itself asks for text NUMERIC (`Oid.supportsBinary`), so this pins the converter for a
/// caller that hands it binary NUMERIC, not a path the driver exercises.
class NumericBinaryConversionTest {
    private final DataConverter converter = new DataConverter(List.of(), StandardCharsets.UTF_8);

    private static byte[] numeric(int weight, int sign, int scale, int... digits) {
        var buffer = ByteBuffer.allocate(8 + 2 * digits.length);

        buffer.putShort((short) digits.length).putShort((short) weight).putShort((short) sign).putShort((short) scale);
        for (var digit : digits) {
            buffer.putShort((short) digit);
        }

        return buffer.array();
    }

    @Test void toObject_binaryNumeric_decodesAsDoubleFloatAndBigDecimal() {
        var positive = numeric(1, 0x0000, 4, 1, 2345, 6789);
        var negativeFraction = numeric(-1, 0x4000, 5, 1, 2000);
        var zero = numeric(0, 0x0000, 2);

        assertThat(converter.toObject(Oid.NUMERIC, positive, Double.class, true)).isEqualTo(12345.6789);
        assertThat(converter.toObject(Oid.NUMERIC, positive, Float.class, true)).isEqualTo(12345.6789f);
        assertThat(converter.toObject(Oid.NUMERIC, positive, BigDecimal.class, true)).isEqualByComparingTo("12345.6789");
        assertThat(converter.toObject(Oid.NUMERIC, positive, BigDecimal.class, true).scale()).isEqualTo(4);
        assertThat(converter.toObject(Oid.NUMERIC, negativeFraction, Double.class, true)).isEqualTo(-0.00012);
        assertThat(converter.toObject(Oid.NUMERIC, negativeFraction, BigDecimal.class, true)).isEqualTo(new BigDecimal("-0.00012"));
        assertThat(converter.toObject(Oid.NUMERIC, zero, Double.class, true)).isEqualTo(0.0);
        assertThat(converter.toObject(Oid.NUMERIC, zero, BigDecimal.class, true)).isEqualTo(new BigDecimal("0.00"));
    }

    @Test void toObject_binaryNumericSpecialValues_mapToDoubleAndRefuseBigDecimal() {
        var nan = numeric(0, 0xC000, 0);
        var positiveInfinity = numeric(0, 0xD000, 0);
        var negativeInfinity = numeric(0, 0xF000, 0);

        assertThat(converter.toObject(Oid.NUMERIC, nan, Double.class, true)).isNaN();
        assertThat(converter.toObject(Oid.NUMERIC, positiveInfinity, Double.class, true)).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(converter.toObject(Oid.NUMERIC, negativeInfinity, Double.class, true)).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThatThrownBy(() -> converter.toObject(Oid.NUMERIC, nan, BigDecimal.class, true))
            .hasMessageContaining("NaN");
    }

    @Test void toObject_textNumeric_unchanged() {
        assertThat(converter.toObject(Oid.NUMERIC, "12345.6789".getBytes(StandardCharsets.UTF_8), Double.class, false)).isEqualTo(12345.6789);
        assertThat(converter.toObject(Oid.NUMERIC, "12345.6789".getBytes(StandardCharsets.UTF_8), BigDecimal.class, false))
            .isEqualTo(new BigDecimal("12345.6789"));
    }
}
