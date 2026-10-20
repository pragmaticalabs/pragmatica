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

package org.pragmatica.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Async-callback probe: an operation that fails LATER, on another thread, while the rollback is slow.
/// The rollback is a side effect the caller depends on (the next transaction on the same `EntityManager`
/// must not meet a still-active one), so it has to land before the failure is observable. A fake that
/// returns an already-failed promise hides the race: `onFailure` then runs inline on the caller.
class TransactionalLateFailureTest {
    private static final long RESOLVE_DELAY_MS = 5;
    private static final long ROLLBACK_DELAY_MS = 30;

    @Test
    void withTransaction_rollsBackBeforeFailureIsObservable_whenOperationFailsLaterOnAnotherThread() {
        var rolledBack = new AtomicBoolean(false);
        var em = entityManager(slowRollbackTransaction(rolledBack));
        var operation = Transactional.withTransaction(em, JpaError::fromException, () -> failsLater());

        operation.apply()
                 .await()
                 .onSuccess(_ -> fail("the operation failed, so must the transaction"));

        assertTrue(rolledBack.get(), "the caller observed the failure before the rollback landed");
    }

    @Test
    void withTransaction_doesNotRollBack_whenOperationSucceedsLaterOnAnotherThread() {
        var rolledBack = new AtomicBoolean(false);
        var em = entityManager(slowRollbackTransaction(rolledBack));
        var operation = Transactional.withTransaction(em, JpaError::fromException, () -> succeedsLater());

        operation.apply()
                 .await()
                 .onFailure(cause -> fail("Should not fail: " + cause));

        assertFalse(rolledBack.get());
    }

    private static Promise<String> failsLater() {
        var promise = Promise.<String>promise();

        Thread.ofVirtual()
              .start(() -> resolveAfterDelay(promise, Causes.cause("late failure").result()));

        return promise;
    }

    private static Promise<String> succeedsLater() {
        var promise = Promise.<String>promise();

        Thread.ofVirtual()
              .start(() -> resolveAfterDelay(promise, Result.success("late success")));

        return promise;
    }

    private static void resolveAfterDelay(Promise<String> promise, Result<String> result) {
        sleep(RESOLVE_DELAY_MS);
        promise.resolve(result);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static EntityManager entityManager(EntityTransaction tx) {
        return new EntityManagerStub() {
            @Override
            public EntityTransaction getTransaction() {
                return tx;
            }
        };
    }

    private static EntityTransaction slowRollbackTransaction(AtomicBoolean rolledBack) {
        return new EntityTransactionStub() {
            @Override
            public void begin() {}

            @Override
            public void commit() {}

            @Override
            public void rollback() {
                sleep(ROLLBACK_DELAY_MS);
                rolledBack.set(true);
            }

            @Override
            public boolean isActive() {
                return true;
            }
        };
    }
}
