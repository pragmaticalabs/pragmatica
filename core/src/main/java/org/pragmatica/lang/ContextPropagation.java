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
 *
 */

package org.pragmatica.lang;

import java.util.ServiceLoader;

/**
 * SPI for context propagation across async boundaries.
 *
 * <p>Implementations are loaded via ServiceLoader to enable modules like aether
 * to provide context propagation without core depending on them.
 *
 * <p>To provide a custom implementation:
 * <ol>
 *   <li>Implement this interface</li>
 *   <li>Create a file at META-INF/services/org.pragmatica.lang.ContextPropagation</li>
 *   <li>Add the fully qualified class name of your implementation to that file</li>
 * </ol>
 *
 * <p>Example implementation for request ID propagation:
 * <pre>{@code
 * public class MyContextPropagation implements ContextPropagation {
 *     public Object capture() {
 *         return MyContext.captureSnapshot();
 *     }
 *
 *     public void runWith(Object snapshot, Runnable action) {
 *         if (snapshot instanceof MyContext.Snapshot s) {
 *             s.runWithCaptured(action);
 *         } else {
 *             action.run();
 *         }
 *     }
 * }
 * }</pre>
 */
public interface ContextPropagation {
    /**
     * Singleton instance loaded via ServiceLoader.
     * Falls back to NoOp if no implementation is found.
     */
    ContextPropagation INSTANCE = ServiceLoader.load(ContextPropagation.class)
                                              .findFirst()
                                              .orElseGet(NoOp::new);

    /**
     * Capture the current context for propagation.
     *
     * @return an opaque snapshot object representing the current context
     */
    Object capture();

    /**
     * Run an action with the captured context restored.
     *
     * @param snapshot the captured context (from capture())
     * @param action   the action to run with restored context
     * @return Unit for composition
     */
    Unit runWith(Object snapshot, Runnable action);

    /**
     * Sentinel object representing empty context (no-op).
     */
    Object EMPTY_CONTEXT = new Object();

    /**
     * No-op implementation when no context propagation is configured.
     * Simply runs actions without any context manipulation.
     */
    class NoOp implements ContextPropagation {
        @Override
        public Object capture() {
            return EMPTY_CONTEXT;
        }

        @Override
        public Unit runWith(Object snapshot, Runnable action) {
            action.run();
            return Unit.unit();
        }
    }
}
