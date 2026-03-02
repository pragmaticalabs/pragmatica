/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */

package org.pragmatica.postgres.r2dbc;

import io.r2dbc.spi.R2dbcException;

/// Exception thrown at the R2DBC adapter boundary to convert
/// Pragmatica [org.pragmatica.lang.Cause] failures into R2DBC-compatible exceptions.
///
/// This exception exists solely at the adapter layer to bridge between
/// the promise-based error model and the reactive streams error model.
final class R2dbcAdapterException extends R2dbcException {
    R2dbcAdapterException(String message) {
        super(message);
    }
}
