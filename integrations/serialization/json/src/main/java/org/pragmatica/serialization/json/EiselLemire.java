/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.serialization.json;

/// Eisel-Lemire decimal-to-double conversion (Lemire, "Number Parsing at a Gigabyte per Second",
/// 2021; the simdjson/fast_float formulation). Exact whenever it answers; returns NaN when it cannot
/// decide (round-to-even ties, subnormals, overflow), and the caller falls back to `Double.parseDouble`.
/// The powers-of-ten table is a generated literal ([EiselLemireTable]): computing it with BigInteger at
/// class load cost 18.5 ms on the first double parsed.
final class EiselLemire {
    private static final int SMALLEST_POWER = EiselLemireTable.SMALLEST_POWER;
    private static final int LARGEST_POWER = EiselLemireTable.LARGEST_POWER;
    private static final long[] HIGH = EiselLemireTable.HIGH;
    private static final long[] LOW = EiselLemireTable.LOW;

    private EiselLemire() {}

    /// `significand * 10^power` as a double, or NaN when the fast algorithm cannot decide.
    /// `significand` must be the exact decimal significand (no dropped digits), nonzero.
    static double toDouble(long significand, int power, boolean negative) {
        if (power < SMALLEST_POWER || power > LARGEST_POWER) {
            return Double.NaN;
        }
        var index = power - SMALLEST_POWER;
        var exponent = (((152_170L + 65_536L) * power) >> 16) + 1024 + 63;
        var lz = Long.numberOfLeadingZeros(significand);
        var i = significand << lz;
        var upper = Math.unsignedMultiplyHigh(i, HIGH[index]);
        var lower = i * HIGH[index];
        if ((upper & 0x1FF) == 0x1FF && Long.compareUnsigned(lower + i, lower) < 0) {
            var productLow = i * LOW[index];
            var productMiddle2 = Math.unsignedMultiplyHigh(i, LOW[index]);
            var productMiddle = lower + productMiddle2;
            var productHigh = upper;
            if (Long.compareUnsigned(productMiddle, lower) < 0) {
                productHigh++;
            }
            if (productMiddle + 1 == 0 && (productHigh & 0x1FF) == 0x1FF && Long.compareUnsigned(productLow + i, productLow) < 0) {
                return Double.NaN;
            }
            upper = productHigh;
            lower = productMiddle;
        }
        var upperBit = upper >>> 63;
        var mantissa = upper >>> (upperBit + 9);
        lz += (int) (1 ^ upperBit);
        if (lower == 0 && (upper & 0x1FF) == 0 && (mantissa & 3) == 1) {
            return Double.NaN;
        }
        mantissa += mantissa & 1;
        mantissa >>>= 1;
        if (mantissa >= (1L << 53)) {
            mantissa = 1L << 52;
            lz--;
        }
        mantissa &= ~(1L << 52);
        var realExponent = exponent - lz;
        if (realExponent < 1 || realExponent > 2046) {
            return Double.NaN;
        }
        var bits = mantissa | realExponent << 52 | (negative ? 1L << 63 : 0);
        return Double.longBitsToDouble(bits);
    }
}
