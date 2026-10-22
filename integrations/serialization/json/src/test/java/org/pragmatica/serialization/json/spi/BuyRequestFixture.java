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
package org.pragmatica.serialization.json.spi;

import org.pragmatica.serialization.json.JsonTypeCodec;

/// Hand-written stand-in for generated output, used by the runtime's own tests (the generator has its own).
interface BuyRequestFixture {
    record BuyRequest(String customer, String event, String seat, String tier) {}

    interface BuyRequestJson {
        FieldNames NAMES = FieldNames.fieldNames("customer", "event", "seat", "tier");
        int REQUIRED = 0b1111;
        JsonTypeCodec<BuyRequest> CODEC = JsonTypeCodec.jsonTypeCodec(BuyRequestJson::write, BuyRequestJson::read);

        static void write(JsonWriter out, BuyRequest value) {
            out.writeByte('{');
            out.writeRaw(NAMES.first(0));
            out.writeString(value.customer());
            out.writeRaw(NAMES.next(1));
            out.writeString(value.event());
            out.writeRaw(NAMES.next(2));
            out.writeString(value.seat());
            out.writeRaw(NAMES.next(3));
            out.writeString(value.tier());
            out.writeByte('}');
        }

        static BuyRequest read(JsonReader in) {
            if (!in.beginObject()) {
                return null;
            }
            String customer = null;
            String event = null;
            String seat = null;
            String tier = null;
            var seen = 0;
            var expected = 0;
            for (var field = in.firstField(NAMES); field != JsonReader.END_OF_OBJECT; field = in.nextField(NAMES, expected)) {
                switch (field) {
                    case 0 -> customer = in.readString();
                    case 1 -> event = in.readString();
                    case 2 -> seat = in.readString();
                    case 3 -> tier = in.readString();
                    default -> in.skipValue();
                }
                if (field >= 0) {
                    seen |= 1 << field;
                    expected = field + 1;
                }
            }
            if ((seen & REQUIRED) != REQUIRED) {
                in.failMissing(NAMES, seen, REQUIRED);
            }
            return in.hasError()
                   ? null
                   : new BuyRequest(customer, event, seat, tier);
        }
    }
}
