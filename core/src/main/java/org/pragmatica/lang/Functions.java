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
package org.pragmatica.lang;

/// Collection of basic functions for various use cases.
@SuppressWarnings("unused")
public interface Functions {
    /// Function with no parameters (supplier). Provided for consistency.
    @FunctionalInterface
    interface Fn0<R> {
        R apply();
    }

    /// Function with one parameter.
    @FunctionalInterface
    interface Fn1<R, T1> {
        R apply(T1 param1);

        /// Compose this function with another function. The result function applies this function first, then the provided function.
        ///
        /// @param function Function to apply after this function
        ///
        /// @return composed function
        default <N> Fn1<N, T1> then(Fn1<N, R> function) {
            return v1 -> function.apply(apply(v1));
        }

        /// Compose this function with another function. The result function applies the provided function first, then this function.
        ///
        /// @param function Function to apply before this function
        ///
        /// @return composed function
        default <N> Fn1<R, N> before(Fn1<T1, N> function) {
            return v1 -> apply(function.apply(v1));
        }

        /// Create an identity function that returns its input unchanged.
        ///
        /// @return identity function
        static <T> Fn1<T, T> id() {
            return Functions::id;
        }
    }

    /// Function with two parameters.
    @FunctionalInterface
    interface Fn2<R, T1, T2> {
        R apply(T1 param1, T2 param2);
    }

    /// Function with three parameters.
    @FunctionalInterface
    interface Fn3<R, T1, T2, T3> {
        R apply(T1 param1, T2 param2, T3 param3);
    }

