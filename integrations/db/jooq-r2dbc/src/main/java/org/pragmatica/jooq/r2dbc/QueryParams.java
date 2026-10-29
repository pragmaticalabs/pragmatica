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
package org.pragmatica.jooq.r2dbc;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.r2dbc.R2dbcError;

import org.jooq.Param;
import org.jooq.Query;
import org.jooq.VisitContext;
import org.jooq.VisitListener;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

import static org.pragmatica.lang.Result.success;


/// The parameters of a query in the order of its `?` placeholders, one entry per placeholder: a named parameter used
/// twice appears twice. Each is a copy of the query's own [Param] with its data type, so it keeps its converter and
/// binding when a fresh query is built
/// from the rendered SQL; re-binding the raw values would store an unconverted value where a converter's user type equals
/// its database type.
///
/// Found by rendering the query once with a visit listener, which sees a parameter at every place it is rendered as a
/// placeholder. When that does not give exactly one parameter per placeholder, the query is refused with a typed error:
/// the raw value is never used in its place.
interface QueryParams {
    static Result<List<Param<?>>> of(Query query) {
        var collector = new Collector();
        var placeholders = query.getBindValues().size();

        DSL.using(query.configuration().deriveAppending(collector)).render(query);

        return collector.params()
                        .size() == placeholders
               ? success(collector.params())
               : unmappable(query,
                            collector.params().size(),
                            placeholders).result();
    }

    private static R2dbcError unmappable(Query query, int parameters, int placeholders) {
        return new R2dbcError.QueryFailed("Cannot map the parameters of the query to its placeholders (" + parameters
                                         + " parameters, " + placeholders
                                         + " placeholders): " + query.getSQL());
    }

    final class Collector implements VisitListener {
        private final List<Param<?>> params = new ArrayList<>();
        private int depth;

        /// A binding renders its own parameter inside the one it belongs to: only the outermost is a placeholder.
        @Contract
        @Override
        public void visitStart(VisitContext context) {
            if (context.queryPart() instanceof Param<?> param) {
                if (depth == 0 && isPlaceholder(context, param)) {
                    params.add(fresh(param));
                }

                depth++;
            }
        }

        @Contract
        @Override
        public void visitEnd(VisitContext context) {
            if (context.queryPart() instanceof Param<?>) {
                depth--;
            }
        }

        List<Param<?>> params() {
            return params;
        }

        /// A distinct instance per placeholder with the same value and data type (converter and binding): jOOQ binds a
        /// named parameter by name, so one instance placed at two positions would not bind at the second.
        private static <T> Param<T> fresh(Param<T> param) {
            return DSL.val(param.getValue(), param.getDataType());
        }

        private static boolean isPlaceholder(VisitContext context, Param<?> param) {
            return ! param.isInline()
                   && context.renderContext() != null
                   && context.renderContext()
                             .paramType() != ParamType.INLINED;
        }
    }
}
