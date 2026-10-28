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

import java.lang.reflect.Proxy;

import org.jooq.Query;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JooqR2dbcQueriesTest {
    /// #2431: only a row-count query (INSERT, UPDATE, DELETE, plain SQL, DDL) is a `Publisher<Integer>`; a `Query` that
    /// is not one fails with a clear cause instead of reaching jOOQ's blocking path.
    @Test
    void execute_queryThatIsNotARowCountQuery_failsWithAClearCause() {
        Query notRowCount = (Query) Proxy.newProxyInstance(Query.class.getClassLoader(),
                                                           new Class<?>[]{Query.class},
                                                           (_, method, _) -> "getSQL".equals(method.getName()) ? "DO SOMETHING" : null);

        var cause = JooqR2dbcQueries.execute(null, SQLDialect.DEFAULT, notRowCount)
                                    .await()
                                    .fold(c -> c, value -> org.junit.jupiter.api.Assertions.fail("expected a failure but got " + value));

        assertThat(cause.message()).contains("Not a row-count query").contains("DO SOMETHING");
    }
}
