// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import java.util.Map;

import org.pragmatica.lang.Promise;


/// One health component (#3651), as Spring Boot's `HealthIndicator`: a failed check is reported as `DOWN` with an
/// `error` detail, never as a failed health request.
@FunctionalInterface
public interface HealthIndicator {
    Promise<Health> health();

    /// Spring Boot's statuses, in its default aggregation order: the first status present among the components wins,
    /// no component at all reads `UNKNOWN`. `DOWN` and `OUT_OF_SERVICE` answer 503, the others 200.
    enum Status {
        DOWN(503),
        OUT_OF_SERVICE(503),
        UP(200),
        UNKNOWN(200);

        private final int httpStatus;

        Status(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    record Health(Status status, Map<String, Object> details) {
        public static Health health(Status status, Map<String, Object> details) {
            return new Health(status, Map.copyOf(details));
        }

        public static Health up() {
            return health(Status.UP, Map.of());
        }
    }
}