    /// Function with four parameters.
    @FunctionalInterface
    interface Fn4<R, T1, T2, T3, T4> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4);
    }

    /// Function with five parameters.
    @FunctionalInterface
    interface Fn5<R, T1, T2, T3, T4, T5> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5);
    }

    /// Function with six parameters.
    @FunctionalInterface
    interface Fn6<R, T1, T2, T3, T4, T5, T6> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6);
    }

    /// Function with seven parameters.
    @FunctionalInterface
    interface Fn7<R, T1, T2, T3, T4, T5, T6, T7> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6, T7 param7);
    }

    /// Function with eight parameters.
    @FunctionalInterface
    interface Fn8<R, T1, T2, T3, T4, T5, T6, T7, T8> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6, T7 param7, T8 param8);
    }

    /// Function with nine parameters.
    @FunctionalInterface
    interface Fn9<R, T1, T2, T3, T4, T5, T6, T7, T8, T9> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6, T7 param7, T8 param8, T9 param9);
    }

    /// Function with ten parameters.
    @FunctionalInterface
    interface Fn10<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10);
    }

    /// Function with eleven parameters.
    @FunctionalInterface
    interface Fn11<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11);
    }

    /// Function with twelve parameters.
    @FunctionalInterface
    interface Fn12<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11,
                T12 param12);
    }

    /// Function with thirteen parameters.
    @FunctionalInterface
    interface Fn13<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11,
                T12 param12,
                T13 param13);
    }

    /// Function with fourteen parameters.
    @FunctionalInterface
    interface Fn14<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11,
                T12 param12,
                T13 param13,
                T14 param14);
    }

    /// Function with fifteen parameters.
    @FunctionalInterface
    interface Fn15<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11,
                T12 param12,
                T13 param13,
                T14 param14,
                T15 param15);
    }

    /// Universal identity function.
    static <T> T id(T value) {
        return value;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /// Supplier which can throw an exception.
    @FunctionalInterface
    @Contract
    interface ThrowingFn0<T> {
        T apply() throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn1<R, T1> {
        R apply(T1 v1) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn2<R, T1, T2> {
        R apply(T1 v1, T2 v2) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn3<R, T1, T2, T3> {
        R apply(T1 param1, T2 param2, T3 param3) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn4<R, T1, T2, T3, T4> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn5<R, T1, T2, T3, T4, T5> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn6<R, T1, T2, T3, T4, T5, T6> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn7<R, T1, T2, T3, T4, T5, T6, T7> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6, T7 param7) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn8<R, T1, T2, T3, T4, T5, T6, T7, T8> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6, T7 param7, T8 param8) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn9<R, T1, T2, T3, T4, T5, T6, T7, T8, T9> {
        R apply(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6, T7 param7, T8 param8, T9 param9) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn10<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn11<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn12<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11,
                T12 param12) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn13<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11,
                T12 param12,
                T13 param13) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn14<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11,
                T12 param12,
                T13 param13,
                T14 param14) throws Throwable;
    }

    @FunctionalInterface
    @Contract
    interface ThrowingFn15<R, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15> {
        R apply(T1 param1,
                T2 param2,
                T3 param3,
                T4 param4,
                T5 param5,
                T6 param6,
                T7 param7,
                T8 param8,
                T9 param9,
                T10 param10,
                T11 param11,
                T12 param12,
                T13 param13,
                T14 param14,
                T15 param15) throws Throwable;
    }

    /// Function with the variable argument list.
    @FunctionalInterface
    interface FnX<R> {
        R apply(Object... values);
    }

    /// Universal consumers of values which do nothing with input values. Useful for cases when the API
    /// requires a function, but there is no need to do anything with the received values.
    @Contract
    static <T1> void unitFn() {}

    /// No-operation function that accepts one parameter and returns nothing. Used when API requires a function, but there is no need to do anything with the received value.
    ///
    /// @param value Input value (ignored)
    @Contract
    static <T1> void unitFn(T1 value) {}

    @Contract
    static <T1, T2> void unitFn(T1 param1, T2 param2) {}

    @Contract
    static <T1, T2, T3> void unitFn(T1 param1, T2 param2, T3 param3) {}

    @Contract
    static <T1, T2, T3, T4> void unitFn(T1 param1, T2 param2, T3 param3, T4 param4) {}

    @Contract
    static <T1, T2, T3, T4, T5> void unitFn(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6> void unitFn(T1 param1, T2 param2, T3 param3, T4 param4, T5 param5, T6 param6) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7> void unitFn(T1 param1,
                                                    T2 param2,
                                                    T3 param3,
                                                    T4 param4,
                                                    T5 param5,
                                                    T6 param6,
                                                    T7 param7) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7, T8> void unitFn(T1 param1,
                                                        T2 param2,
                                                        T3 param3,
                                                        T4 param4,
                                                        T5 param5,
                                                        T6 param6,
                                                        T7 param7,
                                                        T8 param8) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7, T8, T9> void unitFn(T1 param1,
                                                            T2 param2,
                                                            T3 param3,
                                                            T4 param4,
                                                            T5 param5,
                                                            T6 param6,
                                                            T7 param7,
                                                            T8 param8,
                                                            T9 param9) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10> void unitFn(T1 param1,
                                                                 T2 param2,
                                                                 T3 param3,
                                                                 T4 param4,
                                                                 T5 param5,
                                                                 T6 param6,
                                                                 T7 param7,
                                                                 T8 param8,
                                                                 T9 param9,
                                                                 T10 param10) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11> void unitFn(T1 param1,
                                                                      T2 param2,
                                                                      T3 param3,
                                                                      T4 param4,
                                                                      T5 param5,
                                                                      T6 param6,
                                                                      T7 param7,
                                                                      T8 param8,
                                                                      T9 param9,
                                                                      T10 param10,
                                                                      T11 param11) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12> void unitFn(T1 param1,
                                                                           T2 param2,
                                                                           T3 param3,
                                                                           T4 param4,
                                                                           T5 param5,
                                                                           T6 param6,
                                                                           T7 param7,
                                                                           T8 param8,
                                                                           T9 param9,
                                                                           T10 param10,
                                                                           T11 param11,
                                                                           T12 param12) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13> void unitFn(T1 param1,
                                                                                T2 param2,
                                                                                T3 param3,
                                                                                T4 param4,
                                                                                T5 param5,
                                                                                T6 param6,
                                                                                T7 param7,
                                                                                T8 param8,
                                                                                T9 param9,
                                                                                T10 param10,
                                                                                T11 param11,
                                                                                T12 param12,
                                                                                T13 param13) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14> void unitFn(T1 param1,
                                                                                     T2 param2,
                                                                                     T3 param3,
                                                                                     T4 param4,
                                                                                     T5 param5,
                                                                                     T6 param6,
                                                                                     T7 param7,
                                                                                     T8 param8,
                                                                                     T9 param9,
                                                                                     T10 param10,
                                                                                     T11 param11,
                                                                                     T12 param12,
                                                                                     T13 param13,
                                                                                     T14 param14) {}

    @Contract
    static <T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15> void unitFn(T1 param1,
                                                                                          T2 param2,
                                                                                          T3 param3,
                                                                                          T4 param4,
                                                                                          T5 param5,
                                                                                          T6 param6,
                                                                                          T7 param7,
                                                                                          T8 param8,
                                                                                          T9 param9,
                                                                                          T10 param10,
                                                                                          T11 param11,
                                                                                          T12 param12,
                                                                                          T13 param13,
                                                                                          T14 param14,
                                                                                          T15 param15) {}

    /// Utility function that ignores input and always returns null. Used in side-effect fold branches.
    ///
    /// @param value Input value (ignored)
    ///
    /// @return null
    @NullReturn
    static <R, T1> R toNull(T1 value) {
        return null;
    }

    /// Utility function that ignores input and always returns true. Used in functional contexts where a true value is needed.
    ///
    /// @param value Input value (ignored)
    ///
    /// @return true
    @SuppressWarnings("SameReturnValue")
    static <T1> boolean toTrue(T1 value) {
        return true;
    }

    /// Utility function that ignores input and always returns false. Used in functional contexts where a false value is needed.
    ///
    /// @param value Input value (ignored)
    ///
    /// @return false
    @SuppressWarnings("SameReturnValue")
    static <T1> boolean toFalse(T1 value) {
        return false;
    }
}
