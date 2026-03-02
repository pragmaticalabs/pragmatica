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

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;

import org.pragmatica.postgres.PgResultSet;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/// R2DBC [Result] backed by a postgres-async [PgResultSet].
///
/// Provides access to rows, row counts, and supports the segment-based
/// mapping API introduced in R2DBC SPI 1.0.
public final class PgAsyncResult implements Result {
    private final PgResultSet resultSet;

    PgAsyncResult(PgResultSet resultSet) {
        this.resultSet = resultSet;
    }

    @Override
    public Publisher<Long> getRowsUpdated() {
        return Mono.just((long) resultSet.affectedRows());
    }

    @Override
    public <T> Publisher<T> map(BiFunction<Row, RowMetadata, ? extends T> mappingFunction) {
        var metadata = PgAsyncRowMetadata.pgAsyncRowMetadata(resultSet.orderedColumns());
        return Flux.fromIterable(() -> resultSet.iterator())
                   .map(pgRow -> mappingFunction.apply(new PgAsyncRow(pgRow, metadata), metadata));
    }

    @Override
    public Result filter(Predicate<Segment> filter) {
        return this;
    }

    @Override
    public <T> Publisher<T> flatMap(Function<Segment, ? extends Publisher<? extends T>> mappingFunction) {
        var metadata = PgAsyncRowMetadata.pgAsyncRowMetadata(resultSet.orderedColumns());
        return Flux.fromIterable(() -> resultSet.iterator())
                   .concatMap(pgRow -> mappingFunction.apply(new RowSegment(new PgAsyncRow(pgRow, metadata), metadata)));
    }

    private record RowSegment(Row row, RowMetadata metadata) implements Result.RowSegment {
        @Override
        public Row row() {
            return row;
        }
    }
}
