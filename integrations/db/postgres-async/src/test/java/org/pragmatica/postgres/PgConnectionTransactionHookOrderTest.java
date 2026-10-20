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

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.postgres.DatabaseExtension.block;

/// Async-callback probe for the outer transaction's COMMIT / ROLLBACK. The completion of the script is delivered by the
/// I/O thread, LATER than the call, and the hooks the connection fires on that outcome (a subscription kept or dropped
/// with the transaction) are a side effect the caller depends on: it continues right after the `commit()` / `rollback()`
/// promise resolves. The hook here is slow, so a detached callback loses the race to the caller's continuation; the
/// savepoint branches of the same methods already ran their hooks in chain order.
@Tag("Slow")
class PgConnectionTransactionHookOrderTest {
    private static final long HOOK_DELAY_MS = 30;

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.defaultConfiguration();

    @Test
    void commit_firesCommitHooksBeforeContinuation_whenServerAnswersLaterOnAnotherThread() {
        var plain = dbr.builder.plain();
        var connection = (PgConnection) block(plain.getConnection());
        var transaction = block(connection.begin());
        var hookRan = new AtomicBoolean(false);

        connection.registerOnCommit(() -> slowHook(hookRan));
        block(transaction.commit());

        assertTrue(hookRan.get(), "the caller continued before the commit hooks ran");
        block(connection.close());
        block(plain.close());
    }

    @Test
    void rollback_firesRollbackHooksBeforeContinuation_whenServerAnswersLaterOnAnotherThread() {
        var plain = dbr.builder.plain();
        var connection = (PgConnection) block(plain.getConnection());
        var transaction = block(connection.begin());
        var hookRan = new AtomicBoolean(false);

        connection.registerOnRollback(() -> slowHook(hookRan));
        block(transaction.rollback());

        assertTrue(hookRan.get(), "the caller continued before the rollback hooks ran");
        block(connection.close());
        block(plain.close());
    }

    private static void slowHook(AtomicBoolean hookRan) {
        try {
            Thread.sleep(HOOK_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        hookRan.set(true);
    }
}
