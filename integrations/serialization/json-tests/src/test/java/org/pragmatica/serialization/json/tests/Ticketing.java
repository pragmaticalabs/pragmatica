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
package org.pragmatica.serialization.json.tests;

import org.pragmatica.serialization.json.Json;

/// Shapes copied from ticketing `booking/purchase/buyticket/BuyTicket.java:66-68`.
public interface Ticketing {
    @Json
    record BuyRequest(String customer, String event, String seat, String tier) {}

    @Json
    record BuyResponse(String booking, String ticket, String seat, String receipt, long amountMinor, String currency) {}
}
