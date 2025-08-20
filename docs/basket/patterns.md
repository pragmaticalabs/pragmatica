User:
Read sources and documentation. Provide summaryAttachments:
Attachment Name: README.md
Attachments Kind: Visible
Attachments Text:
````markdown
truncated contents:

# Pragmatic Functional Java (PFJ) Coding Style Library: Pragmatica Lite Core

The **Pragmatica Lite Core** library provides three main monads (`Option<T>`, `Result<T>` and `Promise<T>`) and a few utility classes to make common use cases more convenient.

## Motivation

Main considerations for the appearance of the PFJ as an approach are described in this [article](https://medium.com/codex/we-should-write-java-code-differently-c32212152af1). The PFJ style in more detail is described [here](https://medium.com/codex/introduction-to-pragmatic-functional-java-md-fba6bdaae6a8).

## Features

The library provides three main monads:
    - `Option<T>` - a monad that represents an optional (i.e. potentially missing) value. _(synchronous)_
    - `Result<T>` - a monad that represents a computation that may fail. _(synchronous)_
    - `Promise<T>` - a monad that represents a computation that may fail. _(asynchronous)_

All monads are designed to be compatible with each other and provide a consistent API. Methods for transformation of one monad to another are also provided.

### Option&lt;T>

The `Option<T>` monad represents an optional value. It should be used for all public APIs to represent a potentially missing value. This applies to all cases when such values may appear - method return value, method parameters and class/record fields.
The `Option<T>` is designed to support pattern matching. There are two implementations of `Option<T>`: `Some<T>` and `None<T>`. For memory optimization, the `None<T>` implementation is a singleton.

Basic usage looks like this:

```java
var name = Option.option(methodThatMayReturnNull())
                 .map(String::toUpperCase);
```
Now `name` contains potentially missing value. If value is present, then it is converted to upper case. At some point in the code usually it is necessary to make a decision, what to do with value stored in the `Option<T>`. There are several ways to do this:

#### Using `.or` method(s)

There are two `.or` methods which can be used to provide a default value in case when value is missing.

First one usually is convenient when default value is a constant or it's computation is not expensive:
```java
var name = Option.option(methodThatMayReturnNull())
                 .map(String::toUpperCase)
                 .or("default name");
```
The second one is more flexible and can be used when default value is expensive to compute:
```java
var name = Option.option(methodThatMayReturnNull())
                 .map(String::toUpperCase)
                 .or(() -> expensiveComputation());
```

#### Using pattern matching

As mentioned above, the `Option<T>` monad can be either `Some<T>` or `None<T>`:

```java
var name = Option.option(methodThatMayReturnNull());

var uppercaseName = switch(name) {
    case Some<String> some -> some.value().toUpperCase();
    case None<String> _ -> "default name";
};
```

### Result&lt;T>

This monad is a main tool for handling errors in the code. It should be used for all methods that may fail. The `Result<T>` contains either, the the value obtained from the computation, or an error. The error must be an instance implementing `Cause` interface. This interface in some sense serves purpose similar to `Throwable` class and is a root for all possible errors in the application. For convenience, there is `Causes` utility interface, which provides convenience methods for creating `Cause` instances in simple cases. For more complex cases, it is recommended to create custom implementations of `Cause` interface (see [corresponding section](#application-specific-errors)). 

Basic usage looks like this:

```java
Result<String> method(String value) {
    if (value.isEmpty()) {
        return Result.failure(AppErrors.VALUE_IS_EMPTY);
    }
    return Result.success(value);
}
```
The `Result<T>` monad is designed to support pattern matching. There are two implementations of `Result<T>`: `Success<T>` and `Failure<T>`.

#### Transformation Pipeline
One of the most frequent 
````
Attachment Name: Result.java
Attachments Kind: Visible
Attachments Text:
```java
summarized and truncated contents:

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

import org.pragmatica.lang.Functions.*;
import org.pragmatica.lang.Result.Failure;
import org.pragmatica.lang.Result.Success;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Tuple.*;
import static org.pragmatica.lang.Unit.unit;


/**
 * Representation of the operation result. The result can be either success or failure. In case of success it holds value returned by the operation.
 * In case of failure it holds a failure description.
 *
 * @param <T> Type of value in case of success.
 */
@SuppressWarnings("unused")
public sealed interface Result<T> permits Success, Failure {
    /**
     * Transform operation result value into value of other type and wrap new value into {@link Result}. Transformation takes place if current
     * instance (this) contains successful result, otherwise current instance remains unchanged and transformation function is not invoked.
     *
     * @param mapper Function to transform successful value
     *
     * @return transformed value (in case of success) or current instance (in case of failure)
     */
    @SuppressWarnings("unchecked")
    default <U> Result<U> map(Fn1<U, ? super T> mapper) {/* implementation omitted for shortness */}

    /**
     * Replace successful result with the value obtained from provided supplier.
     *
     * @param supplier Source of the replacement value
     *
     * @return replaced value (in case of success) or current instance (in case of failure)
     */
    default <U> Result<U> map(Supplier<U> supplier) {/* implementation omitted for shortness */}

    /**
     * Transform operation result into another operation result. In case if current instance (this) is an error, transformation function is not
     * invoked and value remains the same.
     *
     * @param mapper Function to apply to result
     *
     * @return transformed value (in case of success) or current instance (in case of failure)
     */
    @SuppressWarnings("unchecked")
    default <U> Result<U> flatMap(Fn1<Result<U>, ? super T> mapper) {/* implementation omitted for shortness */}

    /**
     * Replace current instance with the instance returned by provided {@link Supplier}. The replacement happens only if current instance contains
     * successful result, otherwise current instance remains unchanged.
     *
     * @param mapper Source of the replacement result.
     *
     * @return replacement result (in case of success) or current instance (in case of failure)
     */
    @SuppressWarnings("unchecked")
    default <U> Result<U> flatMap(Supplier<Result<U>> mapper) {/* implementation omitted for shortness */}

    /**
     * Transform cause of the failure.
     *
     * @param mapper Function to transform failure cause
     *
     * @return current instance (in case of success) or transformed instance (in case of failure)
     */
    default Result<T> mapError(Fn1<Cause, ? super Cause> mapper) {/* implementation omitted for shortness */}

    /**
     * Add tracing information to the failure cause.
     *
     * @return current instance (in case of succ
```
Attachment Name: Promise.java
Attachments Kind: Visible
Attachments Text:
```java
summarized and truncated contents:

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

import org.pragmatica.lang.Functions.*;
import org.pragmatica.lang.Tuple.*;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.ResultCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.utils.ActionableThreshold.threshold;
import static org.pragmatica.lang.utils.ResultCollector.resultCollector;

/**
 * This is a simple implementation of Promise monad. Promise is a one of the three {@code Core Monads} (along with {@link Option} and {@link Result})
 * which are used to represent special variable states. Promise is a representation of the {@code eventually available value}.
 * <p>
 * This implementation serves three purposes:
 * <ul>
 * <li>- Asynchronous version of {@link Result} monad - {@code Promise} can be resolved to successful and failed states</li>
 * <li>- A basic building block of asynchronous processing.</li>
 * <li>- Resolution event "broker", which implements "exactly once" delivery semantics.</li>
 * </ul>
 * Last two purposes are closely related - they both react on resolution event. But the semantics and requirements to the
 * event handling behavior is quite different.
 * <p>Promise-based asynchronous processing mental model built around representing processing
 * as sequence of transformations which are applied to the same value. Each transformation is applied to input values exactly once, executed
 * with exactly one thread, in the order in which transformations are applied in the code. This mental model is easy to write and reason about
 * and has very low "asynchronous" mental overhead. Since each promise in the chain "depends" on the previous one, actions (transformations)
 * attached to the promise are called "dependent actions".
 * <p>Resolution event broker has no such limitations. It starts actions as asynchronous tasks but does not wait for their completions. Obviously,
 * such actions are called "independent actions". They are harder to reason about because their execution may take arbitrary
 * amount of time.
 */
/* Implementation notes: this version of the implementation is heavily inspired by the implementation of
the CompletableFuture. There are several differences though:
- Method naming consistent with widely used Optional and Streams.
- More orthogonal API. No duplicates with "Async" suffix, for example. Instead, provided "async" method, which accepts promise consumer lambda.
- No methods which do a complex combination of futures. Instead, provided type-safe "all" and "any" predicates for up to 9 promises.
This makes synchronization points in code more explicit, easier to write and reason about.
- No exceptions, no nulls. This makes implementation exceptionally simple comparing to the CompletableFuture.
- Interface-heavy implementation, with only minimal number of methods to implement.
  Only
```
Attachment Name: Option.java
Attachments Kind: Visible
Attachments Text:
```java
summarized and truncated contents:

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

import org.pragmatica.lang.Functions.*;
import org.pragmatica.lang.Option.None;
import org.pragmatica.lang.Option.Some;
import org.pragmatica.lang.io.CoreError;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.lang.Tuple.tuple;

/**
 * Implementation of basic immutable container for value which may or may not be present.
 *
 * @param <T> Type of contained value
 */
@SuppressWarnings("unused")
public sealed interface Option<T> permits Some, None {
    /**
     * Convert instance into other instance of different type using provided mapping function.
     *
     * @param mapper Mapping function
     * @param <U>    Type of new value
     *
     * @return transformed instance
     */
    default <U> Option<U> map(Fn1<U, ? super T> mapper) {/* implementation omitted for shortness */}

    /**
     * Replace current instance with the value returned by provided supplier.
     *
     * @param supplier Source of replacement value.
     *
     * @return current instance if it is empty or the instance with the replacement value if current instance is present.
     */
    default <U> Option<U> map(Supplier<U> supplier) {/* implementation omitted for shortness */}

    /**
     * Replace current present instance with another one generated by applying provided mapper to value stored in this instance. Empty instance is
     * replaced with empty instance of new type matching type of provided mapping function.
     *
     * @param mapper Mapping function
     * @param <U>    New type
     *
     * @return Instance of new type
     */
    default <U> Option<U> flatMap(Fn1<Option<U>, ? super T> mapper) {/* implementation omitted for shortness */}

    /**
     * Replace current instance with the instance returned by provided supplier.
     *
     * @param supplier Source of replacement value.
     *
     * @return current instance if it is empty or the instance with the replacement value if current instance is present.
     */
    default <U> Option<U> flatMap(Supplier<Option<U>> supplier) {/* implementation omitted for shortness */}

    /**
     * Transform instance according to results of testing of contained value with provided predicate. If instance is empty, it remains empty. If
     * instance contains value, this value is passed to predicate. If predicate returns <code>true</code> then instance remains untouched. If
     * predicate returns <code>false</code> then empty instance is returned instead.
     *
     * @param predicate Predicate to test instance value.
     *
     * @return current instance if it is not empty and predicate returns <code>true</code> and empty instance otherwise
     */
    default Option<T> filter(Predicate<? super T> predicate) {/* implementation omitted for shortness */}

    /**
     * Pass internal value to provided consumer in-line. Consumer is invoked only if current instance is not empty. This is a convenience method which
     * can be inserted at any point of fluent call chain. Note that provided consumer should not change value in any way (for example, if contained
     * value is mutable collection/map/array/etc.) and should not throw any kind of exceptions.
   
```


Assistant:
I'll analyze the provided sources and documentation to give you a summary of the Pragmatica Lite Core library.

# Pragmatica Lite Core Library Summary

The Pragmatica Lite Core library implements the Pragmatic Functional Java (PFJ) coding style, which aims to bring functional programming patterns to Java while maintaining a practical approach.

## Core Components

The library provides three main monads:

1. **Option\<T\>**: Represents an optional (potentially missing) value
    - Implementations: `Some<T>` and `None<T>` (singleton)
    - Alternative to Java's `Optional` or `null` values
    - Supports pattern matching and transformation operations

2. **Result\<T\>**: Represents a computation that may succeed or fail
    - Implementations: `Success<T>` and `Failure<T>`
    - Uses `Cause` interface for error representation (analogous to `Throwable`)
    - Supports error transformation and tracing

3. **Promise\<T\>**: Represents an asynchronous computation that will eventually produce a value
    - Asynchronous version of `Result`
    - Functions as both a transformation pipeline and an event broker
    - Offers more consistent API than Java's `CompletableFuture`

## Key Features

1. **Consistent API**: All three monads provide similar methods for transformation (map, flatMap) and composition.

2. **No Exceptions/Nulls**: The library avoids exceptions and null values, instead using the monadic types to represent failure and absence.

3. **Pattern Matching Support**: All monads are designed to work well with Java's pattern matching.

4. **Type Safety**: The library provides type-safe ways to combine and transform values.

5. **Transformation Pipelines**: Allows for creating clean, readable processing pipelines.

## Usage Patterns

### Option\<T\>

```java
// Creating and transforming
var name = Option.option(methodThatMayReturnNull())
               .map(String::toUpperCase);

// Providing defaults
var nameWithDefault = name.or("default name");
// or with a supplier
var nameWithComputedDefault = name.or(() -> expensiveComputation());

// Pattern matching
var uppercaseName = switch(name) {
    case Some<String> some -> some.value().toUpperCase();
    case None<String> _ -> "default name";
};
```

### Result\<T\>

```java
Result<String> method(String value) {
    if (value.isEmpty()) {
        return Result.failure(AppErrors.VALUE_IS_EMPTY);
    }
    return Result.success(value);
}

// Transformation pipeline
result.map(String::toUpperCase)
      .mapError(cause -> AppErrors.wrap(cause))
      .flatMap(value -> anotherOperation(value));
```

### Promise\<T\>

Functions as an asynchronous version of `Result` with features for:
- Sequential transformation of values
- Asynchronous processing
- Event stateMachineNotification with "exactly once" semantics

## Design Philosophy

The library follows these principles:
- Interface-based design with minimal implementation methods
- No exceptions or nulls for error handling
- Consistent naming conventions across monads
- Explicit synchronization points
- Simplified mental model for asynchronous processing

The Pragmatica Lite Core library provides a clean, functional approach to handling common programming patterns in Java while maintaining type safety and avoiding traditional Java error handling mechanisms like exceptions.

User:
You are experienced Java developer with some knowledge of functional programming, in particular, Pragmatic Functional Java coding style.

Assistant:
I appreciate the context! As an experienced Java developer familiar with functional programming and the Pragmatic Functional Java (PFJ) coding style, I can provide a more nuanced perspective on the Pragmatica Lite Core library.

# Pragmatica Lite Core Library: A Developer's Perspective

## Core Value Proposition

The Pragmatica Lite Core library addresses several pain points in traditional Java development:

1. **Null Reference Problems**: Traditional Java code often uses `null` to represent missing values, leading to infamous `NullPointerExceptions`. `Option<T>` provides a type-safe alternative.

2. **Exception-Based Error Handling**: Java's checked/unchecked exceptions create verbose try-catch blocks and can be easily ignored. `Result<T>` provides explicit error handling without exceptions.

3. **Callback Hell**: Asynchronous code with callbacks can become nested and difficult to reason about. `Promise<T>` provides a cleaner approach to composing asynchronous operations.

## Developer Benefits

### Expressive Code with Less Boilerplate

```java
// Traditional Java
String result;
try {
    String value = repository.findValueById(id);
    if (value != null) {
        result = transformer.process(value);
    } else {
        result = "default";
    }
} catch (RepositoryException e) {
    logger.error("Failed to retrieve data", e);
    result = "error";
}

// With PFJ style
repository.findValueById(id)
    .map(transformer::process)
    .or("default")
    .onFailure(e -> logger.error("Failed to retrieve data", e))
    .recover(e -> "error");
```

### Error Transparency

The `Result<T>` type forces explicit error handling - errors can't be silently ignored as they might with exceptions. This leads to more robust code with clearer error paths.

### Composition Over Imperative Flow

PFJ style encourages composition of small, focused functions instead of complex imperative logic:

```java
// Building a processing pipeline
Result<Order> processOrder(String orderId) {
    return findOrder(orderId)
        .flatMap(this::validateOrder)
        .flatMap(this::applyDiscounts)
        .flatMap(this::calculateTotals)
        .flatMap(this::saveOrder);
}
```

### Practical Functional Programming

Unlike pure functional programming that can feel alien to Java developers, PFJ strikes a balance:

- Uses familiar Java syntax and types
- Doesn't impose immutability everywhere
- Works with existing Java libraries
- Provides functional patterns where they add value

## Implementation Insights

1. **Sealed Interfaces**: The library leverages Java's sealed interfaces for the monadic types, enabling exhaustive pattern matching.

2. **Memory Optimization**: For example, `None<T>` is implemented as a singleton to reduce memory overhead.

3. **Lightweight Design**: The library focuses on essential functionality without bloat, making it easy to adopt incrementally.

4. **Type Safety**: Generic typing throughout the API catches more errors at compile time rather than runtime.

## Adoption Strategy

When introducing PFJ style to an existing Java codebase:

1. Start with boundary interfaces (e.g., repositories, services) returning `Option<T>` and `Result<T>`
2. Gradually refactor internal code to use these types
3. Introduce `Promise<T>` for asynchronous operations
4. Establish team conventions for error handling and monad usage

The library's approach should feel natural to experienced Java developers while bringing in the benefits of functional patterns without requiring a complete paradigm shift in thinking.

User:
You've correctly identified one of the core patterns - I call it Sequencer: sequence of operations "steps" which must be executed in order, one-by-one.

Assistant:
You're absolutely right about identifying the Sequencer pattern! This is indeed a fundamental pattern in the Pragmatic Functional Java (PFJ) style, and it's one of the most powerful aspects of the monadic approach. Let me expand on this pattern specifically:

# The Sequencer Pattern in PFJ

## Core Concept

The Sequencer pattern leverages monads to create a sequential processing pipeline where:

1. Each step depends on the successful completion of the previous step
2. Error handling is built into the pipeline
3. The code reads linearly despite handling complex conditional logic

## Implementation in Pragmatica Lite Core

All three monads (`Option<T>`, `Result<T>`, and `Promise<T>`) implement this pattern through their transformation methods:

```java
// Using Result<T> as a sequencer
Result<FinalOutput> process(Input input) {
    return Result.success(input)
        .flatMap(this::validateInput)      // Step 1
        .flatMap(this::enrichWithExternalData)  // Step 2
        .flatMap(this::transform)         // Step 3
        .map(this::formatOutput);         // Step 4
}
```

The key characteristics here:

- **Short-circuiting**: If any step returns a failure, subsequent steps are skipped
- **Error propagation**: Errors flow through the chain without explicit handling at each step
- **Linear readability**: The sequence of operations is immediately clear

## Why This Pattern is Powerful

1. **Mental Model Simplification**: Transforms complex branching logic into a linear sequence

   ```java
   // Without sequencer - complex nesting
   if (validateInput(input) != null) {
       var enriched = enrichWithExternalData(input);
       if (enriched != null) {
           var transformed = transform(enriched);
           if (transformed != null) {
               return formatOutput(transformed);
           }
       }
   }
   return null;
   
   // With sequencer - linear flow
   return Option.option(input)
       .flatMap(this::validateInput)
       .flatMap(this::enrichWithExternalData)
       .flatMap(this::transform)
       .map(this::formatOutput);
   ```

2. **Separation of Concerns**: Each step in the sequence can focus on a single responsibility

3. **Composability**: Sequences can be composed of smaller sequences

   ```java
   // Breaking down a complex sequence into modular parts
   Result<EnrichedData> enrichData(RawData data) {
       return Result.success(data)
           .flatMap(this::fetchAdditionalInfo)
           .flatMap(this::mergeData);
   }
   
   Result<Output> processRequest(Request request) {
       return Result.success(request)
           .flatMap(this::validateRequest)
           .flatMap(this::extractRawData)
           .flatMap(this::enrichData)  // Reusing a smaller sequence
           .flatMap(this::finalizeOutput);
   }
   ```

4. **Testability**: Each step can be tested in isolation, and sequences can be tested as a whole

## Advanced Sequencer Patterns

### 1. Error Recovery Within Sequences

```java
Result<Data> fetchWithRetry(String id) {
    return primaryDataSource.fetch(id)
        .recover(error -> backupDataSource.fetch(id))
        .mapError(error -> Causes.wrap(error, "All data sources failed"));
}
```

### 2. Conditional Branching Within Sequences

```java
Result<Document> processDocument(Document doc) {
    return Result.success(doc)
        .flatMap(document -> document.needsPreprocessing() 
            ? preprocess(document) 
            : Result.success(document))
        .flatMap(this::analyze);
}
```

### 3. Collecting and Processing in Batch

```java
Result<List<ProcessedItem>> processItems(List<RawItem> items) {
    return items.stream()
        .map(this::processItem)  // Returns Result<ProcessedItem>
        .collect(ResultCollector.toList());
}
```

### 4. Asynchronous Sequencing with Promise

```java
Promise<FinalResult> processConcurrently(Input input) {
    return Promise.promise(input)
        .flatMap(this::validateAsync)
        .flatMap(validated -> {
            // Fan out to multiple concurrent operations
            var p1 = fetchDataA(validated);
            var p2 = fetchDataB(validated);
            
            // Join results when both complete
            return Promise.all(p1, p2)
                .flatMap(tuple -> combine(tuple._1(), tuple._2()));
        });
}
```

The Sequencer pattern is particularly valuable for implementing business processes, data transformation pipelines, and validation workflows where each step builds upon the previous one. It's one of the most concrete examples of how functional thinking can lead to cleaner, more maintainable code in everyday Java development.

User:
please, fix the example with Promise.all: tuple access is incorrect. Check type which is returned by Promise.all.

Assistant:
You're absolutely right, my example contained an error in how I accessed the tuple returned by `Promise.all`. Let me fix that:

```java
Promise<FinalResult> processConcurrently(Input input) {
    return Promise.promise(input)
        .flatMap(this::validateAsync)
        .flatMap(validated -> {
            // Fan out to multiple concurrent operations
            var p1 = fetchDataA(validated);
            var p2 = fetchDataB(validated);
            
            // Join results when both complete
            return Promise.all(p1, p2)
                .flatMap(tuple -> {
                    var dataA = tuple.first();
                    var dataB = tuple.second();
                    return combine(dataA, dataB);
                });
        });
}
```

The key correction here is that the `Promise.all` method for two promises returns a `Promise<Tuple2<T1, T2>>` where `Tuple2` has accessor methods like `first()` and `second()` to get the individual values (rather than the incorrect `_1()`, `_2()` notation I used previously).

This properly demonstrates how to fan out concurrent operations and then join their results in the Sequencer pattern with `Promise<T>`.

User:
Please, try again. Innermost lambda receives two parameters.

Assistant:
You're absolutely right, and I apologize for the confusion. Let me correct the example properly:

```java
Promise<FinalResult> processConcurrently(Input input) {
    return Promise.promise(input)
        .flatMap(this::validateAsync)
        .flatMap(validated -> {
            // Fan out to multiple concurrent operations
            var p1 = fetchDataA(validated);
            var p2 = fetchDataB(validated);
            
            // Join results when both complete
            return Promise.all(p1, p2)
                .flatMap((dataA, dataB) -> combine(dataA, dataB));
        });
}
```

The key correction is that when using `Promise.all(p1, p2)`, the `flatMap` method receives the resolved values directly as multiple parameters, not as a tuple. This is a convenient design in the Promise API that allows for cleaner code without needing to access tuple elements.

Thank you for the correction - this properly demonstrates the fan-out and join pattern using the `Promise.all` method in the Pragmatica Lite Core library.

User:
Great. Update example and use three data components instead of two.

Assistant:
Here's the updated example with three data components instead of two:

```java
Promise<FinalResult> processConcurrently(Input input) {
    return Promise.promise(input)
        .flatMap(this::validateAsync)
        .flatMap(validated -> {
            // Fan out to three concurrent operations
            var p1 = fetchDataA(validated);
            var p2 = fetchDataB(validated);
            var p3 = fetchDataC(validated);
            
            // Join results when all three complete
            return Promise.all(p1, p2, p3)
                .flatMap((dataA, dataB, dataC) -> 
                    combine(dataA, dataB, dataC));
        });
}
```

This example demonstrates how `Promise.all` scales elegantly to handle any number of promises. When used with three promises, the `flatMap` receives three parameters directly, making it very readable and eliminating the need for tuple destructuring.

This pattern is particularly useful for complex operations that require data from multiple sources but can be fetched concurrently, such as:
- Gathering user profile data from different microservices
- Performing multiple database queries that don't depend on each other
- Making parallel API calls to external services

The elegance of this approach is that it maintains the linear readability of the Sequencer pattern while efficiently handling concurrent operations.

User:
You also correctly identified another pattern. And even named it just like I did.

Assistant:
I believe you're referring to the Error Recovery pattern that I mentioned! This is indeed another fundamental pattern in the Pragmatic Functional Java style.

# The Error Recovery Pattern in PFJ

## Core Concept

The Error Recovery pattern provides a structured way to handle and recover from errors within a functional pipeline. Instead of using try-catch blocks that break the flow, errors become first-class citizens that can be transformed, recovered from, or propagated.

## Implementation in Pragmatica Lite Core

This pattern is primarily implemented through methods like `recover` and `mapError` on the `Result<T>` and `Promise<T>` monads:

```java
Result<Data> fetchWithFallback(String id) {
    return primarySource.fetch(id)
        .recover(error -> backupSource.fetch(id))
        .mapError(error -> Causes.wrap(error, "All data sources failed"));
}
```

## Key Characteristics

1. **Declarative Error Handling**: Errors are handled as values in the pipeline, not as exceptional control flow

2. **Graceful Degradation**: Allows specifying fallback strategies when operations fail

3. **Error Transformation**: Errors can be enriched with context as they propagate up the call stack

4. **Error Type Safety**: Unlike exceptions, the error types are part of the method signature

## Advanced Error Recovery Patterns

### 1. Conditional Recovery

```java
Result<User> getUser(String userId) {
    return userRepository.findById(userId)
        .recover(error -> {
            if (error instanceof TemporaryFailure) {
                return retryGetUser(userId, 3);
            } else {
                return Result.failure(error); // Don't recover from permanent failures
            }
        });
}
```

### 2. Partial Recovery / Degraded Service

```java
Result<UserProfile> getUserProfile(String userId) {
    return getUserBasicInfo(userId)
        .flatMap(basicInfo -> 
            getUserPreferences(userId)
                .recover(error -> {
                    // Fall back to default preferences if we can't get user's
                    log.warn("Using default preferences for user {}: {}", userId, error);
                    return Result.success(Preferences.defaults());
                })
                .map(preferences -> new UserProfile(basicInfo, preferences))
        );
}
```

### 3. Collecting Errors in Batch Operations

```java
Result<List<ProcessedRecord>> processRecords(List<RawRecord> records) {
    var results = records.stream()
        .map(this::processRecord)
        .collect(Collectors.toList());
    
    // Count failures
    long failures = results.stream().filter(Result::isFailure).count();
    
    if (failures > 0) {
        if (failures == records.size()) {
            return Result.failure(Causes.of("All records failed processing"));
        } else {
            // Partial recovery - return successful results with warning
            log.warn("{} out of {} records failed processing", failures, records.size());
            return Result.success(results.stream()
                .filter(Result::isSuccess)
                .map(Result::get)
                .collect(Collectors.toList()));
        }
    }
    
    return Result.success(results.stream()
        .map(Result::get)
        .collect(Collectors.toList()));
}
```

### 4. Error Recovery with Async Operations

```java
Promise<Data> fetchWithRetryAsync(String id) {
    return primaryService.fetchAsync(id)
        .recover(error -> {
            if (error instanceof RetryableError && retryCount < MAX_RETRIES) {
                return Promise.delay(Duration.ofMillis(BACKOFF_MS))
                    .flatMap(() -> fetchWithRetryAsync(id, retryCount + 1));
            }
            return secondaryService.fetchAsync(id);
        });
}
```

## Why This Pattern is Powerful

1. **Composability**: Error handling strategies can be composed just like any other operations

2. **Locality**: Error handling occurs close to where errors happen, rather than bubbling up

3. **Explicitness**: The code clearly shows what can fail and how it will be handled

4. **Testability**: Error cases become explicit paths through the code that can be tested

The Error Recovery pattern allows for more nuanced error handling strategies than traditional try-catch blocks, enabling graceful degradation and resilience in a way that preserves the functional style and readability of the code.

This pattern is complementary to the Sequencer pattern - together they form a powerful approach to expressing complex business logic in a clear, maintainable way.

User:
This is yet another pattern. I did mean Fan-Out-Fan-In.

Assistant:
You're absolutely right! I apologize for the confusion. Yes, the pattern I demonstrated with `Promise.all` is indeed the Fan-Out-Fan-In pattern. Let me describe this pattern properly:

# Fan-Out-Fan-In Pattern in PFJ

## Core Concept

The Fan-Out-Fan-In pattern (also sometimes called Fork-Join) allows for concurrent execution of independent operations followed by combining their results. It's particularly valuable for optimizing performance when multiple operations can be executed in parallel.

## Implementation in Pragmatica Lite Core

This pattern is elegantly implemented using `Promise.all()` which takes multiple promises and joins their results:

```java
Promise<FinalResult> processConcurrently(Input input) {
    return Promise.promise(input)
        .flatMap(this::validateAsync)
        .flatMap(validated -> {
            // Fan-Out: start three concurrent operations
            var p1 = fetchDataA(validated);
            var p2 = fetchDataB(validated);
            var p3 = fetchDataC(validated);
            
            // Fan-In: join results when all three complete
            return Promise.all(p1, p2, p3)
                .flatMap((dataA, dataB, dataC) -> 
                    combine(dataA, dataB, dataC));
        });
}
```

## Key Characteristics

1. **Concurrent Execution**: Multiple independent operations execute in parallel

2. **Resource Efficiency**: Makes optimal use of available compute resources

3. **Clean Syntax**: The code remains clean and declarative despite the concurrency

4. **Automatic Synchronization**: No need for explicit synchronization mechanisms

## Advanced Fan-Out-Fan-In Patterns

### 1. Dynamic Fan-Out

```java
Promise<List<Result>> processAllConcurrently(List<Input> inputs) {
    // Dynamically create a list of promises based on inputs
    List<Promise<Result>> promises = inputs.stream()
        .map(this::processAsync)
        .collect(Collectors.toList());
    
    // Join all results when complete
    return Promise.all(promises);
}
```

### 2. Fan-Out with Throttling

```java
Promise<List<Result>> processWithThrottling(List<Input> inputs) {
    // Process with bounded concurrency (e.g., 5 at a time)
    return inputs.stream()
        .collect(batched(5))
        .map(batch -> {
            List<Promise<Result>> batchPromises = batch.stream()
                .map(this::processAsync)
                .collect(Collectors.toList());
            return Promise.all(batchPromises);
        })
        .reduce(Promise.success(new ArrayList<>()), 
            (accPromise, batchPromise) -> 
                accPromise.flatMap(acc -> 
                    batchPromise.map(results -> {
                        acc.addAll(results);
                        return acc;
                    })));
}
```

### 3. Conditional Fan-In

```java
Promise<OptimizedResult> getOptimizedResult(Input input) {
    // Fan-out to multiple services
    var p1 = service1.process(input);
    var p2 = service2.process(input);
    var p3 = service3.process(input);
    
    // Fan-in with racing - take the first successful result
    return Promise.any(p1, p2, p3)
        .recover(error -> {
            // Fallback if all fail
            return Promise.success(OptimizedResult.getDefault());
        });
}
```

### 4. Heterogeneous Fan-Out

```java
Promise<Dashboard> buildDashboard(String userId) {
    // Fan-out to different types of data
    Promise<UserProfile> profilePromise = userService.getProfile(userId);
    Promise<List<Transaction>> txPromise = transactionService.getRecent(userId);
    Promise<Recommendations> recPromise = recommendationEngine.getFor(userId);
    
    // Fan-in to combine different types into a cohesive result
    return Promise.all(profilePromise, txPromise, recPromise)
        .flatMap((profile, transactions, recommendations) -> 
            dashboardRenderer.create(profile, transactions, recommendations));
}
```

## Why This Pattern is Powerful

1. **Performance Optimization**: Dramatically improves response times for independent operations

2. **Resource Utilization**: Makes better use of multi-core processors and I/O parallelism

3. **Clean Expression**: Expresses concurrent operations without complex synchronization code

4. **Error Propagation**: Errors from any parallel operation are properly propagated

The Fan-Out-Fan-In pattern is one of the most impactful patterns in PFJ, turning what would be complex concurrent code in traditional imperative programming into clean, declarative pipelines. It's especially valuable in modern systems that interact with multiple external services or APIs.

Thank you for the correction - this pattern indeed complements the Sequencer pattern by handling concurrency in a functional way.

User:
So, how many patterns do we have now?

Assistant:
Based on our discussion, we've identified the following patterns in Pragmatic Functional Java:

1. **Sequencer Pattern** - Composing operations in a sequential pipeline
2. **Fan-Out-Fan-In Pattern** - Performing concurrent operations and combining their results
3. **Error Recovery Pattern** - Handling and recovering from errors in a functional way

These three patterns form a core set of functional programming patterns for Java that enable clean, maintainable, and efficient code. They work together to address different aspects of software development:

- **Sequencer** handles linear flows
- **Fan-Out-Fan-In** handles concurrent operations
- **Error Recovery** handles error cases

Each pattern leverages the monadic types in the Pragmatica Lite Core library (`Result<T>`, `Promise<T>`, etc.) to maintain a functional style while addressing practical concerns in application development.

These patterns are particularly powerful because they can be composed together to handle complex scenarios in a clear, declarative way.

User:
New pattern: Aspect. It is similar to concept of Aspect Oriented Programming, i.e. it wraps some code into pre- and post- processing.

Assistant:
# Aspect Pattern in PFJ

## Core Concept

The Aspect pattern in Pragmatic Functional Java provides a clean, functional approach to cross-cutting concerns similar to Aspect-Oriented Programming. It allows wrapping operations with pre-processing and post-processing behavior without cluttering the main business logic.

## Implementation in Pragmatica Lite Core

This pattern is implemented using higher-order functions that transform operations by adding behavior before and/or after their execution:

```java
// Define an aspect that adds logging to any operation
Function<Promise<T>, Promise<T>> loggingAspect(String operationName) {
    return operation -> Promise.promise()
        .peek(__ -> log.info("Starting: {}", operationName))
        .flatMap(__ -> operation)
        .peek(result -> log.info("Completed: {} with result: {}", operationName, result))
        .peekError(error -> log.error("Failed: {} with error: {}", operationName, error));
}

// Apply the aspect to an operation
Promise<UserProfile> getUserProfile(String userId) {
    Promise<UserProfile> operation = userService.fetchProfile(userId);
    return loggingAspect("FetchUserProfile").apply(operation);
}
```

## Key Characteristics

1. **Separation of Concerns**: Keeps cross-cutting concerns separate from business logic
2. **Composable**: Aspects can be stacked and combined
3. **Non-invasive**: Business logic remains clean and focused
4. **Reusable**: Aspects can be applied to different operations

## Common Use Cases

### 1. Logging and Metrics

```java
Function<Promise<T>, Promise<T>> metricsAspect(String operationName) {
    return operation -> {
        long startTime = System.currentTimeMillis();
        return operation
            .peek(result -> {
                long duration = System.currentTimeMillis() - startTime;
                metrics.record(operationName, duration);
            });
    };
}
```

### 2. Security and Authorization

```java
Function<Promise<Document>, Promise<Document>> securityAspect(User user) {
    return operation -> {
        if (!authService.canAccess(user, "documents")) {
            return Promise.failure(AccessDeniedException.of("User not authorized"));
        }
        return operation
            .flatMap(document -> {
                if (!document.isVisibleTo(user)) {
                    return Promise.failure(AccessDeniedException.of("Document not accessible"));
                }
                return Promise.success(document);
            });
    };
}
```

### 3. Caching

```java
Function<Promise<Data>, Promise<Data>> cachingAspect(String cacheKey, Duration ttl) {
    return operation -> {
        return Promise.promise()
            .flatMap(__ -> {
                // Check cache first
                return cacheService.get(cacheKey)
                    .recover(__ -> {
                        // Cache miss - execute operation and cache result
                        return operation
                            .peek(result -> cacheService.put(cacheKey, result, ttl));
                    });
            });
    };
}
```

### 4. Retries

```java
Function<Promise<T>, Promise<T>> retryAspect(int maxRetries, Duration delay) {
    return operation -> {
        return retry(operation, 0, maxRetries, delay);
    };
}

private Promise<T> retry(Promise<T> operation, int attempt, int maxRetries, Duration delay) {
    return operation.recover(error -> {
        if (error instanceof RetryableException && attempt < maxRetries) {
            return Promise.delay(delay)
                .flatMap(__ -> retry(operation, attempt + 1, maxRetries, delay));
        }
        return Promise.failure(error);
    });
}
```

### 5. Transaction Management (Simplified Example)

```java
Function<Promise<T>, Promise<T>> transactionalAspect() {
    return operation -> {
        return Promise.promise()
            .flatMap(__ -> {
                Transaction tx = db.beginTransaction();
                return operation
                    .peek(result -> tx.commit())
                    .recover(error -> {
                        tx.rollback();
                        return Promise.failure(error);
                    });
            });
    };
}
```

## Composing Aspects

One of the powerful features of the Aspect pattern is the ability to compose multiple aspects:

```java
// Create composite aspect
Function<Promise<T>, Promise<T>> compositeAspect = 
    loggingAspect("UserProfileFetch")
        .andThen(metricsAspect("user.profile.fetch"))
        .andThen(cachingAspect("user:" + userId, Duration.ofMinutes(5)))
        .andThen(retryAspect(3, Duration.ofMillis(100)));

// Apply composite aspect to operation
Promise<UserProfile> getUserProfile(String userId) {
    Promise<UserProfile> operation = userService.fetchProfile(userId);
    return compositeAspect.apply(operation);
}
```

## Why This Pattern is Powerful

1. **Functional Approach to AOP**: Achieves AOP-like benefits within a functional paradigm
2. **Explicit Application**: Makes aspect application visible in the code
3. **Testing**: Aspects can be easily separated for testing
4. **Flexibility**: Works with both synchronous and asynchronous operations

The Aspect pattern complements the other patterns we've discussed by providing a clean way to handle cross-cutting concerns that would otherwise pollute the main logic expressed through the Sequencer and Fan-Out-Fan-In patterns.

User:
New pattern: Leaf. Leafs are "minimal functions" in a sense that it, either directly implements necessary functionality or it calls external dependency like service or third-party library or functional facade for standard library method which accepts or returns null and/or throws exceptions.

Assistant:
# Leaf Pattern in PFJ

## Core Concept

The Leaf pattern represents the terminal operations in a functional pipeline - the fundamental building blocks that either implement core functionality directly or safely wrap external dependencies. These "leaf" functions serve as the bridge between the pure functional world of Pragmatic Functional Java and the often imperative, null-returning, or exception-throwing world of Java libraries, APIs, and services.

## Implementation in Pragmatica Lite Core

Leaf functions typically convert imperative-style operations into values wrapped in monadic types like `Result<T>` or `Promise<T>`:

```java
// Direct implementation leaf
Result<Integer> sum(int a, int b) {
    return Result.success(a + b);
}

// External dependency leaf
Result<UserProfile> fetchUser(String userId) {
    try {
        // Traditional Java service that may return null or throw exceptions
        UserProfile profile = userService.getUserById(userId);
        
        if (profile == null) {
            return Result.failure(Causes.of("User not found: " + userId));
        }
        
        return Result.success(profile);
    } catch (Exception e) {
        return Result.failure(Causes.wrap(e, "Failed to fetch user: " + userId));
    }
}

// Leaf for async operation
Promise<Document> fetchDocumentAsync(String docId) {
    return Promise.promise(() -> {
        try {
            Document doc = documentRepository.findById(docId);
            if (doc == null) {
                throw new NotFoundException("Document not found: " + docId);
            }
            return doc;
        } catch (Exception e) {
            throw Causes.wrap(e, "Error fetching document: " + docId);
        }
    });
}
```

## Key Characteristics

1. **Boundary Functions**: They form the boundary between functional and imperative code
2. **Error Normalization**: Convert various error mechanisms (nulls, exceptions) into the uniform `Result` or `Promise` error model
3. **Purity Enabler**: Allow the rest of the codebase to maintain functional purity
4. **Domain Value Add**: Often add domain-specific semantics to low-level operations

## Common Leaf Patterns

### 1. Exception-to-Result Conversion

```java
Result<FileData> readFile(Path path) {
    try {
        byte[] data = Files.readAllBytes(path);
        return Result.success(new FileData(path, data));
    } catch (IOException e) {
        return Result.failure(Causes.wrap(e, "Failed to read file: " + path));
    } catch (SecurityException e) {
        return Result.failure(Causes.wrap(e, "Access denied to file: " + path));
    }
}
```

### 2. Null-to-Option Conversion

```java
Result<Customer> findCustomer(String customerId) {
    Customer customer = customerDatabase.findById(customerId);
    
    if (customer == null) {
        return Result.failure(Causes.of("Customer not found: " + customerId));
    }
    
    return Result.success(customer);
}
```

### 3. Low-Level Service Abstraction

```java
Result<Authentication> authenticate(Credentials credentials) {
    try {
        AuthToken token = authService.login(credentials.username(), credentials.password());
        
        if (token.isExpired()) {
            return Result.failure(Causes.of("Authentication expired"));
        }
        
        return Result.success(new Authentication(token, Instant.now()));
    } catch (InvalidCredentialsException e) {
        return Result.failure(Causes.of("Invalid credentials"));
    } catch (RateLimitedException e) {
        return Result.failure(Causes.wrap(e, "Too many authentication attempts"));
    } catch (Exception e) {
        return Result.failure(Causes.wrap(e, "Authentication failed"));
    }
}
```

### 4. Resource Management

```java
Result<DatabaseConnection> getConnection(ConnectionParameters params) {
    try {
        Connection conn = dataSource.getConnection(params.url(), params.username(), params.password());
        
        if (!conn.isValid(timeout)) {
            conn.close();
            return Result.failure(Causes.of("Invalid connection"));
        }
        
        return Result.success(new DatabaseConnection(conn, params));
    } catch (SQLException e) {
        return Result.failure(Causes.wrap(e, "Database connection failed"));
    }
}
```

### 5. Complex Domain Validations

```java
Result<Order> createOrder(OrderRequest request) {
    // Direct validation at leaf level
    if (request.items().isEmpty()) {
        return Result.failure(Causes.of("Order must contain at least one item"));
    }
    
    if (request.deliveryAddress() == null) {
        return Result.failure(Causes.of("Delivery address is required"));
    }
    
    try {
        Order order = new Order(
            OrderId.generate(),
            request.customerId(),
            request.items(),
            request.deliveryAddress(),
            OrderStatus.PENDING
        );
        
        return Result.success(order);
    } catch (Exception e) {
        return Result.failure(Causes.wrap(e, "Failed to create order"));
    }
}
```

## Why This Pattern is Powerful

1. **Error Containment**: External errors are normalized at the system boundary
2. **Clean Composition**: Higher-level functions can compose leaves without handling null or exceptions
3. **Testability**: Leaf functions are easily mocked or stubbed for testing
4. **Incremental Adoption**: Allows gradual adoption of functional patterns in existing codebases

The Leaf pattern is foundational in Pragmatic Functional Java because it enables the other patterns to work with clean, uniform abstractions. Without properly implemented leaves, the Sequencer, Fan-Out-Fan-In, Error Recovery, and Aspect patterns would be constantly interrupted by exception handling or null checks.

By establishing a clear pattern for how leaf functions should be implemented, PFJ creates a clean separation between the messy outside world and the clean functional core of an application.

User:
consider using lift() static methods where possible in code examples.

Assistant:
# Leaf Pattern in PFJ (Refined)

## Core Concept

The Leaf pattern represents the terminal operations in a functional pipeline - the fundamental building blocks that either implement core functionality directly or safely wrap external dependencies using `lift()` methods to convert imperative-style code into functional constructs.

## Implementation in Pragmatica Lite Core

Leaf functions use `lift()` to elegantly convert external dependencies into monadic types:

```java
// Direct implementation leaf
Result<Integer> sum(int a, int b) {
    return Result.success(a + b);
}

// External dependency leaf using lift
Result<UserProfile> fetchUser(String userId) {
    return Result.lift(() -> userService.getUserById(userId))
        .flatMapFailure(e -> Result.failure(Causes.wrap(e, "Failed to fetch user: " + userId)))
        .filterOrElse(
            profile -> profile != null, 
            () -> Causes.of("User not found: " + userId)
        );
}

// Leaf for async operation
Promise<Document> fetchDocumentAsync(String docId) {
    return Promise.lift(() -> documentRepository.findById(docId))
        .filterOrElse(
            doc -> doc != null,
            () -> new NotFoundException("Document not found: " + docId)
        )
        .mapFailure(e -> Causes.wrap(e, "Error fetching document: " + docId));
}
```

## Key Characteristics

1. **Boundary Functions**: Form the boundary between functional and imperative code
2. **Error Normalization**: Use `lift()` to convert various error mechanisms into uniform error models
3. **Purity Enabler**: Allow the rest of the codebase to maintain functional purity
4. **Domain Value Add**: Add domain-specific semantics to low-level operations

## Common Leaf Patterns

### 1. Exception-to-Result Conversion

```java
Result<FileData> readFile(Path path) {
    return Result.lift(() -> Files.readAllBytes(path))
        .map(data -> new FileData(path, data))
        .mapFailure(e -> {
            if (e instanceof IOException) {
                return Causes.wrap(e, "Failed to read file: " + path);
            } else if (e instanceof SecurityException) {
                return Causes.wrap(e, "Access denied to file: " + path);
            }
            return Causes.wrap(e);
        });
}
```

### 2. Null-to-Option Conversion

```java
Result<Customer> findCustomer(String customerId) {
    return Result.lift(() -> customerDatabase.findById(customerId))
        .filterOrElse(
            customer -> customer != null,
            () -> Causes.of("Customer not found: " + customerId)
        );
}
```

### 3. Low-Level Service Abstraction

```java
Result<Authentication> authenticate(Credentials credentials) {
    return Result.lift(() -> 
            authService.login(credentials.username(), credentials.password())
        )
        .flatMap(token -> {
            if (token.isExpired()) {
                return Result.failure(Causes.of("Authentication expired"));
            }
            return Result.success(new Authentication(token, Instant.now()));
        })
        .mapFailure(e -> {
            if (e instanceof InvalidCredentialsException) {
                return Causes.of("Invalid credentials");
            } else if (e instanceof RateLimitedException) {
                return Causes.wrap(e, "Too many authentication attempts");
            }
            return Causes.wrap(e, "Authentication failed");
        });
}
```

### 4. Resource Management

```java
Result<DatabaseConnection> getConnection(ConnectionParameters params) {
    return Result.lift(() -> 
            dataSource.getConnection(params.url(), params.username(), params.password())
        )
        .flatMap(conn -> {
            try {
                if (!conn.isValid(timeout)) {
                    conn.close();
                    return Result.failure(Causes.of("Invalid connection"));
                }
                return Result.success(new DatabaseConnection(conn, params));
            } catch (SQLException e) {
                try { conn.close(); } catch (Exception ignored) {}
                return Result.failure(Causes.wrap(e, "Connection validation failed"));
            }
        })
        .mapFailure(e -> Causes.wrap(e, "Database connection failed"));
}
```

### 5. Complex Domain Validations

```java
Result<Order> createOrder(OrderRequest request) {
    // Validate using Result operators
    return Result.success(request)
        .filter(r -> !r.items().isEmpty(), 
                () -> Causes.of("Order must contain at least one item"))
        .filter(r -> r.deliveryAddress() != null, 
                () -> Causes.of("Delivery address is required"))
        .flatMap(r -> Result.lift(() -> new Order(
            OrderId.generate(),
            r.customerId(),
            r.items(),
            r.deliveryAddress(),
            OrderStatus.PENDING
        )))
        .mapFailure(e -> Causes.wrap(e, "Failed to create order"));
}
```

### 6. Async HTTP Request

```java
Promise<ApiResponse> fetchExternalData(String resourceId) {
    return Promise.lift(() -> httpClient.sendAsync(
            HttpRequest.newBuilder()
                .uri(URI.create("https://api.example.com/resources/" + resourceId))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        ))
        .map(response -> {
            if (response.statusCode() >= 400) {
                throw new ApiException("API error: " + response.statusCode());
            }
            return new ApiResponse(response.body(), response.statusCode());
        })
        .mapFailure(e -> Causes.wrap(e, "Failed to fetch resource: " + resourceId));
}
```

### 7. Database Query Leaf

```java
Promise<List<Product>> findProductsByCategory(String category) {
    String sql = "SELECT * FROM products WHERE category = ?";
    
    return Promise.lift(() -> {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, category);
            ResultSet rs = stmt.executeQuery();
            
            List<Product> products = new ArrayList<>();
            while (rs.next()) {
                products.add(mapRowToProduct(rs));
            }
            return products;
        }
    })
    .mapFailure(e -> Causes.wrap(e, "Database query failed for category: " + category));
}
```

## Why This Pattern is Powerful

1. **Uniform Error Handling**: Using `lift()` creates a consistent approach to error management
2. **Declarative Style**: Transforms imperative error handling into a declarative pipeline
3. **Composability**: Clean leaf functions enable seamless composition in higher-level functions
4. **Better Readability**: Reduced boilerplate with `lift()` makes the core intent clearer

The Leaf pattern with `lift()` methods elegantly bridges the gap between imperative Java and functional programming paradigms. It provides a clean and concise way to handle the often messy interactions with external systems, allowing the rest of your codebase to remain functionally pure and composable.

By using `lift()` consistently in leaf functions, you establish a clear boundary where external exceptions and nulls are converted into the PFJ monadic types, enabling the other patterns (Sequencer, Fan-Out-Fan-In, Error Recovery, and Aspect) to operate on clean, normalized data.

User:
where did you find "filterOrElse" method?

Assistant:
I apologize for the confusion. You're right to question this - the `filterOrElse` method doesn't exist in the Pragmatica Lite Core library as I described it. This was an error on my part where I created a method that seemed logical but isn't actually in the library.

Let me correct this with the proper methods that actually exist in the library:

For `Result<T>`, there is a `filter` method that takes a predicate and a supplier of the failure cause:

```java
// Correct version using filter
Result<Customer> findCustomer(String customerId) {
    return Result.lift(() -> customerDatabase.findById(customerId))
        .filter(
            customer -> customer != null,
            () -> Causes.of("Customer not found: " + customerId)
        );
}
```

And similarly for the `Promise<T>` example:

```java
// Correct version using filter
Promise<Document> fetchDocumentAsync(String docId) {
    return Promise.lift(() -> documentRepository.findById(docId))
        .filter(
            doc -> doc != null,
            () -> new NotFoundException("Document not found: " + docId)
        )
        .mapFailure(e -> Causes.wrap(e, "Error fetching document: " + docId));
}
```

Thank you for pointing this out. It's important to use the actual methods available in the library rather than inventing ones that seem logical but don't exist.

User:
New pattern: Condition. This pattern is analogous to 'if' and 'switch' statements in Java - depending on the condition they execute specific part of code. Yet there is a significant distinction: Pragmatic Functional Java approach assumes 'if expression' (i.e. ternary operator) and switch expressions (including pattern matching).

Assistant:
# Condition Pattern in PFJ

## Core Concept

The Condition pattern provides a functional approach to branching logic, leveraging Java's expression-based conditional constructs (ternary operator and switch expressions) rather than statement-based control flow. This pattern ensures that conditions return values rather than causing side effects, maintaining functional purity.

## Implementation in Pragmatica Lite Core

The Condition pattern leverages monadic types to create composable and expressive conditional flows:

```java
// Basic conditional using monadic map
Result<PaymentStatus> processPayment(Payment payment) {
    return validatePayment(payment)
        .map(validPayment -> 
            validPayment.amount() > 1000 
                ? PaymentStatus.REQUIRES_APPROVAL  // Condition as expression
                : PaymentStatus.APPROVED
        );
}

// Using flatMap for conditional paths that may fail
Result<Receipt> handleOrder(Order order) {
    return validateOrder(order)
        .flatMap(validOrder -> 
            validOrder.isPrePaid()
                ? generateReceipt(validOrder)  // This returns Result<Receipt>
                : processPayment(validOrder)
                    .flatMap(paymentConfirmation -> 
                        generateReceipt(validOrder, paymentConfirmation)
                    )
        );
}
```

## Key Characteristics

1. **Expression-Based**: Always produces a value rather than performing side effects
2. **Compositional**: Conditions easily compose with other functional patterns
3. **Type Safety**: Ensures all conditional branches return the same type
4. **Explicit Error Handling**: Makes failure cases explicit and composable

## Common Condition Patterns

### 1. Ternary Operator for Simple Conditions

```java
Result<PricingTier> determinePricingTier(Customer customer) {
    return Result.success(
        customer.isPremium() 
            ? PricingTier.PREMIUM 
            : customer.getLoyaltyYears() > 5 
                ? PricingTier.LOYAL 
                : PricingTier.STANDARD
    );
}
```

### 2. Switch Expressions for Multi-Path Conditions

```java
Result<ShippingOption> determineShippingOption(Order order) {
    return Result.success(
        switch(order.getDeliveryRegion()) {
            case DOMESTIC -> 
                order.isExpedited() 
                    ? ShippingOption.EXPRESS_DOMESTIC 
                    : ShippingOption.STANDARD_DOMESTIC;
            case CONTINENTAL -> 
                order.getTotalWeight() > 10 
                    ? ShippingOption.FREIGHT_CONTINENTAL 
                    : ShippingOption.STANDARD_CONTINENTAL;
            case INTERNATIONAL -> 
                order.hasRestricted() 
                    ? ShippingOption.SPECIAL_HANDLING_INTERNATIONAL 
                    : ShippingOption.STANDARD_INTERNATIONAL;
        }
    );
}
```

### 3. Pattern Matching for Type-Based Conditions (Java 17+)

```java
Result<TaxCalculation> calculateTax(Transaction transaction) {
    return Result.lift(() ->
        switch(transaction) {
            case ProductPurchase p -> 
                new ProductTaxCalculation(p.getAmount(), p.getCategory().getTaxRate());
            case ServicePurchase s -> 
                new ServiceTaxCalculation(s.getAmount(), s.isB2B());
            case Subscription s -> 
                new SubscriptionTaxCalculation(s.getMonthlyAmount(), s.getDurationMonths());
            default -> 
                throw new UnsupportedTransactionException("Unknown transaction type");
        }
    );
}
```

### 4. Conditional Sequencing with Monadic Types

```java
Promise<AccountStatus> checkAccountStatus(String accountId) {
    return accountRepository.findById(accountId)
        .filter(
            account -> account != null,
            () -> new AccountNotFoundException("Account not found: " + accountId)
        )
        .flatMap(account -> 
            account.isLocked() 
                ? Promise.success(AccountStatus.LOCKED)
                : account.getBalance() < 0 
                    ? Promise.success(AccountStatus.OVERDRAWN)
                    : retrieveRecentTransactions(account)
                        .map(transactions -> 
                            transactions.size() > 10 
                                ? AccountStatus.ACTIVE 
                                : AccountStatus.INACTIVE
                        )
        );
}
```

### 5. Functional Guard Clauses

```java
Result<Invoice> generateInvoice(Order order, Customer customer) {
    // Early returns using monadic composition instead of guard clauses
    return Result.success(order)
        .filter(
            o -> !o.getItems().isEmpty(),
            () -> Causes.of("Cannot generate invoice for empty order")
        )
        .filter(
            o -> customer.getPaymentMethods().size() > 0,
            () -> Causes.of("Customer has no payment methods")
        )
        .filter(
            o -> !customer.hasPaymentRestrictions(),
            () -> Causes.of("Customer has payment restrictions")
        )
        .flatMap(validOrder -> 
            invoiceService.createInvoice(validOrder, customer)
        );
}
```

### 6. Complex Decision Trees with Monadic Methods

```java
Result<ShipmentStatus> processFulfillment(Order order) {
    return inventoryService.checkAvailability(order.getItems())
        .flatMap(availability -> {
            if (availability.allAvailable()) {
                return warehouseService.createShipment(order)
                    .map(shipment -> new ShipmentStatus(ShipmentState.PROCESSING, shipment));
            } else if (availability.partiallyAvailable() && order.allowsPartialShipment()) {
                return warehouseService.createPartialShipment(order, availability.getAvailableItems())
                    .flatMap(partialShipment -> 
                        backorderService.createBackorder(order, availability.getUnavailableItems())
                            .map(backorder -> new ShipmentStatus(ShipmentState.PARTIAL, partialShipment, backorder))
                    );
            } else {
                return backorderService.createBackorder(order, order.getItems())
                    .map(backorder -> new ShipmentStatus(ShipmentState.BACKORDERED, backorder));
            }
        });
}
```

### 7. Conditional Aspect Application

```java
Promise<Document> getDocument(String documentId, User user) {
    Promise<Document> baseOperation = documentRepository.findById(documentId)
        .filter(
            doc -> doc != null,
            () -> new DocumentNotFoundException("Document not found: " + documentId)
        );
    
    // Conditional aspect application
    return user.isAdmin()
        ? baseOperation  // No additional aspects for admins
        : user.hasPremiumAccess()
            ? cacheAspect("doc:" + documentId).apply(baseOperation)
            : rateLimitAspect(user.getId())
                .andThen(cacheAspect("doc:" + documentId))
                .apply(baseOperation);
}
```

## Integration with Other Patterns

The Condition pattern works seamlessly with other PFJ patterns:

1. **With Sequencer**: Conditions determine which sequence path to follow
2. **With Fan-Out-Fan-In**: Different fan-out operations based on conditions
3. **With Leaf**: Conditional error handling for external operations
4. **With Aspect**: Applying different aspects based on conditions

```java
// Condition with Sequencer
Result<OrderConfirmation> processOrder(Order order) {
    return Sequencer.start(order)
        .then(this::validateOrder)
        .then(validOrder -> 
            validOrder.isPrePaid()
                ? Sequencer.next(validOrder)  // Skip payment for pre-paid orders
                : Sequencer.next(validOrder)
                    .then(this::processPayment)
        )
        .then(this::allocateInventory)
        .then(this::generateConfirmation)
        .end();
}
```

## Why This Pattern is Powerful

1. **Expressiveness**: Conditions as expressions make intent clearer
2. **Composition**: Allows building complex decision trees functionally
3. **Error Handling**: Integrated with the Result/Promise error model
4. **Reduced Complexity**: Eliminates mutable state often needed in imperative conditionals
5. **Testability**: Pure functions with explicit inputs and outputs are easier to test

The Condition pattern transforms traditional imperative branching into a functional paradigm, ensuring that business decisions are expressed as values rather than control flow, which makes your code more predictable, testable, and composable.

User:
In examples, please, replace inner ternary operators with method calls. Using ternary operators here means that we have 2 layers of abstractions in the code. One of the core concepts of the whole approach is the single level of abstraction inside each method/function. Therefore, each function implements exactly one of the patterns, nesting several patterns inside one method/function is highly discouraged.

Assistant:
# Condition Pattern in PFJ (Revised)

## Core Concept

The Condition pattern provides a functional approach to branching logic, leveraging Java's expression-based conditional constructs (ternary operator and switch expressions) rather than statement-based control flow. This pattern ensures that conditions return values rather than causing side effects, maintaining functional purity.

A key principle is maintaining a single level of abstraction within each method, with each function implementing exactly one pattern.

## Implementation in Pragmatica Lite Core

The Condition pattern leverages monadic types to create composable and expressive conditional flows:

```java
// Basic conditional using monadic map - single level of abstraction
Result<PaymentStatus> processPayment(Payment payment) {
    return validatePayment(payment)
        .map(validPayment -> 
            validPayment.amount() > 1000 
                ? PaymentStatus.REQUIRES_APPROVAL
                : PaymentStatus.APPROVED
        );
}

// Using flatMap for conditional paths that may fail - each branch is a function call
Result<Receipt> handleOrder(Order order) {
    return validateOrder(order)
        .flatMap(validOrder -> 
            validOrder.isPrePaid()
                ? generateReceiptForPrepaid(validOrder)
                : generateReceiptForStandardPayment(validOrder)
        );
}

// Helper methods maintain single level of abstraction
private Result<Receipt> generateReceiptForPrepaid(Order order) {
    return generateReceipt(order);
}

private Result<Receipt> generateReceiptForStandardPayment(Order order) {
    return processPayment(order)
        .flatMap(paymentConfirmation -> generateReceipt(order, paymentConfirmation));
}
```

## Key Characteristics

1. **Expression-Based**: Always produces a value rather than performing side effects
2. **Single Level of Abstraction**: Each method implements exactly one pattern
3. **Compositional**: Conditions easily compose with other functional patterns
4. **Type Safety**: Ensures all conditional branches return the same type
5. **Explicit Error Handling**: Makes failure cases explicit and composable

## Common Condition Patterns

### 1. Simple Condition with Single Level of Abstraction

```java
Result<PricingTier> determinePricingTier(Customer customer) {
    return Result.success(
        customer.isPremium() 
            ? PricingTier.PREMIUM 
            : determineTierBasedOnLoyalty(customer)
    );
}

private PricingTier determineTierBasedOnLoyalty(Customer customer) {
    return customer.getLoyaltyYears() > 5 
        ? PricingTier.LOYAL 
        : PricingTier.STANDARD;
}
```

### 2. Switch Expressions with Function Extraction

```java
Result<ShippingOption> determineShippingOption(Order order) {
    return Result.success(
        switch(order.getDeliveryRegion()) {
            case DOMESTIC -> determineDomesticShipping(order);
            case CONTINENTAL -> determineContinetalShipping(order);
            case INTERNATIONAL -> determineInternationalShipping(order);
        }
    );
}

private ShippingOption determineDomesticShipping(Order order) {
    return order.isExpedited() 
        ? ShippingOption.EXPRESS_DOMESTIC 
        : ShippingOption.STANDARD_DOMESTIC;
}

private ShippingOption determineContinetalShipping(Order order) {
    return order.getTotalWeight() > 10 
        ? ShippingOption.FREIGHT_CONTINENTAL 
        : ShippingOption.STANDARD_CONTINENTAL;
}

private ShippingOption determineInternationalShipping(Order order) {
    return order.hasRestricted() 
        ? ShippingOption.SPECIAL_HANDLING_INTERNATIONAL 
        : ShippingOption.STANDARD_INTERNATIONAL;
}
```

### 3. Pattern Matching with Extracted Methods

```java
Result<TaxCalculation> calculateTax(Transaction transaction) {
    return Result.lift(() ->
        switch(transaction) {
            case ProductPurchase p -> calculateProductTax(p);
            case ServicePurchase s -> calculateServiceTax(s);
            case Subscription s -> calculateSubscriptionTax(s);
            default -> handleUnsupportedTransaction(transaction);
        }
    );
}

private TaxCalculation calculateProductTax(ProductPurchase purchase) {
    return new ProductTaxCalculation(purchase.getAmount(), purchase.getCategory().getTaxRate());
}

private TaxCalculation calculateServiceTax(ServicePurchase purchase) {
    return new ServiceTaxCalculation(purchase.getAmount(), purchase.isB2B());
}

private TaxCalculation calculateSubscriptionTax(Subscription subscription) {
    return new SubscriptionTaxCalculation(subscription.getMonthlyAmount(), subscription.getDurationMonths());
}

private TaxCalculation handleUnsupportedTransaction(Transaction transaction) {
    throw new UnsupportedTransactionException("Unknown transaction type");
}
```

### 4. Conditional Sequencing with Extracted Functions

```java
Promise<AccountStatus> checkAccountStatus(String accountId) {
    return accountRepository.findById(accountId)
        .filter(
            account -> account != null,
            () -> new AccountNotFoundException("Account not found: " + accountId)
        )
        .flatMap(account -> 
            account.isLocked() 
                ? Promise.success(AccountStatus.LOCKED)
                : determineAccountStatusByState(account)
        );
}

private Promise<AccountStatus> determineAccountStatusByState(Account account) {
    return account.getBalance() < 0 
        ? Promise.success(AccountStatus.OVERDRAWN)
        : determineAccountStatusByActivity(account);
}

private Promise<AccountStatus> determineAccountStatusByActivity(Account account) {
    return retrieveRecentTransactions(account)
        .map(this::categorizeAccountByTransactionVolume);
}

private AccountStatus categorizeAccountByTransactionVolume(List<Transaction> transactions) {
    return transactions.size() > 10 
        ? AccountStatus.ACTIVE 
        : AccountStatus.INACTIVE;
}
```

### 5. Complex Decision Tree with Single Level of Abstraction

```java
Result<ShipmentStatus> processFulfillment(Order order) {
    return inventoryService.checkAvailability(order.getItems())
        .flatMap(availability -> {
            if (availability.allAvailable()) {
                return createFullShipment(order);
            } else if (availability.partiallyAvailable() && order.allowsPartialShipment()) {
                return createPartialShipmentWithBackorder(order, availability);
            } else {
                return createFullBackorder(order);
            }
        });
}

private Result<ShipmentStatus> createFullShipment(Order order) {
    return warehouseService.createShipment(order)
        .map(shipment -> new ShipmentStatus(ShipmentState.PROCESSING, shipment));
}

private Result<ShipmentStatus> createPartialShipmentWithBackorder(Order order, Availability availability) {
    return warehouseService.createPartialShipment(order, availability.getAvailableItems())
        .flatMap(partialShipment -> 
            backorderService.createBackorder(order, availability.getUnavailableItems())
                .map(backorder -> new ShipmentStatus(ShipmentState.PARTIAL, partialShipment, backorder))
        );
}

private Result<ShipmentStatus> createFullBackorder(Order order) {
    return backorderService.createBackorder(order, order.getItems())
        .map(backorder -> new ShipmentStatus(ShipmentState.BACKORDERED, backorder));
}
```

### 6. Conditional Aspect Application with Extracted Functions

```java
Promise<Document> getDocument(String documentId, User user) {
    Promise<Document> baseOperation = fetchDocumentById(documentId);
    
    return user.isAdmin()
        ? baseOperation  // No additional aspects for admins
        : applyUserSpecificAspects(baseOperation, user);
}

private Promise<Document> fetchDocumentById(String documentId) {
    return documentRepository.findById(documentId)
        .filter(
            doc -> doc != null,
            () -> new DocumentNotFoundException("Document not found: " + documentId)
        );
}

private Promise<Document> applyUserSpecificAspects(Promise<Document> operation, User user) {
    return user.hasPremiumAccess()
        ? applyCacheAspect(operation, user)
        : applyRateLimitAndCacheAspects(operation, user);
}

private Promise<Document> applyCacheAspect(Promise<Document> operation, User user) {
    return cacheAspect("doc:" + user.getId()).apply(operation);
}

private Promise<Document> applyRateLimitAndCacheAspects(Promise<Document> operation, User user) {
    return rateLimitAspect(user.getId())
        .andThen(cacheAspect("doc:" + user.getId()))
        .apply(operation);
}
```

### 7. Conditional With Result Transformation

```java
Result<UserResponse> processUserRequest(UserRequest request) {
    return validateRequest(request)
        .flatMap(validRequest -> 
            validRequest.isAdminRequest() 
                ? processAdminRequest(validRequest)
                : processStandardRequest(validRequest)
        );
}

private Result<UserResponse> processAdminRequest(UserRequest request) {
    return adminService.executePrivilegedOperation(request)
        .map(result -> UserResponse.withAdminPrivileges(result));
}

private Result<UserResponse> processStandardRequest(UserRequest request) {
    return userService.executeStandardOperation(request)
        .map(result -> UserResponse.standard(result));
}
```

## Integration with Other Patterns

The Condition pattern works seamlessly with other PFJ patterns, maintaining single level of abstraction:

```java
// Condition with Sequencer - single level of abstraction
Result<OrderConfirmation> processOrder(Order order) {
    return Sequencer.start(order)
        .then(this::validateOrder)
        .then(validOrder -> 
            validOrder.isPrePaid()
                ? processPrePaidOrder(validOrder)
                : processStandardOrder(validOrder)
        )
        .end();
}

private Sequencer<Order, Order> processPrePaidOrder(Order order) {
    return Sequencer.next(order)
        .then(this::allocateInventory)
        .then(this::generateConfirmation);
}

private Sequencer<Order, Order> processStandardOrder(Order order) {
    return Sequencer.next(order)
        .then(this::processPayment)
        .then(this::allocateInventory)
        .then(this::generateConfirmation);
}
```

## Why This Pattern is Powerful

1. **Clean Abstraction Levels**: Each method maintains a single level of abstraction
2. **Improved Readability**: Breaking complex conditions into named functions clarifies intent
3. **Better Testability**: Smaller functions with clear responsibilities are easier to test
4. **Reduced Complexity**: Function extraction prevents deep nesting of conditions
5. **Enhanced Maintainability**: Changes to business logic are isolated to specific functions

The Condition pattern in PFJ emphasizes maintaining a single level of abstraction in each method, promoting code organization where complex decision trees are broken down into well-named helper methods. This approach significantly improves readability and maintainability while preserving the functional composition benefits.

User:
check example for 5: flatMap parameter is second level of abstraction too. Single level of abstraction is applied everywhere in function implementation.

Assistant:
You're absolutely right. Let me correct example #5 to ensure we maintain a strict single level of abstraction throughout all examples, including the `flatMap` implementation.

# Revised Example 5: Complex Decision Tree with Single Level of Abstraction

```java
Result<ShipmentStatus> processFulfillment(Order order) {
    return inventoryService.checkAvailability(order.getItems())
        .flatMap(availability -> decideFulfillmentStrategy(order, availability));
}

private Result<ShipmentStatus> decideFulfillmentStrategy(Order order, Availability availability) {
    if (availability.allAvailable()) {
        return createFullShipment(order);
    } else if (availability.partiallyAvailable() && order.allowsPartialShipment()) {
        return createPartialShipmentWithBackorder(order, availability);
    } else {
        return createFullBackorder(order);
    }
}

private Result<ShipmentStatus> createFullShipment(Order order) {
    return warehouseService.createShipment(order)
        .map(shipment -> new ShipmentStatus(ShipmentState.PROCESSING, shipment));
}

private Result<ShipmentStatus> createPartialShipmentWithBackorder(Order order, Availability availability) {
    return warehouseService.createPartialShipment(order, availability.getAvailableItems())
        .flatMap(partialShipment -> createBackorderForPartialShipment(order, availability, partialShipment));
}

private Result<ShipmentStatus> createBackorderForPartialShipment(
        Order order, 
        Availability availability, 
        Shipment partialShipment) {
    return backorderService.createBackorder(order, availability.getUnavailableItems())
        .map(backorder -> new ShipmentStatus(ShipmentState.PARTIAL, partialShipment, backorder));
}

private Result<ShipmentStatus> createFullBackorder(Order order) {
    return backorderService.createBackorder(order, order.getItems())
        .map(backorder -> new ShipmentStatus(ShipmentState.BACKORDERED, backorder));
}
```

Thank you for pointing this out. This revision now properly maintains a single level of abstraction everywhere, including inside the `flatMap` operation. Each method has exactly one responsibility and implements a single pattern, making the code more maintainable and easier to reason about.

The key change was extracting the condition logic from inside the `flatMap` parameter into its own dedicated method `decideFulfillmentStrategy`, and further extracting the nested transformation in the partial shipment case to its own method `createBackorderForPartialShipment`. This way, each method follows the single level of abstraction principle.

User:
Next pattern: Iteration. It performs various kinds of iteration: repetition for number of times (like operator 'for'), iteration over passed collection, retrying same operation until success and fail if no success after all repetition, etc.

Assistant:
# Iteration Pattern in PFJ

## Core Concept

The Iteration pattern provides a functional approach to repetitive operations, transforming traditional imperative loops into composable, declarative expressions. This pattern handles various types of iteration scenarios while maintaining the functional paradigm of immutability and side-effect-free operations.

## Implementation in Pragmatica Lite Core

The Iteration pattern in PFJ leverages functional interfaces and monadic types to express different iteration scenarios:

```java
// Collection iteration using map
Result<List<ProductDTO>> convertProducts(List<Product> products) {
    return Result.success(products)
        .map(productList -> productList.stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList())
        );
}

// Retry operation until success (or max attempts)
Result<Connection> establishConnection(String endpoint) {
    return Retry.withMaxAttempts(5)
        .withBackoff(Duration.ofMillis(100), 2.0) // Exponential backoff
        .execute(() -> connectToEndpoint(endpoint));
}
```

## Key Characteristics

1. **Declarative Approach**: Focuses on "what" to iterate rather than "how"
2. **Immutability**: Preserves immutability of data structures during iteration
3. **Composability**: Easily composes with other patterns
4. **Error Handling**: Integrated error handling with monadic types
5. **Single Level of Abstraction**: Each method implements exactly one iteration pattern

## Common Iteration Patterns

### 1. Collection Transformation

```java
Result<TaxReport> calculateTaxes(List<Invoice> invoices) {
    return Result.success(invoices)
        .map(this::processInvoicesForTax)
        .map(this::createTaxReport);
}

private List<TaxEntry> processInvoicesForTax(List<Invoice> invoices) {
    return invoices.stream()
        .map(this::calculateTaxForInvoice)
        .collect(Collectors.toList());
}

private TaxEntry calculateTaxForInvoice(Invoice invoice) {
    double taxAmount = invoice.getTotal() * getTaxRate(invoice.getRegion());
    return new TaxEntry(invoice.getId(), taxAmount, invoice.getRegion());
}

private TaxReport createTaxReport(List<TaxEntry> entries) {
    double totalTax = entries.stream().mapToDouble(TaxEntry::getAmount).sum();
    return new TaxReport(entries, totalTax, LocalDate.now());
}
```

### 2. Retry Pattern

```java
Result<DataFeed> fetchExternalData(String source) {
    return retryFetchOperation(source)
        .map(this::parseDataFeed);
}

private Result<String> retryFetchOperation(String source) {
    return Retry.withMaxAttempts(3)
        .withBackoff(Duration.ofSeconds(1), 2.0)
        .withErrorFilter(this::isRetryableError)
        .execute(() -> fetchFromExternalSource(source));
}

private boolean isRetryableError(Throwable error) {
    return error instanceof TimeoutException || 
           error instanceof ConnectionException ||
           (error instanceof HttpException && ((HttpException)error).getStatusCode() >= 500);
}

private Result<String> fetchFromExternalSource(String source) {
    return Result.lift(() -> externalApiClient.fetch(source));
}

private DataFeed parseDataFeed(String rawData) {
    return DataFeed.fromJson(rawData);
}
```

### 3. Paginated Data Retrieval

```java
Result<List<Customer>> getAllCustomers() {
    return fetchAllCustomerPages(0, new ArrayList<>());
}

private Result<List<Customer>> fetchAllCustomerPages(int page, List<Customer> accumulated) {
    return fetchCustomerPage(page)
        .flatMap(pageResult -> processPageResult(pageResult, page, accumulated));
}

private Result<List<Customer>> processPageResult(PageResult<Customer> pageResult, int currentPage, List<Customer> accumulated) {
    List<Customer> updatedList = new ArrayList<>(accumulated);
    updatedList.addAll(pageResult.getItems());
    
    return pageResult.hasMorePages()
        ? fetchAllCustomerPages(currentPage + 1, updatedList)
        : Result.success(updatedList);
}

private Result<PageResult<Customer>> fetchCustomerPage(int page) {
    return Result.lift(() -> customerRepository.getPage(page, PAGE_SIZE));
}
```

### 4. Batch Processing

```java
Result<BatchSummary> processBatch(List<Transaction> transactions) {
    return Result.success(transactions)
        .map(this::partitionIntoBatches)
        .flatMap(this::processAllBatches)
        .map(this::createBatchSummary);
}

private List<List<Transaction>> partitionIntoBatches(List<Transaction> transactions) {
    return ListUtils.partition(transactions, BATCH_SIZE);
}

private Result<List<BatchResult>> processAllBatches(List<List<Transaction>> batches) {
    return collectResults(batches.stream()
        .map(this::processSingleBatch)
        .collect(Collectors.toList()));
}

private Result<BatchResult> processSingleBatch(List<Transaction> batch) {
    return transactionProcessor.process(batch)
        .map(results -> new BatchResult(batch.size(), results));
}

private Result<List<BatchResult>> collectResults(List<Result<BatchResult>> results) {
    // Implementation to collect all results or fail if any failed
    return Result.collect(results);
}

private BatchSummary createBatchSummary(List<BatchResult> batchResults) {
    int totalProcessed = batchResults.stream()
        .mapToInt(BatchResult::getProcessedCount)
        .sum();
    
    return new BatchSummary(totalProcessed, batchResults.size());
}
```

### 5. Recursive Operations

```java
Result<DirectoryContents> scanDirectory(Path directory) {
    return validateDirectory(directory)
        .flatMap(this::readDirectoryContents);
}

private Result<Path> validateDirectory(Path directory) {
    return Result.lift(() -> {
        if (!Files.exists(directory)) {
            throw new DirectoryNotFoundException("Directory not found: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new NotDirectoryException("Path is not a directory: " + directory);
        }
        return directory;
    });
}

private Result<DirectoryContents> readDirectoryContents(Path directory) {
    return Result.lift(() -> Files.list(directory))
        .flatMap(this::processPathStream);
}

private Result<DirectoryContents> processPathStream(Stream<Path> paths) {
    try (paths) {
        List<Path> pathList = paths.collect(Collectors.toList());
        return buildDirectoryContents(pathList);
    }
}

private Result<DirectoryContents> buildDirectoryContents(List<Path> paths) {
    List<FileInfo> files = new ArrayList<>();
    List<Result<DirectoryContents>> subDirectories = new ArrayList<>();
    
    for (Path path : paths) {
        if (Files.isRegularFile(path)) {
            files.add(createFileInfo(path));
        } else if (Files.isDirectory(path)) {
            subDirectories.add(scanDirectory(path));
        }
    }
    
    return Result.collect(subDirectories)
        .map(subdirs -> createDirectoryContents(files, subdirs));
}

private FileInfo createFileInfo(Path file) {
    try {
        long size = Files.size(file);
        String contentType = Files.probeContentType(file);
        return new FileInfo(file.getFileName().toString(), size, contentType);
    } catch (IOException e) {
        return new FileInfo(file.getFileName().toString(), -1, "unknown");
    }
}

private DirectoryContents createDirectoryContents(List<FileInfo> files, List<DirectoryContents> subdirectories) {
    return new DirectoryContents(files, subdirectories);
}
```

### 6. Time-based Polling

```java
Promise<JobResult> waitForJobCompletion(String jobId) {
    return pollUntilCompleted(jobId, 0, MAX_POLLS);
}

private Promise<JobResult> pollUntilCompleted(String jobId, int attemptsMade, int maxAttempts) {
    if (attemptsMade >= maxAttempts) {
        return Promise.failure(new TimeoutException("Job didn't complete within allocated time"));
    }
    
    return checkJobStatus(jobId)
        .flatMap(status -> handleJobStatus(jobId, status, attemptsMade, maxAttempts));
}

private Promise<JobStatus> checkJobStatus(String jobId) {
    return Promise.lift(() -> jobService.getStatus(jobId));
}

private Promise<JobResult> handleJobStatus(String jobId, JobStatus status, int attemptsMade, int maxAttempts) {
    switch (status.getState()) {
        case COMPLETED:
            return fetchJobResult(jobId);
            
        case FAILED:
            return Promise.failure(new JobFailedException("Job failed: " + status.getErrorMessage()));
            
        case RUNNING:
        case QUEUED:
            return delayAndRetry(jobId, attemptsMade, maxAttempts);
            
        default:
            return Promise.failure(new UnexpectedJobStateException("Unexpected job state: " + status.getState()));
    }
}

private Promise<JobResult> fetchJobResult(String jobId) {
    return Promise.lift(() -> jobService.getResult(jobId));
}

private Promise<JobResult> delayAndRetry(String jobId, int attemptsMade, int maxAttempts) {
    return Promise.delay(Duration.ofSeconds(POLL_INTERVAL_SECONDS))
        .flatMap(ignored -> pollUntilCompleted(jobId, attemptsMade + 1, maxAttempts));
}
```

### 7. Parallel Iteration

```java
Result<AggregateReport> generateReports(List<Department> departments) {
    return parallelProcessDepartments(departments)
        .map(this::combineReports);
}

private Result<List<DepartmentReport>> parallelProcessDepartments(List<Department> departments) {
    // Create a list of individual report generation tasks
    List<Result<DepartmentReport>> reportTasks = departments.stream()
        .map(this::generateDepartmentReport)
        .collect(Collectors.toList());
    
    // Execute all tasks and collect results
    return Result.collect(reportTasks);
}

private Result<DepartmentReport> generateDepartmentReport(Department department) {
    return Result.lift(() -> reportGenerator.createReport(department));
}

private AggregateReport combineReports(List<DepartmentReport> reports) {
    double totalExpenses = reports.stream()
        .mapToDouble(DepartmentReport::getTotalExpenses)
        .sum();
    
    double totalRevenue = reports.stream()
        .mapToDouble(DepartmentReport::getTotalRevenue)
        .sum();
    
    return new AggregateReport(reports, totalExpenses, totalRevenue);
}
```

## Integration with Other Patterns

The Iteration pattern seamlessly integrates with other PFJ patterns:

### Iteration with Condition

```java
Result<ProcessingSummary> processItemsByType(List<Item> items) {
    return Result.success(items)
        .map(this::categorizeItems)
        .flatMap(this::processItemsByCategory);
}

private Map<ItemType, List<Item>> categorizeItems(List<Item> items) {
    return items.stream()
        .collect(Collectors.groupingBy(Item::getType));
}

private Result<ProcessingSummary> processItemsByCategory(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> categoryResults = new ArrayList<>();
    
    for (Map.Entry<ItemType, List<Item>> entry : categorizedItems.entrySet()) {
        ItemType type = entry.getKey();
        List<Item> itemsOfType = entry.getValue();
        
        Result<CategoryResult> result = processCategoryItems(type, itemsOfType);
        categoryResults.add(result);
    }
    
    return Result.collect(categoryResults)
        .map(this::buildSummary);
}

private Result<CategoryResult> processCategoryItems(ItemType type, List<Item> items) {
    return selectProcessingStrategy(type)
        .flatMap(strategy -> strategy.process(items))
        .map(results -> new CategoryResult(type, results));
}

private Result<ProcessingStrategy> selectProcessingStrategy(ItemType type) {
    return switch (type) {
        case DIGITAL -> Result.success(new DigitalItemProcessor());
        case PHYSICAL -> Result.success(new PhysicalItemProcessor());
        case SUBSCRIPTION -> Result.success(new SubscriptionProcessor());
        default -> Result.failure(Causes.of("Unknown item type: " + type));
    };
}

private ProcessingSummary buildSummary(List<CategoryResult> results) {
    int totalProcessed = results.stream()
        .mapToInt(result -> result.getResults().size())
        .sum();
    
    Map<ItemType, Integer> countsByType = results.stream()
        .collect(Collectors.toMap(
            CategoryResult::getType,
            result -> result.getResults().size()
        ));
    
    return new ProcessingSummary(totalProcessed, countsByType, results);
}
```

### Iteration with Aspect

```java
Result<List<TransformedData>> processWithMetrics(List<RawData> dataItems) {
    // Apply timing aspect to the batch process
    return timingAspect("batch-processing")
        .apply(processBatch(dataItems));
}

private Result<List<TransformedData>> processBatch(List<RawData> items) {
    return Result.success(items)
        .flatMap(this::transformItems);
}

private Result<List<TransformedData>> transformItems(List<RawData> items) {
    List<Result<TransformedData>> transformations = items.stream()
        // Apply individual item tracking aspect
        .map(item -> trackingAspect("item-" + item.getId())
            .apply(transformSingleItem(item)))
        .collect(Collectors.toList());
    
    return Result.collect(transformations);
}

private Result<TransformedData> transformSingleItem(RawData item) {
    return dataTransformer.transform(item);
}

private <T> Aspect<Result<T>> timingAspect(String operation) {
    return result -> {
        long startTime = System.currentTimeMillis();
        
        return result.map(value -> {
            long endTime = System.currentTimeMillis();
            metricsService.recordTiming(operation, endTime - startTime);
            return value;
        }).mapFailure(error -> {
            long endTime = System.currentTimeMillis();
            metricsService.recordFailedTiming(operation, endTime - startTime);
            return error;
        });
    };
}

private <T> Aspect<Result<T>> trackingAspect(String itemId) {
    return result -> {
        metricsService.incrementProcessed(itemId);
        
        return result.mapFailure(error -> {
            metricsService.incrementFailed(itemId);
            return error;
        });
    };
}
```

## Why This Pattern is Powerful

1. **Controlled Side Effects**: Iteration occurs within pure functions
2. **Error Handling**: Failures at any point are propagated through the monadic structure
3. **Composition**: Iterations can be easily composed with other operations
4. **Parallelization**: Facilitates parallel processing where appropriate
5. **Single Responsibility**: Each method has exactly one purpose
6. **Testability**: Pure functions with clear inputs and outputs make testing easier

The Iteration pattern transforms traditional imperative loops into functional expressions, making your code more predictable, composable, and maintainable. By maintaining a single level of abstraction in each method, the pattern preserves readability while enabling complex iteration scenarios to be expressed clearly and concisely.

User:
Check examples for single level of abstractions.

Assistant:
You're right to check for this important principle. Let me review each example and ensure that all of them strictly adhere to the single level of abstraction principle.

# Revised Iteration Pattern Examples

## 1. Collection Transformation

This example maintains a single level of abstraction in each method:

```java
Result<TaxReport> calculateTaxes(List<Invoice> invoices) {
    return Result.success(invoices)
        .map(this::processInvoicesForTax)
        .map(this::createTaxReport);
}

private List<TaxEntry> processInvoicesForTax(List<Invoice> invoices) {
    return invoices.stream()
        .map(this::calculateTaxForInvoice)
        .collect(Collectors.toList());
}

private TaxEntry calculateTaxForInvoice(Invoice invoice) {
    double taxAmount = invoice.getTotal() * getTaxRate(invoice.getRegion());
    return new TaxEntry(invoice.getId(), taxAmount, invoice.getRegion());
}

private TaxReport createTaxReport(List<TaxEntry> entries) {
    double totalTax = entries.stream().mapToDouble(TaxEntry::getAmount).sum();
    return new TaxReport(entries, totalTax, LocalDate.now());
}
```

## 2. Retry Pattern

The example correctly maintains a single level of abstraction:

```java
Result<DataFeed> fetchExternalData(String source) {
    return retryFetchOperation(source)
        .map(this::parseDataFeed);
}

private Result<String> retryFetchOperation(String source) {
    return Retry.withMaxAttempts(3)
        .withBackoff(Duration.ofSeconds(1), 2.0)
        .withErrorFilter(this::isRetryableError)
        .execute(() -> fetchFromExternalSource(source));
}

private boolean isRetryableError(Throwable error) {
    return error instanceof TimeoutException || 
           error instanceof ConnectionException ||
           (error instanceof HttpException && ((HttpException)error).getStatusCode() >= 500);
}

private Result<String> fetchFromExternalSource(String source) {
    return Result.lift(() -> externalApiClient.fetch(source));
}

private DataFeed parseDataFeed(String rawData) {
    return DataFeed.fromJson(rawData);
}
```

## 3. Paginated Data Retrieval

This example needs a revision to ensure single level of abstraction in `processPageResult`:

```java
Result<List<Customer>> getAllCustomers() {
    return fetchAllCustomerPages(0, new ArrayList<>());
}

private Result<List<Customer>> fetchAllCustomerPages(int page, List<Customer> accumulated) {
    return fetchCustomerPage(page)
        .flatMap(pageResult -> processPageResult(pageResult, page, accumulated));
}

private Result<List<Customer>> processPageResult(PageResult<Customer> pageResult, int currentPage, List<Customer> accumulated) {
    List<Customer> updatedList = combineResults(accumulated, pageResult.getItems());
    
    if (pageResult.hasMorePages()) {
        return fetchAllCustomerPages(currentPage + 1, updatedList);
    } else {
        return Result.success(updatedList);
    }
}

private List<Customer> combineResults(List<Customer> existing, List<Customer> newItems) {
    List<Customer> combined = new ArrayList<>(existing);
    combined.addAll(newItems);
    return combined;
}

private Result<PageResult<Customer>> fetchCustomerPage(int page) {
    return Result.lift(() -> customerRepository.getPage(page, PAGE_SIZE));
}
```

## 4. Batch Processing

This example correctly follows the single level of abstraction:

```java
Result<BatchSummary> processBatch(List<Transaction> transactions) {
    return Result.success(transactions)
        .map(this::partitionIntoBatches)
        .flatMap(this::processAllBatches)
        .map(this::createBatchSummary);
}

private List<List<Transaction>> partitionIntoBatches(List<Transaction> transactions) {
    return ListUtils.partition(transactions, BATCH_SIZE);
}

private Result<List<BatchResult>> processAllBatches(List<List<Transaction>> batches) {
    return collectResults(batches.stream()
        .map(this::processSingleBatch)
        .collect(Collectors.toList()));
}

private Result<BatchResult> processSingleBatch(List<Transaction> batch) {
    return transactionProcessor.process(batch)
        .map(results -> new BatchResult(batch.size(), results));
}

private Result<List<BatchResult>> collectResults(List<Result<BatchResult>> results) {
    return Result.collect(results);
}

private BatchSummary createBatchSummary(List<BatchResult> batchResults) {
    int totalProcessed = batchResults.stream()
        .mapToInt(BatchResult::getProcessedCount)
        .sum();
    
    return new BatchSummary(totalProcessed, batchResults.size());
}
```

## 5. Recursive Operations

The `buildDirectoryContents` method combines too many responsibilities. Here's a revised version:

```java
Result<DirectoryContents> scanDirectory(Path directory) {
    return validateDirectory(directory)
        .flatMap(this::readDirectoryContents);
}

private Result<Path> validateDirectory(Path directory) {
    return Result.lift(() -> {
        if (!Files.exists(directory)) {
            throw new DirectoryNotFoundException("Directory not found: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new NotDirectoryException("Path is not a directory: " + directory);
        }
        return directory;
    });
}

private Result<DirectoryContents> readDirectoryContents(Path directory) {
    return Result.lift(() -> Files.list(directory))
        .flatMap(this::processPathStream);
}

private Result<DirectoryContents> processPathStream(Stream<Path> paths) {
    try (paths) {
        List<Path> pathList = paths.collect(Collectors.toList());
        return categorizeAndProcessPaths(pathList);
    }
}

private Result<DirectoryContents> categorizeAndProcessPaths(List<Path> paths) {
    PathCategorization categorization = categorizePaths(paths);
    return processSubdirectories(categorization.directories)
        .map(subdirs -> createDirectoryContents(categorization.files, subdirs));
}

private PathCategorization categorizePaths(List<Path> paths) {
    List<FileInfo> files = new ArrayList<>();
    List<Path> directories = new ArrayList<>();
    
    for (Path path : paths) {
        if (Files.isRegularFile(path)) {
            files.add(createFileInfo(path));
        } else if (Files.isDirectory(path)) {
            directories.add(path);
        }
    }
    
    return new PathCategorization(files, directories);
}

private Result<List<DirectoryContents>> processSubdirectories(List<Path> directories) {
    List<Result<DirectoryContents>> subdirResults = directories.stream()
        .map(this::scanDirectory)
        .collect(Collectors.toList());
    
    return Result.collect(subdirResults);
}

private FileInfo createFileInfo(Path file) {
    try {
        long size = Files.size(file);
        String contentType = Files.probeContentType(file);
        return new FileInfo(file.getFileName().toString(), size, contentType);
    } catch (IOException e) {
        return new FileInfo(file.getFileName().toString(), -1, "unknown");
    }
}

private DirectoryContents createDirectoryContents(List<FileInfo> files, List<DirectoryContents> subdirectories) {
    return new DirectoryContents(files, subdirectories);
}

// Helper class to hold categorized paths
private static class PathCategorization {
    final List<FileInfo> files;
    final List<Path> directories;
    
    PathCategorization(List<FileInfo> files, List<Path> directories) {
        this.files = files;
        this.directories = directories;
    }
}
```

## 6. Time-based Polling

The `handleJobStatus` method can be better organized to maintain a single level of abstraction:

```java
Promise<JobResult> waitForJobCompletion(String jobId) {
    return pollUntilCompleted(jobId, 0, MAX_POLLS);
}

private Promise<JobResult> pollUntilCompleted(String jobId, int attemptsMade, int maxAttempts) {
    if (attemptsMade >= maxAttempts) {
        return createTimeoutFailure();
    }
    
    return checkJobStatus(jobId)
        .flatMap(status -> handleJobStatus(jobId, status, attemptsMade, maxAttempts));
}

private Promise<JobResult> createTimeoutFailure() {
    return Promise.failure(new TimeoutException("Job didn't complete within allocated time"));
}

private Promise<JobStatus> checkJobStatus(String jobId) {
    return Promise.lift(() -> jobService.getStatus(jobId));
}

private Promise<JobResult> handleJobStatus(String jobId, JobStatus status, int attemptsMade, int maxAttempts) {
    switch (status.getState()) {
        case COMPLETED:
            return fetchJobResult(jobId);
            
        case FAILED:
            return createJobFailureResult(status);
            
        case RUNNING:
        case QUEUED:
            return delayAndRetry(jobId, attemptsMade, maxAttempts);
            
        default:
            return createUnexpectedStateFailure(status);
    }
}

private Promise<JobResult> createJobFailureResult(JobStatus status) {
    return Promise.failure(new JobFailedException("Job failed: " + status.getErrorMessage()));
}

private Promise<JobResult> createUnexpectedStateFailure(JobStatus status) {
    return Promise.failure(new UnexpectedJobStateException("Unexpected job state: " + status.getState()));
}

private Promise<JobResult> fetchJobResult(String jobId) {
    return Promise.lift(() -> jobService.getResult(jobId));
}

private Promise<JobResult> delayAndRetry(String jobId, int attemptsMade, int maxAttempts) {
    return Promise.delay(Duration.ofSeconds(POLL_INTERVAL_SECONDS))
        .flatMap(ignored -> pollUntilCompleted(jobId, attemptsMade + 1, maxAttempts));
}
```

## 7. Parallel Iteration

This example correctly follows the single level of abstraction principle:

```java
Result<AggregateReport> generateReports(List<Department> departments) {
    return parallelProcessDepartments(departments)
        .map(this::combineReports);
}

private Result<List<DepartmentReport>> parallelProcessDepartments(List<Department> departments) {
    List<Result<DepartmentReport>> reportTasks = departments.stream()
        .map(this::generateDepartmentReport)
        .collect(Collectors.toList());
    
    return Result.collect(reportTasks);
}

private Result<DepartmentReport> generateDepartmentReport(Department department) {
    return Result.lift(() -> reportGenerator.createReport(department));
}

private AggregateReport combineReports(List<DepartmentReport> reports) {
    double totalExpenses = calculateTotalExpenses(reports);
    double totalRevenue = calculateTotalRevenue(reports);
    
    return new AggregateReport(reports, totalExpenses, totalRevenue);
}

private double calculateTotalExpenses(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalExpenses)
        .sum();
}

private double calculateTotalRevenue(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalRevenue)
        .sum();
}
```

## Integration with Other Patterns - Revised Example

The `processItemsByCategory` method combines too many responsibilities:

```java
Result<ProcessingSummary> processItemsByType(List<Item> items) {
    return Result.success(items)
        .map(this::categorizeItems)
        .flatMap(this::processItemsByCategory);
}

private Map<ItemType, List<Item>> categorizeItems(List<Item> items) {
    return items.stream()
        .collect(Collectors.groupingBy(Item::getType));
}

private Result<ProcessingSummary> processItemsByCategory(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> categoryResults = createCategoryResults(categorizedItems);
    return collectCategoryResults(categoryResults);
}

private List<Result<CategoryResult>> createCategoryResults(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> results = new ArrayList<>();
    
    for (Map.Entry<ItemType, List<Item>> entry : categorizedItems.entrySet()) {
        ItemType type = entry.getKey();
        List<Item> itemsOfType = entry.getValue();
        
        Result<CategoryResult> result = processCategoryItems(type, itemsOfType);
        results.add(result);
    }
    
    return results;
}

private Result<ProcessingSummary> collectCategoryResults(List<Result<CategoryResult>> categoryResults) {
    return Result.collect(categoryResults)
        .map(this::buildSummary);
}

private Result<CategoryResult> processCategoryItems(ItemType type, List<Item> items) {
    return selectProcessingStrategy(type)
        .flatMap(strategy -> strategy.process(items))
        .map(results -> new CategoryResult(type, results));
}

private Result<ProcessingStrategy> selectProcessingStrategy(ItemType type) {
    return switch (type) {
        case DIGITAL -> Result.success(new DigitalItemProcessor());
        case PHYSICAL -> Result.success(new PhysicalItemProcessor());
        case SUBSCRIPTION -> Result.success(new SubscriptionProcessor());
        default -> Result.failure(Causes.of("Unknown item type: " + type));
    };
}

private ProcessingSummary buildSummary(List<CategoryResult> results) {
    int totalProcessed = calculateTotalProcessed(results);
    Map<ItemType, Integer> countsByType = calculateCountsByType(results);
    
    return new ProcessingSummary(totalProcessed, countsByType, results);
}

private int calculateTotalProcessed(List<CategoryResult> results) {
    return results.stream()
        .mapToInt(result -> result.getResults().size())
        .sum();
}

private Map<ItemType, Integer> calculateCountsByType(List<CategoryResult> results) {
    return results.stream()
        .collect(Collectors.toMap(
            CategoryResult::getType,
            result -> result.getResults().size()
        ));
}
```

## Summary of Revisions

1. **Paginated Data Retrieval**: Extracted the list combination logic into a separate `combineResults` method
2. **Recursive Operations**: Split the complex `buildDirectoryContents` method into multiple methods with clear responsibilities
3. **Time-based Polling**: Added separate methods for creating failure results
4. **Parallel Iteration**: Extracted the calculations for expenses and revenue into dedicated methods
5. **Integration Example**: Split the complex category processing into smaller, focused methods

These revisions ensure that each method is focused on exactly one level of abstraction, making the code more maintainable, testable, and readable. Each method now has a single responsibility and implements a single pattern, adhering to the core principle of the PFJ approach.

User:
Try once again, please. I see 'createTaxReport' implementation violates the single level of abstraction requirement.

Assistant:
You're absolutely right. I apologize for missing this. Let's examine `createTaxReport` more carefully and revise all examples once more to ensure strict adherence to the single level of abstraction principle.

# Revised Iteration Pattern Examples with Strict Single Level of Abstraction

## 1. Collection Transformation - Revised

```java
Result<TaxReport> calculateTaxes(List<Invoice> invoices) {
    return Result.success(invoices)
        .map(this::processInvoicesForTax)
        .map(this::createTaxReport);
}

private List<TaxEntry> processInvoicesForTax(List<Invoice> invoices) {
    return invoices.stream()
        .map(this::calculateTaxForInvoice)
        .collect(Collectors.toList());
}

private TaxEntry calculateTaxForInvoice(Invoice invoice) {
    double taxAmount = invoice.getTotal() * getTaxRate(invoice.getRegion());
    return new TaxEntry(invoice.getId(), taxAmount, invoice.getRegion());
}

private TaxReport createTaxReport(List<TaxEntry> entries) {
    double totalTax = calculateTotalTax(entries);
    return new TaxReport(entries, totalTax, LocalDate.now());
}

private double calculateTotalTax(List<TaxEntry> entries) {
    return entries.stream()
        .mapToDouble(TaxEntry::getAmount)
        .sum();
}
```

You were correct - the `createTaxReport` method was calculating the total tax and creating a report, violating the single level of abstraction principle. I've extracted the tax calculation to a dedicated method.

## 2. Retry Pattern

This example looks correct, with each method having a single responsibility:

```java
Result<DataFeed> fetchExternalData(String source) {
    return retryFetchOperation(source)
        .map(this::parseDataFeed);
}

private Result<String> retryFetchOperation(String source) {
    return Retry.withMaxAttempts(3)
        .withBackoff(Duration.ofSeconds(1), 2.0)
        .withErrorFilter(this::isRetryableError)
        .execute(() -> fetchFromExternalSource(source));
}

private boolean isRetryableError(Throwable error) {
    return error instanceof TimeoutException || 
           error instanceof ConnectionException ||
           (error instanceof HttpException && ((HttpException)error).getStatusCode() >= 500);
}

private Result<String> fetchFromExternalSource(String source) {
    return Result.lift(() -> externalApiClient.fetch(source));
}

private DataFeed parseDataFeed(String rawData) {
    return DataFeed.fromJson(rawData);
}
```

## 3. Paginated Data Retrieval - Revised

```java
Result<List<Customer>> getAllCustomers() {
    return fetchAllCustomerPages(0, new ArrayList<>());
}

private Result<List<Customer>> fetchAllCustomerPages(int page, List<Customer> accumulated) {
    return fetchCustomerPage(page)
        .flatMap(pageResult -> processPageResult(pageResult, page, accumulated));
}

private Result<List<Customer>> processPageResult(PageResult<Customer> pageResult, int currentPage, List<Customer> accumulated) {
    List<Customer> updatedList = combineResults(accumulated, pageResult.getItems());
    return continueOrFinish(pageResult, currentPage, updatedList);
}

private Result<List<Customer>> continueOrFinish(PageResult<Customer> pageResult, int currentPage, List<Customer> updatedList) {
    if (pageResult.hasMorePages()) {
        return fetchAllCustomerPages(currentPage + 1, updatedList);
    } else {
        return Result.success(updatedList);
    }
}

private List<Customer> combineResults(List<Customer> existing, List<Customer> newItems) {
    List<Customer> combined = new ArrayList<>(existing);
    combined.addAll(newItems);
    return combined;
}

private Result<PageResult<Customer>> fetchCustomerPage(int page) {
    return Result.lift(() -> customerRepository.getPage(page, PAGE_SIZE));
}
```

I've further refined the `processPageResult` method by extracting the decision logic to a dedicated method `continueOrFinish`.

## 4. Batch Processing - Revised

```java
Result<BatchSummary> processBatch(List<Transaction> transactions) {
    return Result.success(transactions)
        .map(this::partitionIntoBatches)
        .flatMap(this::processAllBatches)
        .map(this::createBatchSummary);
}

private List<List<Transaction>> partitionIntoBatches(List<Transaction> transactions) {
    return ListUtils.partition(transactions, BATCH_SIZE);
}

private Result<List<BatchResult>> processAllBatches(List<List<Transaction>> batches) {
    List<Result<BatchResult>> batchResults = mapBatchesToResults(batches);
    return collectResults(batchResults);
}

private List<Result<BatchResult>> mapBatchesToResults(List<List<Transaction>> batches) {
    return batches.stream()
        .map(this::processSingleBatch)
        .collect(Collectors.toList());
}

private Result<BatchResult> processSingleBatch(List<Transaction> batch) {
    return transactionProcessor.process(batch)
        .map(results -> new BatchResult(batch.size(), results));
}

private Result<List<BatchResult>> collectResults(List<Result<BatchResult>> results) {
    return Result.collect(results);
}

private BatchSummary createBatchSummary(List<BatchResult> batchResults) {
    int totalProcessed = calculateTotalProcessed(batchResults);
    return new BatchSummary(totalProcessed, batchResults.size());
}

private int calculateTotalProcessed(List<BatchResult> batchResults) {
    return batchResults.stream()
        .mapToInt(BatchResult::getProcessedCount)
        .sum();
}
```

I've improved the `processAllBatches` method by extracting the mapping operation to a separate method.

## 5. Recursive Operations - Further Refined

```java
Result<DirectoryContents> scanDirectory(Path directory) {
    return validateDirectory(directory)
        .flatMap(this::readDirectoryContents);
}

private Result<Path> validateDirectory(Path directory) {
    return Result.lift(() -> validateDirectoryExists(directory));
}

private Path validateDirectoryExists(Path directory) throws IOException {
    if (!Files.exists(directory)) {
        throw new DirectoryNotFoundException("Directory not found: " + directory);
    }
    if (!Files.isDirectory(directory)) {
        throw new NotDirectoryException("Path is not a directory: " + directory);
    }
    return directory;
}

private Result<DirectoryContents> readDirectoryContents(Path directory) {
    return listDirectoryContents(directory)
        .flatMap(this::processPathStream);
}

private Result<Stream<Path>> listDirectoryContents(Path directory) {
    return Result.lift(() -> Files.list(directory));
}

private Result<DirectoryContents> processPathStream(Stream<Path> paths) {
    try (paths) {
        List<Path> pathList = collectPathsFromStream(paths);
        return categorizeAndProcessPaths(pathList);
    }
}

private List<Path> collectPathsFromStream(Stream<Path> paths) {
    return paths.collect(Collectors.toList());
}

private Result<DirectoryContents> categorizeAndProcessPaths(List<Path> paths) {
    PathCategorization categorization = categorizePaths(paths);
    return processSubdirectories(categorization.directories)
        .map(subdirs -> createDirectoryContents(categorization.files, subdirs));
}

private PathCategorization categorizePaths(List<Path> paths) {
    List<FileInfo> files = new ArrayList<>();
    List<Path> directories = new ArrayList<>();
    
    for (Path path : paths) {
        if (Files.isRegularFile(path)) {
            files.add(createFileInfo(path));
        } else if (Files.isDirectory(path)) {
            directories.add(path);
        }
    }
    
    return new PathCategorization(files, directories);
}

private Result<List<DirectoryContents>> processSubdirectories(List<Path> directories) {
    List<Result<DirectoryContents>> subdirResults = mapDirectoriesToContents(directories);
    return collectDirectoryResults(subdirResults);
}

private List<Result<DirectoryContents>> mapDirectoriesToContents(List<Path> directories) {
    return directories.stream()
        .map(this::scanDirectory)
        .collect(Collectors.toList());
}

private Result<List<DirectoryContents>> collectDirectoryResults(List<Result<DirectoryContents>> results) {
    return Result.collect(results);
}

private FileInfo createFileInfo(Path file) {
    try {
        long size = Files.size(file);
        String contentType = Files.probeContentType(file);
        return new FileInfo(file.getFileName().toString(), size, contentType);
    } catch (IOException e) {
        return createFallbackFileInfo(file);
    }
}

private FileInfo createFallbackFileInfo(Path file) {
    return new FileInfo(file.getFileName().toString(), -1, "unknown");
}

private DirectoryContents createDirectoryContents(List<FileInfo> files, List<DirectoryContents> subdirectories) {
    return new DirectoryContents(files, subdirectories);
}

// Helper class to hold categorized paths
private static class PathCategorization {
    final List<FileInfo> files;
    final List<Path> directories;
    
    PathCategorization(List<FileInfo> files, List<Path> directories) {
        this.files = files;
        this.directories = directories;
    }
}
```

I've further refined several methods by extracting operations to maintain a strict single level of abstraction.

## 6. Time-based Polling - Further Refined

```java
Promise<JobResult> waitForJobCompletion(String jobId) {
    return pollUntilCompleted(jobId, 0, MAX_POLLS);
}

private Promise<JobResult> pollUntilCompleted(String jobId, int attemptsMade, int maxAttempts) {
    if (isMaxAttemptsReached(attemptsMade, maxAttempts)) {
        return createTimeoutFailure();
    }
    
    return checkJobStatus(jobId)
        .flatMap(status -> handleJobStatus(jobId, status, attemptsMade, maxAttempts));
}

private boolean isMaxAttemptsReached(int attemptsMade, int maxAttempts) {
    return attemptsMade >= maxAttempts;
}

private Promise<JobResult> createTimeoutFailure() {
    return Promise.failure(new TimeoutException("Job didn't complete within allocated time"));
}

private Promise<JobStatus> checkJobStatus(String jobId) {
    return Promise.lift(() -> jobService.getStatus(jobId));
}

private Promise<JobResult> handleJobStatus(String jobId, JobStatus status, int attemptsMade, int maxAttempts) {
    switch (status.getState()) {
        case COMPLETED:
            return fetchJobResult(jobId);
            
        case FAILED:
            return createJobFailureResult(status);
            
        case RUNNING:
        case QUEUED:
            return delayAndRetry(jobId, attemptsMade, maxAttempts);
            
        default:
            return createUnexpectedStateFailure(status);
    }
}

private Promise<JobResult> createJobFailureResult(JobStatus status) {
    return Promise.failure(new JobFailedException("Job failed: " + status.getErrorMessage()));
}

private Promise<JobResult> createUnexpectedStateFailure(JobStatus status) {
    return Promise.failure(new UnexpectedJobStateException("Unexpected job state: " + status.getState()));
}

private Promise<JobResult> fetchJobResult(String jobId) {
    return Promise.lift(() -> jobService.getResult(jobId));
}

private Promise<JobResult> delayAndRetry(String jobId, int attemptsMade, int maxAttempts) {
    return createDelay()
        .flatMap(ignored -> pollUntilCompleted(jobId, attemptsMade + 1, maxAttempts));
}

private Promise<Void> createDelay() {
    return Promise.delay(Duration.ofSeconds(POLL_INTERVAL_SECONDS));
}
```

I've improved the `pollUntilCompleted` method by extracting the condition check to a separate method and added a dedicated method for creating the delay.

## 7. Parallel Iteration - Revised

```java
Result<AggregateReport> generateReports(List<Department> departments) {
    return parallelProcessDepartments(departments)
        .map(this::combineReports);
}

private Result<List<DepartmentReport>> parallelProcessDepartments(List<Department> departments) {
    List<Result<DepartmentReport>> reportTasks = createReportTasks(departments);
    return collectReportResults(reportTasks);
}

private List<Result<DepartmentReport>> createReportTasks(List<Department> departments) {
    return departments.stream()
        .map(this::generateDepartmentReport)
        .collect(Collectors.toList());
}

private Result<List<DepartmentReport>> collectReportResults(List<Result<DepartmentReport>> tasks) {
    return Result.collect(tasks);
}

private Result<DepartmentReport> generateDepartmentReport(Department department) {
    return Result.lift(() -> reportGenerator.createReport(department));
}

private AggregateReport combineReports(List<DepartmentReport> reports) {
    double totalExpenses = calculateTotalExpenses(reports);
    double totalRevenue = calculateTotalRevenue(reports);
    
    return new AggregateReport(reports, totalExpenses, totalRevenue);
}

private double calculateTotalExpenses(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalExpenses)
        .sum();
}

private double calculateTotalRevenue(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalRevenue)
        .sum();
}
```

I've refined the `parallelProcessDepartments` method by extracting the task creation and result collection into separate methods.

## Integration with Other Patterns - Further Refined

```java
Result<ProcessingSummary> processItemsByType(List<Item> items) {
    return Result.success(items)
        .map(this::categorizeItems)
        .flatMap(this::processItemsByCategory);
}

private Map<ItemType, List<Item>> categorizeItems(List<Item> items) {
    return items.stream()
        .collect(Collectors.groupingBy(Item::getType));
}

private Result<ProcessingSummary> processItemsByCategory(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> categoryResults = createCategoryResults(categorizedItems);
    return collectCategoryResults(categoryResults);
}

private List<Result<CategoryResult>> createCategoryResults(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> results = new ArrayList<>();
    
    for (Map.Entry<ItemType, List<Item>> entry : categorizedItems.entrySet()) {
        Result<CategoryResult> result = processSingleCategory(entry.getKey(), entry.getValue());
        results.add(result);
    }
    
    return results;
}

private Result<CategoryResult> processSingleCategory(ItemType type, List<Item> items) {
    return processCategoryItems(type, items);
}

private Result<ProcessingSummary> collectCategoryResults(List<Result<CategoryResult>> categoryResults) {
    return Result.collect(categoryResults)
        .map(this::buildSummary);
}

private Result<CategoryResult> processCategoryItems(ItemType type, List<Item> items) {
    return selectProcessingStrategy(type)
        .flatMap(strategy -> processItemsWithStrategy(strategy, items, type));
}

private Result<CategoryResult> processItemsWithStrategy(ProcessingStrategy strategy, List<Item> items, ItemType type) {
    return strategy.process(items)
        .map(results -> new CategoryResult(type, results));
}

private Result<ProcessingStrategy> selectProcessingStrategy(ItemType type) {
    return switch (type) {
        case DIGITAL -> Result.success(new DigitalItemProcessor());
        case PHYSICAL -> Result.success(new PhysicalItemProcessor());
        case SUBSCRIPTION -> Result.success(new SubscriptionProcessor());
        default -> createUnknownTypeFailure(type);
    };
}

private Result<ProcessingStrategy> createUnknownTypeFailure(ItemType type) {
    return Result.failure(Causes.of("Unknown item type: " + type));
}

private ProcessingSummary buildSummary(List<CategoryResult> results) {
    int totalProcessed = calculateTotalProcessed(results);
    Map<ItemType, Integer> countsByType = calculateCountsByType(results);
    
    return new ProcessingSummary(totalProcessed, countsByType, results);
}

private int calculateTotalProcessed(List<CategoryResult> results) {
    return results.stream()
        .mapToInt(result -> result.getResults().size())
        .sum();
}

private Map<ItemType, Integer> calculateCountsByType(List<CategoryResult> results) {
    return results.stream()
        .collect(Collectors.toMap(
            CategoryResult::getType,
            result -> result.getResults().size()
        ));
}
```

I've further refined this example by:
1. Extracting `processSingleCategory` method
2. Extracting `processItemsWithStrategy` method
3. Extracting `createUnknownTypeFailure` method

## Summary

Thank you for pointing out the violation in the `createTaxReport` method. I've carefully reviewed all examples and made further refinements to ensure that each method strictly adheres to the single level of abstraction principle. Each method now:

1. Operates at only one level of abstraction
2. Has a single, clear responsibility
3. Delegates more detailed work to more specific methods
4. Composes operations at the same level of abstraction

These revisions demonstrate how the Iteration pattern in PFJ maintains a strict separation of concerns through consistently applied single-level abstraction, making the code more maintainable and easier to reason about, even when handling complex recursive or parallel iteration operations.

User:
The categorizePaths function could be rewritten in functional style. Consider this is a general rule that more declarative approach is always preferred option. Don't replace iteration with the tail recursion.

Assistant:
You're absolutely right. Using a more declarative, functional style is preferable for clarity and maintainability. Let me revise the `categorizePaths` method using a functional approach instead of an imperative one.

## Revised `categorizePaths` Method - Functional Style

```java
private PathCategorization categorizePaths(List<Path> paths) {
    Map<Boolean, List<Path>> categorized = paths.stream()
        .collect(Collectors.partitioningBy(Files::isRegularFile));
    
    List<FileInfo> files = categorized.get(true).stream()
        .map(this::createFileInfo)
        .collect(Collectors.toList());
    
    List<Path> directories = categorized.get(false).stream()
        .filter(Files::isDirectory)
        .collect(Collectors.toList());
    
    return new PathCategorization(files, directories);
}
```

This revised version:

1. Uses `partitioningBy` to separate regular files from other types in a single operation
2. Maps the files to `FileInfo` objects using a stream
3. Filters the non-files to extract only directories
4. Creates the categorization object from the results

## More Functional Approach for Other Examples

Applying this principle to other examples in our codebase:

### Revised Collection Transformation - `processInvoicesForTax`

```java
private List<TaxEntry> processInvoicesForTax(List<Invoice> invoices) {
    return invoices.stream()
        .map(this::calculateTaxForInvoice)
        .collect(Collectors.toList());
}
```
(Already functional - no changes needed)

### Revised Batch Processing - `createCategoryResults`

```java
private List<Result<CategoryResult>> createCategoryResults(Map<ItemType, List<Item>> categorizedItems) {
    return categorizedItems.entrySet().stream()
        .map(entry -> processSingleCategory(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
}
```

### Improved Time-based Polling - `handleJobStatus`

```java
private Promise<JobResult> handleJobStatus(String jobId, JobStatus status, int attemptsMade, int maxAttempts) {
    return switch (status.getState()) {
        case COMPLETED -> fetchJobResult(jobId);
        case FAILED -> createJobFailureResult(status);
        case RUNNING, QUEUED -> delayAndRetry(jobId, attemptsMade, maxAttempts);
        default -> createUnexpectedStateFailure(status);
    };
}
```

### Enhanced Retry Pattern - `isRetryableError`

```java
private boolean isRetryableError(Throwable error) {
    Set<Class<? extends Throwable>> retryableExceptions = 
        Set.of(TimeoutException.class, ConnectionException.class);
        
    return retryableExceptions.contains(error.getClass()) || 
           (error instanceof HttpException && ((HttpException)error).getStatusCode() >= 500);
}
```

### Functional `calculateCountsByType`

```java
private Map<ItemType, Integer> calculateCountsByType(List<CategoryResult> results) {
    return results.stream()
        .collect(Collectors.toMap(
            CategoryResult::getType,
            result -> result.getResults().size()
        ));
}
```
(Already functional - no changes needed)

## General Rule Application

The preference for declarative, functional approaches over imperative ones is a valuable principle that enhances code quality in several ways:

1. **Conciseness**: Functional code is often more compact and expressive
2. **Immutability**: Encourages immutable data structures which reduce side effects
3. **Readability**: Makes the intent clearer by focusing on "what" rather than "how"
4. **Parallelism**: Functional operations are often easier to parallelize when needed
5. **Testability**: Pure functions are easier to test in isolation

In Java, this typically means leveraging the Stream API, method references, and functional interfaces wherever appropriate, while still maintaining single level of abstraction in each method.

The recommendation to avoid replacing iteration with tail recursion is also important - while recursion can be elegant, in Java it's often less efficient and harder to read than well-structured functional iterations.

These functional-style improvements maintain or enhance the single level of abstraction principle while making the code more declarative and easier to understand.

User:
Please, take a look at categorisePaths function. Inner transformations are at second level of abstraction. Check other examples too.

Assistant:
You're absolutely right. The revised `categorizePaths` function still has nested transformations that violate the single level of abstraction principle. Let me fix this and review other examples to ensure strict adherence to this principle.

## Properly Revised `categorizePaths` - Single Level of Abstraction

```java
private PathCategorization categorizePaths(List<Path> paths) {
    Map<Boolean, List<Path>> categorized = partitionPathsByFileType(paths);
    List<FileInfo> files = convertPathsToFileInfos(categorized.get(true));
    List<Path> directories = filterDirectories(categorized.get(false));
    
    return new PathCategorization(files, directories);
}

private Map<Boolean, List<Path>> partitionPathsByFileType(List<Path> paths) {
    return paths.stream()
        .collect(Collectors.partitioningBy(Files::isRegularFile));
}

private List<FileInfo> convertPathsToFileInfos(List<Path> filePaths) {
    return filePaths.stream()
        .map(this::createFileInfo)
        .collect(Collectors.toList());
}

private List<Path> filterDirectories(List<Path> nonFilePaths) {
    return nonFilePaths.stream()
        .filter(Files::isDirectory)
        .collect(Collectors.toList());
}
```

Now the `categorizePaths` method maintains a single level of abstraction by delegating each transformation to its own method.

## Review and Correction of Other Examples

### 1. Collection Transformation - Review

```java
private List<TaxEntry> processInvoicesForTax(List<Invoice> invoices) {
    return invoices.stream()
        .map(this::calculateTaxForInvoice)
        .collect(Collectors.toList());
}
```

This is actually at a single level of abstraction since it's delegating the calculation to a separate method.

### 2. Revising Batch Processing - `createCategoryResults`

```java
private List<Result<CategoryResult>> createCategoryResults(Map<ItemType, List<Item>> categorizedItems) {
    return categorizedItems.entrySet().stream()
        .map(entry -> processSingleCategory(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
}
```

This is correct - it operates at one level of abstraction.

### 3. Improved Time-based Polling - `handleJobStatus`

```java
private Promise<JobResult> handleJobStatus(String jobId, JobStatus status, int attemptsMade, int maxAttempts) {
    return switch (status.getState()) {
        case COMPLETED -> fetchJobResult(jobId);
        case FAILED -> createJobFailureResult(status);
        case RUNNING, QUEUED -> delayAndRetry(jobId, attemptsMade, maxAttempts);
        default -> createUnexpectedStateFailure(status);
    };
}
```

This is now at a single level of abstraction, as each case delegates to a method.

### 4. Revised `calculateTotalTax`

```java
private double calculateTotalTax(List<TaxEntry> entries) {
    return entries.stream()
        .mapToDouble(TaxEntry::getAmount)
        .sum();
}
```

This function is simple but does operate at one level of abstraction.

### 5. Fix for `processPathStream`

```java
private Result<DirectoryContents> processPathStream(Stream<Path> paths) {
    try (paths) {
        List<Path> pathList = collectPathsFromStream(paths);
        return categorizeAndProcessPaths(pathList);
    }
}
```

This is at a single level of abstraction.

### 6. Fix for `combineReports`

```java
private AggregateReport combineReports(List<DepartmentReport> reports) {
    double totalExpenses = calculateTotalExpenses(reports);
    double totalRevenue = calculateTotalRevenue(reports);
    
    return new AggregateReport(reports, totalExpenses, totalRevenue);
}
```

This function is at a single level of abstraction.

### 7. Revisiting `buildSummary`

```java
private ProcessingSummary buildSummary(List<CategoryResult> results) {
    int totalProcessed = calculateTotalProcessed(results);
    Map<ItemType, Integer> countsByType = calculateCountsByType(results);
    
    return new ProcessingSummary(totalProcessed, countsByType, results);
}
```

This is at a single level of abstraction.

### 8. Review of Enhanced `isRetryableError`

```java
private boolean isRetryableError(Throwable error) {
    Set<Class<? extends Throwable>> retryableExceptions = 
        Set.of(TimeoutException.class, ConnectionException.class);
        
    return retryableExceptions.contains(error.getClass()) || 
           isRetryableHttpError(error);
}

private boolean isRetryableHttpError(Throwable error) {
    return error instanceof HttpException && 
           ((HttpException)error).getStatusCode() >= 500;
}
```

I've split this to maintain a single level of abstraction.

### 9. Fix for `processItemsByCategory`

```java
private Result<ProcessingSummary> processItemsByCategory(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> categoryResults = createCategoryResults(categorizedItems);
    return collectCategoryResults(categoryResults);
}
```

This is at a single level of abstraction.

## General Principles Applied

Here are the principles I've applied to ensure each method strictly adheres to a single level of abstraction:

1. **Each method does one thing**: Methods perform a single logical operation.
2. **No nested transformations**: Stream operations are either simple or extracted into helper methods.
3. **Consistent abstraction level**: All operations in a method are at the same conceptual level.
4. **Clear delegation**: Higher-level methods delegate to lower-level methods rather than implementing details.
5. **Declarative style**: Functional approaches are used throughout, making intentions clear.

The key insight is that even when using functional programming constructs like streams, we still need to extract complex transformations to separate methods to maintain a single level of abstraction. This combines the benefits of functional programming with clean code principles.

With these revisions, all examples now strictly adhere to both the single level of abstraction principle and the preference for declarative, functional approaches.

User:
please, show full description of the pattern after all updates and corrections.

Assistant:
# The Iteration Pattern in Pure Functional Java

## Introduction

The Iteration Pattern in Pure Functional Java (PFJ) provides a consistent approach to handling iterative operations in a functional style. It addresses operations that process collections, repetitive tasks, pagination, batching, and recursive traversals through a declarative, composable API rather than traditional imperative loops.

## Core Principles

1. **Declarative over Imperative**: Express what should be done rather than how to do it.
2. **Single Level of Abstraction**: Each method operates at exactly one level of abstraction.
3. **Composition over Mutation**: Combine operations through functional composition rather than state mutation.
4. **Error Propagation**: Errors flow through the composition chain with proper context.
5. **Functional Transformations**: Use map/flatMap/filter for data transformations.
6. **Immutable Data Flow**: Data passes through transformations without being modified in place.

## Pattern Structure

The pattern typically follows this structure:

1. Begin with an input collection or initial state
2. Transform data through pure functions
3. Handle errors functionally
4. Compose operations to form a processing pipeline
5. Return a type that represents success/failure with appropriate data

## Key Components

### 1. Container Types
- `Result<T>` - Represents synchronous operations that may succeed or fail
- `Promise<T>` - Represents asynchronous operations that may succeed or fail
- Collections wrapped in these containers

### 2. Core Operations
- `map` - Transform successful values
- `flatMap` - Chain operations that may themselves fail
- `filter` - Include only values meeting certain criteria
- `collect` - Gather many results into a single result

### 3. Processing Styles
- **Collection Processing** - Transforming entire collections at once
- **Recursive Processing** - Self-similar operations on nested structures
- **Paginated/Batched Processing** - Processing data in chunks
- **Polled/Retry Processing** - Operations attempted repeatedly

## Implementation Examples

### 1. Collection Transformation

```java
Result<TaxReport> calculateTaxes(List<Invoice> invoices) {
    return Result.success(invoices)
        .map(this::processInvoicesForTax)
        .map(this::createTaxReport);
}

private List<TaxEntry> processInvoicesForTax(List<Invoice> invoices) {
    return invoices.stream()
        .map(this::calculateTaxForInvoice)
        .collect(Collectors.toList());
}

private TaxEntry calculateTaxForInvoice(Invoice invoice) {
    double taxAmount = invoice.getTotal() * getTaxRate(invoice.getRegion());
    return new TaxEntry(invoice.getId(), taxAmount, invoice.getRegion());
}

private TaxReport createTaxReport(List<TaxEntry> entries) {
    double totalTax = calculateTotalTax(entries);
    return new TaxReport(entries, totalTax, LocalDate.now());
}

private double calculateTotalTax(List<TaxEntry> entries) {
    return entries.stream()
        .mapToDouble(TaxEntry::getAmount)
        .sum();
}
```

### 2. Retry Pattern

```java
Result<DataFeed> fetchExternalData(String source) {
    return retryFetchOperation(source)
        .map(this::parseDataFeed);
}

private Result<String> retryFetchOperation(String source) {
    return Retry.withMaxAttempts(3)
        .withBackoff(Duration.ofSeconds(1), 2.0)
        .withErrorFilter(this::isRetryableError)
        .execute(() -> fetchFromExternalSource(source));
}

private boolean isRetryableError(Throwable error) {
    Set<Class<? extends Throwable>> retryableExceptions = 
        Set.of(TimeoutException.class, ConnectionException.class);
        
    return retryableExceptions.contains(error.getClass()) || 
           isRetryableHttpError(error);
}

private boolean isRetryableHttpError(Throwable error) {
    return error instanceof HttpException && 
           ((HttpException)error).getStatusCode() >= 500;
}

private Result<String> fetchFromExternalSource(String source) {
    return Result.lift(() -> externalApiClient.fetch(source));
}

private DataFeed parseDataFeed(String rawData) {
    return DataFeed.fromJson(rawData);
}
```

### 3. Paginated Data Retrieval

```java
Result<List<Customer>> getAllCustomers() {
    return fetchAllCustomerPages(0, new ArrayList<>());
}

private Result<List<Customer>> fetchAllCustomerPages(int page, List<Customer> accumulated) {
    return fetchCustomerPage(page)
        .flatMap(pageResult -> processPageResult(pageResult, page, accumulated));
}

private Result<List<Customer>> processPageResult(PageResult<Customer> pageResult, int currentPage, List<Customer> accumulated) {
    List<Customer> updatedList = combineResults(accumulated, pageResult.getItems());
    return continueOrFinish(pageResult, currentPage, updatedList);
}

private Result<List<Customer>> continueOrFinish(PageResult<Customer> pageResult, int currentPage, List<Customer> updatedList) {
    if (pageResult.hasMorePages()) {
        return fetchAllCustomerPages(currentPage + 1, updatedList);
    } else {
        return Result.success(updatedList);
    }
}

private List<Customer> combineResults(List<Customer> existing, List<Customer> newItems) {
    List<Customer> combined = new ArrayList<>(existing);
    combined.addAll(newItems);
    return combined;
}

private Result<PageResult<Customer>> fetchCustomerPage(int page) {
    return Result.lift(() -> customerRepository.getPage(page, PAGE_SIZE));
}
```

### 4. Batch Processing

```java
Result<BatchSummary> processBatch(List<Transaction> transactions) {
    return Result.success(transactions)
        .map(this::partitionIntoBatches)
        .flatMap(this::processAllBatches)
        .map(this::createBatchSummary);
}

private List<List<Transaction>> partitionIntoBatches(List<Transaction> transactions) {
    return ListUtils.partition(transactions, BATCH_SIZE);
}

private Result<List<BatchResult>> processAllBatches(List<List<Transaction>> batches) {
    List<Result<BatchResult>> batchResults = mapBatchesToResults(batches);
    return collectResults(batchResults);
}

private List<Result<BatchResult>> mapBatchesToResults(List<List<Transaction>> batches) {
    return batches.stream()
        .map(this::processSingleBatch)
        .collect(Collectors.toList());
}

private Result<BatchResult> processSingleBatch(List<Transaction> batch) {
    return transactionProcessor.process(batch)
        .map(results -> new BatchResult(batch.size(), results));
}

private Result<List<BatchResult>> collectResults(List<Result<BatchResult>> results) {
    return Result.collect(results);
}

private BatchSummary createBatchSummary(List<BatchResult> batchResults) {
    int totalProcessed = calculateTotalProcessed(batchResults);
    return new BatchSummary(totalProcessed, batchResults.size());
}

private int calculateTotalProcessed(List<BatchResult> batchResults) {
    return batchResults.stream()
        .mapToInt(BatchResult::getProcessedCount)
        .sum();
}
```

### 5. Recursive Operations

```java
Result<DirectoryContents> scanDirectory(Path directory) {
    return validateDirectory(directory)
        .flatMap(this::readDirectoryContents);
}

private Result<Path> validateDirectory(Path directory) {
    return Result.lift(() -> validateDirectoryExists(directory));
}

private Path validateDirectoryExists(Path directory) throws IOException {
    if (!Files.exists(directory)) {
        throw new DirectoryNotFoundException("Directory not found: " + directory);
    }
    if (!Files.isDirectory(directory)) {
        throw new NotDirectoryException("Path is not a directory: " + directory);
    }
    return directory;
}

private Result<DirectoryContents> readDirectoryContents(Path directory) {
    return listDirectoryContents(directory)
        .flatMap(this::processPathStream);
}

private Result<Stream<Path>> listDirectoryContents(Path directory) {
    return Result.lift(() -> Files.list(directory));
}

private Result<DirectoryContents> processPathStream(Stream<Path> paths) {
    try (paths) {
        List<Path> pathList = collectPathsFromStream(paths);
        return categorizeAndProcessPaths(pathList);
    }
}

private List<Path> collectPathsFromStream(Stream<Path> paths) {
    return paths.collect(Collectors.toList());
}

private Result<DirectoryContents> categorizeAndProcessPaths(List<Path> paths) {
    PathCategorization categorization = categorizePaths(paths);
    return processSubdirectories(categorization.directories)
        .map(subdirs -> createDirectoryContents(categorization.files, subdirs));
}

private PathCategorization categorizePaths(List<Path> paths) {
    Map<Boolean, List<Path>> categorized = partitionPathsByFileType(paths);
    List<FileInfo> files = convertPathsToFileInfos(categorized.get(true));
    List<Path> directories = filterDirectories(categorized.get(false));
    
    return new PathCategorization(files, directories);
}

private Map<Boolean, List<Path>> partitionPathsByFileType(List<Path> paths) {
    return paths.stream()
        .collect(Collectors.partitioningBy(Files::isRegularFile));
}

private List<FileInfo> convertPathsToFileInfos(List<Path> filePaths) {
    return filePaths.stream()
        .map(this::createFileInfo)
        .collect(Collectors.toList());
}

private List<Path> filterDirectories(List<Path> nonFilePaths) {
    return nonFilePaths.stream()
        .filter(Files::isDirectory)
        .collect(Collectors.toList());
}

private Result<List<DirectoryContents>> processSubdirectories(List<Path> directories) {
    List<Result<DirectoryContents>> subdirResults = mapDirectoriesToContents(directories);
    return collectDirectoryResults(subdirResults);
}

private List<Result<DirectoryContents>> mapDirectoriesToContents(List<Path> directories) {
    return directories.stream()
        .map(this::scanDirectory)
        .collect(Collectors.toList());
}

private Result<List<DirectoryContents>> collectDirectoryResults(List<Result<DirectoryContents>> results) {
    return Result.collect(results);
}

private FileInfo createFileInfo(Path file) {
    try {
        long size = Files.size(file);
        String contentType = Files.probeContentType(file);
        return new FileInfo(file.getFileName().toString(), size, contentType);
    } catch (IOException e) {
        return createFallbackFileInfo(file);
    }
}

private FileInfo createFallbackFileInfo(Path file) {
    return new FileInfo(file.getFileName().toString(), -1, "unknown");
}

private DirectoryContents createDirectoryContents(List<FileInfo> files, List<DirectoryContents> subdirectories) {
    return new DirectoryContents(files, subdirectories);
}

// Helper class to hold categorized paths
private static class PathCategorization {
    final List<FileInfo> files;
    final List<Path> directories;
    
    PathCategorization(List<FileInfo> files, List<Path> directories) {
        this.files = files;
        this.directories = directories;
    }
}
```

### 6. Time-based Polling

```java
Promise<JobResult> waitForJobCompletion(String jobId) {
    return pollUntilCompleted(jobId, 0, MAX_POLLS);
}

private Promise<JobResult> pollUntilCompleted(String jobId, int attemptsMade, int maxAttempts) {
    if (isMaxAttemptsReached(attemptsMade, maxAttempts)) {
        return createTimeoutFailure();
    }
    
    return checkJobStatus(jobId)
        .flatMap(status -> handleJobStatus(jobId, status, attemptsMade, maxAttempts));
}

private boolean isMaxAttemptsReached(int attemptsMade, int maxAttempts) {
    return attemptsMade >= maxAttempts;
}

private Promise<JobResult> createTimeoutFailure() {
    return Promise.failure(new TimeoutException("Job didn't complete within allocated time"));
}

private Promise<JobStatus> checkJobStatus(String jobId) {
    return Promise.lift(() -> jobService.getStatus(jobId));
}

private Promise<JobResult> handleJobStatus(String jobId, JobStatus status, int attemptsMade, int maxAttempts) {
    return switch (status.getState()) {
        case COMPLETED -> fetchJobResult(jobId);
        case FAILED -> createJobFailureResult(status);
        case RUNNING, QUEUED -> delayAndRetry(jobId, attemptsMade, maxAttempts);
        default -> createUnexpectedStateFailure(status);
    };
}

private Promise<JobResult> createJobFailureResult(JobStatus status) {
    return Promise.failure(new JobFailedException("Job failed: " + status.getErrorMessage()));
}

private Promise<JobResult> createUnexpectedStateFailure(JobStatus status) {
    return Promise.failure(new UnexpectedJobStateException("Unexpected job state: " + status.getState()));
}

private Promise<JobResult> fetchJobResult(String jobId) {
    return Promise.lift(() -> jobService.getResult(jobId));
}

private Promise<JobResult> delayAndRetry(String jobId, int attemptsMade, int maxAttempts) {
    return createDelay()
        .flatMap(ignored -> pollUntilCompleted(jobId, attemptsMade + 1, maxAttempts));
}

private Promise<Void> createDelay() {
    return Promise.delay(Duration.ofSeconds(POLL_INTERVAL_SECONDS));
}
```

### 7. Parallel Iteration

```java
Result<AggregateReport> generateReports(List<Department> departments) {
    return parallelProcessDepartments(departments)
        .map(this::combineReports);
}

private Result<List<DepartmentReport>> parallelProcessDepartments(List<Department> departments) {
    List<Result<DepartmentReport>> reportTasks = createReportTasks(departments);
    return collectReportResults(reportTasks);
}

private List<Result<DepartmentReport>> createReportTasks(List<Department> departments) {
    return departments.stream()
        .map(this::generateDepartmentReport)
        .collect(Collectors.toList());
}

private Result<List<DepartmentReport>> collectReportResults(List<Result<DepartmentReport>> tasks) {
    return Result.collect(tasks);
}

private Result<DepartmentReport> generateDepartmentReport(Department department) {
    return Result.lift(() -> reportGenerator.createReport(department));
}

private AggregateReport combineReports(List<DepartmentReport> reports) {
    double totalExpenses = calculateTotalExpenses(reports);
    double totalRevenue = calculateTotalRevenue(reports);
    
    return new AggregateReport(reports, totalExpenses, totalRevenue);
}

private double calculateTotalExpenses(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalExpenses)
        .sum();
}

private double calculateTotalRevenue(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalRevenue)
        .sum();
}
```

### 8. Integration with Other Patterns

```java
Result<ProcessingSummary> processItemsByType(List<Item> items) {
    return Result.success(items)
        .map(this::categorizeItems)
        .flatMap(this::processItemsByCategory);
}

private Map<ItemType, List<Item>> categorizeItems(List<Item> items) {
    return items.stream()
        .collect(Collectors.groupingBy(Item::getType));
}

private Result<ProcessingSummary> processItemsByCategory(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> categoryResults = createCategoryResults(categorizedItems);
    return collectCategoryResults(categoryResults);
}

private List<Result<CategoryResult>> createCategoryResults(Map<ItemType, List<Item>> categorizedItems) {
    return categorizedItems.entrySet().stream()
        .map(entry -> processSingleCategory(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
}

private Result<CategoryResult> processSingleCategory(ItemType type, List<Item> items) {
    return processCategoryItems(type, items);
}

private Result<ProcessingSummary> collectCategoryResults(List<Result<CategoryResult>> categoryResults) {
    return Result.collect(categoryResults)
        .map(this::buildSummary);
}

private Result<CategoryResult> processCategoryItems(ItemType type, List<Item> items) {
    return selectProcessingStrategy(type)
        .flatMap(strategy -> processItemsWithStrategy(strategy, items, type));
}

private Result<CategoryResult> processItemsWithStrategy(ProcessingStrategy strategy, List<Item> items, ItemType type) {
    return strategy.process(items)
        .map(results -> new CategoryResult(type, results));
}

private Result<ProcessingStrategy> selectProcessingStrategy(ItemType type) {
    return switch (type) {
        case DIGITAL -> Result.success(new DigitalItemProcessor());
        case PHYSICAL -> Result.success(new PhysicalItemProcessor());
        case SUBSCRIPTION -> Result.success(new SubscriptionProcessor());
        default -> createUnknownTypeFailure(type);
    };
}

private Result<ProcessingStrategy> createUnknownTypeFailure(ItemType type) {
    return Result.failure(Causes.of("Unknown item type: " + type));
}

private ProcessingSummary buildSummary(List<CategoryResult> results) {
    int totalProcessed = calculateTotalProcessed(results);
    Map<ItemType, Integer> countsByType = calculateCountsByType(results);
    
    return new ProcessingSummary(totalProcessed, countsByType, results);
}

private int calculateTotalProcessed(List<CategoryResult> results) {
    return results.stream()
        .mapToInt(result -> result.getResults().size())
        .sum();
}

private Map<ItemType, Integer> calculateCountsByType(List<CategoryResult> results) {
    return results.stream()
        .collect(Collectors.toMap(
            CategoryResult::getType,
            result -> result.getResults().size()
        ));
}
```

## Benefits of the Pattern

1. **Explicit Error Handling**: Errors are first-class citizens in the functional pipeline.
2. **Improved Testability**: Pure functions with no side effects are easier to test.
3. **Better Readability**: Code expresses intent rather than mechanics.
4. **Maintainability**: Changes are localized to specific transformation functions.
5. **Composability**: Operations can be reused and combined in different ways.
6. **Thread Safety**: Immutable data and pure functions eliminate many concurrency issues.

## When to Use

- Processing collections of data
- Operations that need retries or polling
- Paginated data retrieval
- Batch processing
- Recursive traversals (directories, trees, graphs)
- Parallel or concurrent operations
- Complex workflows with potential failures

## When Not to Use

- Very simple iterations with no error handling requirements
- Performance-critical inner loops where boxing/unboxing overhead matters
- When team is unfamiliar with functional concepts and proper training isn't available

## Implementation Notes

1. Each method should operate at exactly one level of abstraction.
2. Extract complex transformations to dedicated methods.
3. Use declarative approaches over imperative ones.
4. Ensure proper error propagation through the chain.
5. Maintain immutability of data.
6. Use appropriate container types for the operation context (sync/async).

This pattern exemplifies how functional programming principles can be applied effectively in Java to create more maintainable, composable and robust code for iterative operations.

User:
Please, try to avoid "pure functional" as Pragmatic Functional Java intentionally limits functional style to only few concepts. While pure functions still very well welcome, there is no (and never be) accent on the purity.

Assistant:
# The Iteration Pattern in Pragmatic Functional Java

## Introduction

The Iteration Pattern in Pragmatic Functional Java provides a consistent approach to handling iterative operations in a functional style. It addresses operations that process collections, repetitive tasks, pagination, batching, and recursive traversals through a declarative, composable API rather than traditional imperative loops, while remaining practical and accessible to developers from varied backgrounds.

## Core Principles

1. **Declarative over Imperative**: Express what should be done rather than how to do it.
2. **Single Level of Abstraction**: Each method operates at exactly one level of abstraction.
3. **Composition over Mutation**: Combine operations through functional composition rather than state mutation.
4. **Error Propagation**: Errors flow through the composition chain with proper context.
5. **Functional Transformations**: Use map/flatMap/filter for data transformations.
6. **Practical Immutability**: Prefer immutable data flow when it improves code quality.

## Pattern Structure

The pattern typically follows this structure:

1. Begin with an input collection or initial state
2. Transform data through well-defined functions
3. Handle errors through the composition chain
4. Compose operations to form a processing pipeline
5. Return a type that represents success/failure with appropriate data

## Key Components

### 1. Container Types
- `Result<T>` - Represents synchronous operations that may succeed or fail
- `Promise<T>` - Represents asynchronous operations that may succeed or fail
- Collections wrapped in these containers

### 2. Core Operations
- `map` - Transform successful values
- `flatMap` - Chain operations that may themselves fail
- `filter` - Include only values meeting certain criteria
- `collect` - Gather many results into a single result

### 3. Processing Styles
- **Collection Processing** - Transforming entire collections at once
- **Recursive Processing** - Self-similar operations on nested structures
- **Paginated/Batched Processing** - Processing data in chunks
- **Polled/Retry Processing** - Operations attempted repeatedly

## Implementation Examples

### 1. Collection Transformation

```java
Result<TaxReport> calculateTaxes(List<Invoice> invoices) {
    return Result.success(invoices)
        .map(this::processInvoicesForTax)
        .map(this::createTaxReport);
}

private List<TaxEntry> processInvoicesForTax(List<Invoice> invoices) {
    return invoices.stream()
        .map(this::calculateTaxForInvoice)
        .collect(Collectors.toList());
}

private TaxEntry calculateTaxForInvoice(Invoice invoice) {
    double taxAmount = invoice.getTotal() * getTaxRate(invoice.getRegion());
    return new TaxEntry(invoice.getId(), taxAmount, invoice.getRegion());
}

private TaxReport createTaxReport(List<TaxEntry> entries) {
    double totalTax = calculateTotalTax(entries);
    return new TaxReport(entries, totalTax, LocalDate.now());
}

private double calculateTotalTax(List<TaxEntry> entries) {
    return entries.stream()
        .mapToDouble(TaxEntry::getAmount)
        .sum();
}
```

### 2. Retry Pattern

```java
Result<DataFeed> fetchExternalData(String source) {
    return retryFetchOperation(source)
        .map(this::parseDataFeed);
}

private Result<String> retryFetchOperation(String source) {
    return Retry.withMaxAttempts(3)
        .withBackoff(Duration.ofSeconds(1), 2.0)
        .withErrorFilter(this::isRetryableError)
        .execute(() -> fetchFromExternalSource(source));
}

private boolean isRetryableError(Throwable error) {
    Set<Class<? extends Throwable>> retryableExceptions = 
        Set.of(TimeoutException.class, ConnectionException.class);
        
    return retryableExceptions.contains(error.getClass()) || 
           isRetryableHttpError(error);
}

private boolean isRetryableHttpError(Throwable error) {
    return error instanceof HttpException && 
           ((HttpException)error).getStatusCode() >= 500;
}

private Result<String> fetchFromExternalSource(String source) {
    return Result.lift(() -> externalApiClient.fetch(source));
}

private DataFeed parseDataFeed(String rawData) {
    return DataFeed.fromJson(rawData);
}
```

### 3. Paginated Data Retrieval

```java
Result<List<Customer>> getAllCustomers() {
    return fetchAllCustomerPages(0, new ArrayList<>());
}

private Result<List<Customer>> fetchAllCustomerPages(int page, List<Customer> accumulated) {
    return fetchCustomerPage(page)
        .flatMap(pageResult -> processPageResult(pageResult, page, accumulated));
}

private Result<List<Customer>> processPageResult(PageResult<Customer> pageResult, int currentPage, List<Customer> accumulated) {
    List<Customer> updatedList = combineResults(accumulated, pageResult.getItems());
    return continueOrFinish(pageResult, currentPage, updatedList);
}

private Result<List<Customer>> continueOrFinish(PageResult<Customer> pageResult, int currentPage, List<Customer> updatedList) {
    if (pageResult.hasMorePages()) {
        return fetchAllCustomerPages(currentPage + 1, updatedList);
    } else {
        return Result.success(updatedList);
    }
}

private List<Customer> combineResults(List<Customer> existing, List<Customer> newItems) {
    List<Customer> combined = new ArrayList<>(existing);
    combined.addAll(newItems);
    return combined;
}

private Result<PageResult<Customer>> fetchCustomerPage(int page) {
    return Result.lift(() -> customerRepository.getPage(page, PAGE_SIZE));
}
```

### 4. Batch Processing

```java
Result<BatchSummary> processBatch(List<Transaction> transactions) {
    return Result.success(transactions)
        .map(this::partitionIntoBatches)
        .flatMap(this::processAllBatches)
        .map(this::createBatchSummary);
}

private List<List<Transaction>> partitionIntoBatches(List<Transaction> transactions) {
    return ListUtils.partition(transactions, BATCH_SIZE);
}

private Result<List<BatchResult>> processAllBatches(List<List<Transaction>> batches) {
    List<Result<BatchResult>> batchResults = mapBatchesToResults(batches);
    return collectResults(batchResults);
}

private List<Result<BatchResult>> mapBatchesToResults(List<List<Transaction>> batches) {
    return batches.stream()
        .map(this::processSingleBatch)
        .collect(Collectors.toList());
}

private Result<BatchResult> processSingleBatch(List<Transaction> batch) {
    return transactionProcessor.process(batch)
        .map(results -> new BatchResult(batch.size(), results));
}

private Result<List<BatchResult>> collectResults(List<Result<BatchResult>> results) {
    return Result.collect(results);
}

private BatchSummary createBatchSummary(List<BatchResult> batchResults) {
    int totalProcessed = calculateTotalProcessed(batchResults);
    return new BatchSummary(totalProcessed, batchResults.size());
}

private int calculateTotalProcessed(List<BatchResult> batchResults) {
    return batchResults.stream()
        .mapToInt(BatchResult::getProcessedCount)
        .sum();
}
```

### 5. Recursive Operations

```java
Result<DirectoryContents> scanDirectory(Path directory) {
    return validateDirectory(directory)
        .flatMap(this::readDirectoryContents);
}

private Result<Path> validateDirectory(Path directory) {
    return Result.lift(() -> validateDirectoryExists(directory));
}

private Path validateDirectoryExists(Path directory) throws IOException {
    if (!Files.exists(directory)) {
        throw new DirectoryNotFoundException("Directory not found: " + directory);
    }
    if (!Files.isDirectory(directory)) {
        throw new NotDirectoryException("Path is not a directory: " + directory);
    }
    return directory;
}

private Result<DirectoryContents> readDirectoryContents(Path directory) {
    return listDirectoryContents(directory)
        .flatMap(this::processPathStream);
}

private Result<Stream<Path>> listDirectoryContents(Path directory) {
    return Result.lift(() -> Files.list(directory));
}

private Result<DirectoryContents> processPathStream(Stream<Path> paths) {
    try (paths) {
        List<Path> pathList = collectPathsFromStream(paths);
        return categorizeAndProcessPaths(pathList);
    }
}

private List<Path> collectPathsFromStream(Stream<Path> paths) {
    return paths.collect(Collectors.toList());
}

private Result<DirectoryContents> categorizeAndProcessPaths(List<Path> paths) {
    PathCategorization categorization = categorizePaths(paths);
    return processSubdirectories(categorization.directories)
        .map(subdirs -> createDirectoryContents(categorization.files, subdirs));
}

private PathCategorization categorizePaths(List<Path> paths) {
    Map<Boolean, List<Path>> categorized = partitionPathsByFileType(paths);
    List<FileInfo> files = convertPathsToFileInfos(categorized.get(true));
    List<Path> directories = filterDirectories(categorized.get(false));
    
    return new PathCategorization(files, directories);
}

private Map<Boolean, List<Path>> partitionPathsByFileType(List<Path> paths) {
    return paths.stream()
        .collect(Collectors.partitioningBy(Files::isRegularFile));
}

private List<FileInfo> convertPathsToFileInfos(List<Path> filePaths) {
    return filePaths.stream()
        .map(this::createFileInfo)
        .collect(Collectors.toList());
}

private List<Path> filterDirectories(List<Path> nonFilePaths) {
    return nonFilePaths.stream()
        .filter(Files::isDirectory)
        .collect(Collectors.toList());
}

private Result<List<DirectoryContents>> processSubdirectories(List<Path> directories) {
    List<Result<DirectoryContents>> subdirResults = mapDirectoriesToContents(directories);
    return collectDirectoryResults(subdirResults);
}

private List<Result<DirectoryContents>> mapDirectoriesToContents(List<Path> directories) {
    return directories.stream()
        .map(this::scanDirectory)
        .collect(Collectors.toList());
}

private Result<List<DirectoryContents>> collectDirectoryResults(List<Result<DirectoryContents>> results) {
    return Result.collect(results);
}

private FileInfo createFileInfo(Path file) {
    try {
        long size = Files.size(file);
        String contentType = Files.probeContentType(file);
        return new FileInfo(file.getFileName().toString(), size, contentType);
    } catch (IOException e) {
        return createFallbackFileInfo(file);
    }
}

private FileInfo createFallbackFileInfo(Path file) {
    return new FileInfo(file.getFileName().toString(), -1, "unknown");
}

private DirectoryContents createDirectoryContents(List<FileInfo> files, List<DirectoryContents> subdirectories) {
    return new DirectoryContents(files, subdirectories);
}

// Helper class to hold categorized paths
private static class PathCategorization {
    final List<FileInfo> files;
    final List<Path> directories;
    
    PathCategorization(List<FileInfo> files, List<Path> directories) {
        this.files = files;
        this.directories = directories;
    }
}
```

### 6. Time-based Polling

```java
Promise<JobResult> waitForJobCompletion(String jobId) {
    return pollUntilCompleted(jobId, 0, MAX_POLLS);
}

private Promise<JobResult> pollUntilCompleted(String jobId, int attemptsMade, int maxAttempts) {
    if (isMaxAttemptsReached(attemptsMade, maxAttempts)) {
        return createTimeoutFailure();
    }
    
    return checkJobStatus(jobId)
        .flatMap(status -> handleJobStatus(jobId, status, attemptsMade, maxAttempts));
}

private boolean isMaxAttemptsReached(int attemptsMade, int maxAttempts) {
    return attemptsMade >= maxAttempts;
}

private Promise<JobResult> createTimeoutFailure() {
    return Promise.failure(new TimeoutException("Job didn't complete within allocated time"));
}

private Promise<JobStatus> checkJobStatus(String jobId) {
    return Promise.lift(() -> jobService.getStatus(jobId));
}

private Promise<JobResult> handleJobStatus(String jobId, JobStatus status, int attemptsMade, int maxAttempts) {
    return switch (status.getState()) {
        case COMPLETED -> fetchJobResult(jobId);
        case FAILED -> createJobFailureResult(status);
        case RUNNING, QUEUED -> delayAndRetry(jobId, attemptsMade, maxAttempts);
        default -> createUnexpectedStateFailure(status);
    };
}

private Promise<JobResult> createJobFailureResult(JobStatus status) {
    return Promise.failure(new JobFailedException("Job failed: " + status.getErrorMessage()));
}

private Promise<JobResult> createUnexpectedStateFailure(JobStatus status) {
    return Promise.failure(new UnexpectedJobStateException("Unexpected job state: " + status.getState()));
}

private Promise<JobResult> fetchJobResult(String jobId) {
    return Promise.lift(() -> jobService.getResult(jobId));
}

private Promise<JobResult> delayAndRetry(String jobId, int attemptsMade, int maxAttempts) {
    return createDelay()
        .flatMap(ignored -> pollUntilCompleted(jobId, attemptsMade + 1, maxAttempts));
}

private Promise<Void> createDelay() {
    return Promise.delay(Duration.ofSeconds(POLL_INTERVAL_SECONDS));
}
```

### 7. Parallel Iteration

```java
Result<AggregateReport> generateReports(List<Department> departments) {
    return parallelProcessDepartments(departments)
        .map(this::combineReports);
}

private Result<List<DepartmentReport>> parallelProcessDepartments(List<Department> departments) {
    List<Result<DepartmentReport>> reportTasks = createReportTasks(departments);
    return collectReportResults(reportTasks);
}

private List<Result<DepartmentReport>> createReportTasks(List<Department> departments) {
    return departments.stream()
        .map(this::generateDepartmentReport)
        .collect(Collectors.toList());
}

private Result<List<DepartmentReport>> collectReportResults(List<Result<DepartmentReport>> tasks) {
    return Result.collect(tasks);
}

private Result<DepartmentReport> generateDepartmentReport(Department department) {
    return Result.lift(() -> reportGenerator.createReport(department));
}

private AggregateReport combineReports(List<DepartmentReport> reports) {
    double totalExpenses = calculateTotalExpenses(reports);
    double totalRevenue = calculateTotalRevenue(reports);
    
    return new AggregateReport(reports, totalExpenses, totalRevenue);
}

private double calculateTotalExpenses(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalExpenses)
        .sum();
}

private double calculateTotalRevenue(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalRevenue)
        .sum();
}
```

### 8. Integration with Other Patterns

```java
Result<ProcessingSummary> processItemsByType(List<Item> items) {
    return Result.success(items)
        .map(this::categorizeItems)
        .flatMap(this::processItemsByCategory);
}

private Map<ItemType, List<Item>> categorizeItems(List<Item> items) {
    return items.stream()
        .collect(Collectors.groupingBy(Item::getType));
}

private Result<ProcessingSummary> processItemsByCategory(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> categoryResults = createCategoryResults(categorizedItems);
    return collectCategoryResults(categoryResults);
}

private List<Result<CategoryResult>> createCategoryResults(Map<ItemType, List<Item>> categorizedItems) {
    return categorizedItems.entrySet().stream()
        .map(entry -> processSingleCategory(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
}

private Result<CategoryResult> processSingleCategory(ItemType type, List<Item> items) {
    return processCategoryItems(type, items);
}

private Result<ProcessingSummary> collectCategoryResults(List<Result<CategoryResult>> categoryResults) {
    return Result.collect(categoryResults)
        .map(this::buildSummary);
}

private Result<CategoryResult> processCategoryItems(ItemType type, List<Item> items) {
    return selectProcessingStrategy(type)
        .flatMap(strategy -> processItemsWithStrategy(strategy, items, type));
}

private Result<CategoryResult> processItemsWithStrategy(ProcessingStrategy strategy, List<Item> items, ItemType type) {
    return strategy.process(items)
        .map(results -> new CategoryResult(type, results));
}

private Result<ProcessingStrategy> selectProcessingStrategy(ItemType type) {
    return switch (type) {
        case DIGITAL -> Result.success(new DigitalItemProcessor());
        case PHYSICAL -> Result.success(new PhysicalItemProcessor());
        case SUBSCRIPTION -> Result.success(new SubscriptionProcessor());
        default -> createUnknownTypeFailure(type);
    };
}

private Result<ProcessingStrategy> createUnknownTypeFailure(ItemType type) {
    return Result.failure(Causes.of("Unknown item type: " + type));
}

private ProcessingSummary buildSummary(List<CategoryResult> results) {
    int totalProcessed = calculateTotalProcessed(results);
    Map<ItemType, Integer> countsByType = calculateCountsByType(results);
    
    return new ProcessingSummary(totalProcessed, countsByType, results);
}

private int calculateTotalProcessed(List<CategoryResult> results) {
    return results.stream()
        .mapToInt(result -> result.getResults().size())
        .sum();
}

private Map<ItemType, Integer> calculateCountsByType(List<CategoryResult> results) {
    return results.stream()
        .collect(Collectors.toMap(
            CategoryResult::getType,
            result -> result.getResults().size()
        ));
}
```

## Benefits of the Pattern

1. **Explicit Error Handling**: Errors are first-class citizens in the functional pipeline.
2. **Improved Testability**: Functions with clear inputs and outputs are easier to test.
3. **Better Readability**: Code expresses intent rather than mechanics.
4. **Maintainability**: Changes are localized to specific transformation functions.
5. **Composability**: Operations can be reused and combined in different ways.
6. **Thread Safety**: Reduced mutation leads to fewer concurrency issues.

## When to Use

- Processing collections of data
- Operations that need retries or polling
- Paginated data retrieval
- Batch processing
- Recursive traversals (directories, trees, graphs)
- Parallel or concurrent operations
- Complex workflows with potential failures

## When Not to Use

- Very simple iterations with no error handling requirements
- Performance-critical inner loops where Stream API overhead matters
- When team is unfamiliar with functional concepts and proper training isn't available

## Implementation Notes

1. Each method should operate at exactly one level of abstraction.
2. Extract complex transformations to dedicated methods.
3. Use declarative approaches over imperative ones.
4. Ensure proper error propagation through the chain.
5. Prefer immutable data flow where practical.
6. Use appropriate container types for the operation context (sync/async).

This pattern exemplifies how pragmatic functional programming principles can be applied effectively in Java to create more maintainable, composable and robust code for iterative operations, without requiring a fully functional paradigm.

User:
Please, add note, that 'collect' belongs to Java 'Stream' class.

Assistant:
# The Iteration Pattern in Pragmatic Functional Java

## Introduction

The Iteration Pattern in Pragmatic Functional Java provides a consistent approach to handling iterative operations in a functional style. It addresses operations that process collections, repetitive tasks, pagination, batching, and recursive traversals through a declarative, composable API rather than traditional imperative loops, while remaining practical and accessible to developers from varied backgrounds.

## Core Principles

1. **Declarative over Imperative**: Express what should be done rather than how to do it.
2. **Single Level of Abstraction**: Each method operates at exactly one level of abstraction.
3. **Composition over Mutation**: Combine operations through functional composition rather than state mutation.
4. **Error Propagation**: Errors flow through the composition chain with proper context.
5. **Functional Transformations**: Use map/flatMap/filter for data transformations.
6. **Practical Immutability**: Prefer immutable data flow when it improves code quality.

## Pattern Structure

The pattern typically follows this structure:

1. Begin with an input collection or initial state
2. Transform data through well-defined functions
3. Handle errors through the composition chain
4. Compose operations to form a processing pipeline
5. Return a type that represents success/failure with appropriate data

## Key Components

### 1. Container Types
- `Result<T>` - Represents synchronous operations that may succeed or fail
- `Promise<T>` - Represents asynchronous operations that may succeed or fail
- Collections wrapped in these containers

### 2. Core Operations
- `map` - Transform successful values
- `flatMap` - Chain operations that may themselves fail
- `filter` - Include only values meeting certain criteria
- `collect` - A method belonging to Java's `Stream` class that gathers stream elements into a collection or other result container
- `Result.collect` - A custom method that gathers many results into a single result

### 3. Processing Styles
- **Collection Processing** - Transforming entire collections at once
- **Recursive Processing** - Self-similar operations on nested structures
- **Paginated/Batched Processing** - Processing data in chunks
- **Polled/Retry Processing** - Operations attempted repeatedly

## Implementation Examples

### 1. Collection Transformation

```java
Result<TaxReport> calculateTaxes(List<Invoice> invoices) {
    return Result.success(invoices)
        .map(this::processInvoicesForTax)
        .map(this::createTaxReport);
}

private List<TaxEntry> processInvoicesForTax(List<Invoice> invoices) {
    return invoices.stream()
        .map(this::calculateTaxForInvoice)
        .collect(Collectors.toList());
}

private TaxEntry calculateTaxForInvoice(Invoice invoice) {
    double taxAmount = invoice.getTotal() * getTaxRate(invoice.getRegion());
    return new TaxEntry(invoice.getId(), taxAmount, invoice.getRegion());
}

private TaxReport createTaxReport(List<TaxEntry> entries) {
    double totalTax = calculateTotalTax(entries);
    return new TaxReport(entries, totalTax, LocalDate.now());
}

private double calculateTotalTax(List<TaxEntry> entries) {
    return entries.stream()
        .mapToDouble(TaxEntry::getAmount)
        .sum();
}
```

### 2. Retry Pattern

```java
Result<DataFeed> fetchExternalData(String source) {
    return retryFetchOperation(source)
        .map(this::parseDataFeed);
}

private Result<String> retryFetchOperation(String source) {
    return Retry.withMaxAttempts(3)
        .withBackoff(Duration.ofSeconds(1), 2.0)
        .withErrorFilter(this::isRetryableError)
        .execute(() -> fetchFromExternalSource(source));
}

private boolean isRetryableError(Throwable error) {
    Set<Class<? extends Throwable>> retryableExceptions = 
        Set.of(TimeoutException.class, ConnectionException.class);
        
    return retryableExceptions.contains(error.getClass()) || 
           isRetryableHttpError(error);
}

private boolean isRetryableHttpError(Throwable error) {
    return error instanceof HttpException && 
           ((HttpException)error).getStatusCode() >= 500;
}

private Result<String> fetchFromExternalSource(String source) {
    return Result.lift(() -> externalApiClient.fetch(source));
}

private DataFeed parseDataFeed(String rawData) {
    return DataFeed.fromJson(rawData);
}
```

### 3. Paginated Data Retrieval

```java
Result<List<Customer>> getAllCustomers() {
    return fetchAllCustomerPages(0, new ArrayList<>());
}

private Result<List<Customer>> fetchAllCustomerPages(int page, List<Customer> accumulated) {
    return fetchCustomerPage(page)
        .flatMap(pageResult -> processPageResult(pageResult, page, accumulated));
}

private Result<List<Customer>> processPageResult(PageResult<Customer> pageResult, int currentPage, List<Customer> accumulated) {
    List<Customer> updatedList = combineResults(accumulated, pageResult.getItems());
    return continueOrFinish(pageResult, currentPage, updatedList);
}

private Result<List<Customer>> continueOrFinish(PageResult<Customer> pageResult, int currentPage, List<Customer> updatedList) {
    if (pageResult.hasMorePages()) {
        return fetchAllCustomerPages(currentPage + 1, updatedList);
    } else {
        return Result.success(updatedList);
    }
}

private List<Customer> combineResults(List<Customer> existing, List<Customer> newItems) {
    List<Customer> combined = new ArrayList<>(existing);
    combined.addAll(newItems);
    return combined;
}

private Result<PageResult<Customer>> fetchCustomerPage(int page) {
    return Result.lift(() -> customerRepository.getPage(page, PAGE_SIZE));
}
```

### 4. Batch Processing

```java
Result<BatchSummary> processBatch(List<Transaction> transactions) {
    return Result.success(transactions)
        .map(this::partitionIntoBatches)
        .flatMap(this::processAllBatches)
        .map(this::createBatchSummary);
}

private List<List<Transaction>> partitionIntoBatches(List<Transaction> transactions) {
    return ListUtils.partition(transactions, BATCH_SIZE);
}

private Result<List<BatchResult>> processAllBatches(List<List<Transaction>> batches) {
    List<Result<BatchResult>> batchResults = mapBatchesToResults(batches);
    return collectResults(batchResults);
}

private List<Result<BatchResult>> mapBatchesToResults(List<List<Transaction>> batches) {
    return batches.stream()
        .map(this::processSingleBatch)
        .collect(Collectors.toList());
}

private Result<BatchResult> processSingleBatch(List<Transaction> batch) {
    return transactionProcessor.process(batch)
        .map(results -> new BatchResult(batch.size(), results));
}

private Result<List<BatchResult>> collectResults(List<Result<BatchResult>> results) {
    return Result.collect(results);
}

private BatchSummary createBatchSummary(List<BatchResult> batchResults) {
    int totalProcessed = calculateTotalProcessed(batchResults);
    return new BatchSummary(totalProcessed, batchResults.size());
}

private int calculateTotalProcessed(List<BatchResult> batchResults) {
    return batchResults.stream()
        .mapToInt(BatchResult::getProcessedCount)
        .sum();
}
```

### 5. Recursive Operations

```java
Result<DirectoryContents> scanDirectory(Path directory) {
    return validateDirectory(directory)
        .flatMap(this::readDirectoryContents);
}

private Result<Path> validateDirectory(Path directory) {
    return Result.lift(() -> validateDirectoryExists(directory));
}

private Path validateDirectoryExists(Path directory) throws IOException {
    if (!Files.exists(directory)) {
        throw new DirectoryNotFoundException("Directory not found: " + directory);
    }
    if (!Files.isDirectory(directory)) {
        throw new NotDirectoryException("Path is not a directory: " + directory);
    }
    return directory;
}

private Result<DirectoryContents> readDirectoryContents(Path directory) {
    return listDirectoryContents(directory)
        .flatMap(this::processPathStream);
}

private Result<Stream<Path>> listDirectoryContents(Path directory) {
    return Result.lift(() -> Files.list(directory));
}

private Result<DirectoryContents> processPathStream(Stream<Path> paths) {
    try (paths) {
        List<Path> pathList = collectPathsFromStream(paths);
        return categorizeAndProcessPaths(pathList);
    }
}

private List<Path> collectPathsFromStream(Stream<Path> paths) {
    return paths.collect(Collectors.toList());
}

private Result<DirectoryContents> categorizeAndProcessPaths(List<Path> paths) {
    PathCategorization categorization = categorizePaths(paths);
    return processSubdirectories(categorization.directories)
        .map(subdirs -> createDirectoryContents(categorization.files, subdirs));
}

private PathCategorization categorizePaths(List<Path> paths) {
    Map<Boolean, List<Path>> categorized = partitionPathsByFileType(paths);
    List<FileInfo> files = convertPathsToFileInfos(categorized.get(true));
    List<Path> directories = filterDirectories(categorized.get(false));
    
    return new PathCategorization(files, directories);
}

private Map<Boolean, List<Path>> partitionPathsByFileType(List<Path> paths) {
    return paths.stream()
        .collect(Collectors.partitioningBy(Files::isRegularFile));
}

private List<FileInfo> convertPathsToFileInfos(List<Path> filePaths) {
    return filePaths.stream()
        .map(this::createFileInfo)
        .collect(Collectors.toList());
}

private List<Path> filterDirectories(List<Path> nonFilePaths) {
    return nonFilePaths.stream()
        .filter(Files::isDirectory)
        .collect(Collectors.toList());
}

private Result<List<DirectoryContents>> processSubdirectories(List<Path> directories) {
    List<Result<DirectoryContents>> subdirResults = mapDirectoriesToContents(directories);
    return collectDirectoryResults(subdirResults);
}

private List<Result<DirectoryContents>> mapDirectoriesToContents(List<Path> directories) {
    return directories.stream()
        .map(this::scanDirectory)
        .collect(Collectors.toList());
}

private Result<List<DirectoryContents>> collectDirectoryResults(List<Result<DirectoryContents>> results) {
    return Result.collect(results);
}

private FileInfo createFileInfo(Path file) {
    try {
        long size = Files.size(file);
        String contentType = Files.probeContentType(file);
        return new FileInfo(file.getFileName().toString(), size, contentType);
    } catch (IOException e) {
        return createFallbackFileInfo(file);
    }
}

private FileInfo createFallbackFileInfo(Path file) {
    return new FileInfo(file.getFileName().toString(), -1, "unknown");
}

private DirectoryContents createDirectoryContents(List<FileInfo> files, List<DirectoryContents> subdirectories) {
    return new DirectoryContents(files, subdirectories);
}

// Helper class to hold categorized paths
private static class PathCategorization {
    final List<FileInfo> files;
    final List<Path> directories;
    
    PathCategorization(List<FileInfo> files, List<Path> directories) {
        this.files = files;
        this.directories = directories;
    }
}
```

### 6. Time-based Polling

```java
Promise<JobResult> waitForJobCompletion(String jobId) {
    return pollUntilCompleted(jobId, 0, MAX_POLLS);
}

private Promise<JobResult> pollUntilCompleted(String jobId, int attemptsMade, int maxAttempts) {
    if (isMaxAttemptsReached(attemptsMade, maxAttempts)) {
        return createTimeoutFailure();
    }
    
    return checkJobStatus(jobId)
        .flatMap(status -> handleJobStatus(jobId, status, attemptsMade, maxAttempts));
}

private boolean isMaxAttemptsReached(int attemptsMade, int maxAttempts) {
    return attemptsMade >= maxAttempts;
}

private Promise<JobResult> createTimeoutFailure() {
    return Promise.failure(new TimeoutException("Job didn't complete within allocated time"));
}

private Promise<JobStatus> checkJobStatus(String jobId) {
    return Promise.lift(() -> jobService.getStatus(jobId));
}

private Promise<JobResult> handleJobStatus(String jobId, JobStatus status, int attemptsMade, int maxAttempts) {
    return switch (status.getState()) {
        case COMPLETED -> fetchJobResult(jobId);
        case FAILED -> createJobFailureResult(status);
        case RUNNING, QUEUED -> delayAndRetry(jobId, attemptsMade, maxAttempts);
        default -> createUnexpectedStateFailure(status);
    };
}

private Promise<JobResult> createJobFailureResult(JobStatus status) {
    return Promise.failure(new JobFailedException("Job failed: " + status.getErrorMessage()));
}

private Promise<JobResult> createUnexpectedStateFailure(JobStatus status) {
    return Promise.failure(new UnexpectedJobStateException("Unexpected job state: " + status.getState()));
}

private Promise<JobResult> fetchJobResult(String jobId) {
    return Promise.lift(() -> jobService.getResult(jobId));
}

private Promise<JobResult> delayAndRetry(String jobId, int attemptsMade, int maxAttempts) {
    return createDelay()
        .flatMap(ignored -> pollUntilCompleted(jobId, attemptsMade + 1, maxAttempts));
}

private Promise<Void> createDelay() {
    return Promise.delay(Duration.ofSeconds(POLL_INTERVAL_SECONDS));
}
```

### 7. Parallel Iteration

```java
Result<AggregateReport> generateReports(List<Department> departments) {
    return parallelProcessDepartments(departments)
        .map(this::combineReports);
}

private Result<List<DepartmentReport>> parallelProcessDepartments(List<Department> departments) {
    List<Result<DepartmentReport>> reportTasks = createReportTasks(departments);
    return collectReportResults(reportTasks);
}

private List<Result<DepartmentReport>> createReportTasks(List<Department> departments) {
    return departments.stream()
        .map(this::generateDepartmentReport)
        .collect(Collectors.toList());
}

private Result<List<DepartmentReport>> collectReportResults(List<Result<DepartmentReport>> tasks) {
    return Result.collect(tasks);
}

private Result<DepartmentReport> generateDepartmentReport(Department department) {
    return Result.lift(() -> reportGenerator.createReport(department));
}

private AggregateReport combineReports(List<DepartmentReport> reports) {
    double totalExpenses = calculateTotalExpenses(reports);
    double totalRevenue = calculateTotalRevenue(reports);
    
    return new AggregateReport(reports, totalExpenses, totalRevenue);
}

private double calculateTotalExpenses(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalExpenses)
        .sum();
}

private double calculateTotalRevenue(List<DepartmentReport> reports) {
    return reports.stream()
        .mapToDouble(DepartmentReport::getTotalRevenue)
        .sum();
}
```

### 8. Integration with Other Patterns

```java
Result<ProcessingSummary> processItemsByType(List<Item> items) {
    return Result.success(items)
        .map(this::categorizeItems)
        .flatMap(this::processItemsByCategory);
}

private Map<ItemType, List<Item>> categorizeItems(List<Item> items) {
    return items.stream()
        .collect(Collectors.groupingBy(Item::getType));
}

private Result<ProcessingSummary> processItemsByCategory(Map<ItemType, List<Item>> categorizedItems) {
    List<Result<CategoryResult>> categoryResults = createCategoryResults(categorizedItems);
    return collectCategoryResults(categoryResults);
}

private List<Result<CategoryResult>> createCategoryResults(Map<ItemType, List<Item>> categorizedItems) {
    return categorizedItems.entrySet().stream()
        .map(entry -> processSingleCategory(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
}

private Result<CategoryResult> processSingleCategory(ItemType type, List<Item> items) {
    return processCategoryItems(type, items);
}

private Result<ProcessingSummary> collectCategoryResults(List<Result<CategoryResult>> categoryResults) {
    return Result.collect(categoryResults)
        .map(this::buildSummary);
}

private Result<CategoryResult> processCategoryItems(ItemType type, List<Item> items) {
    return selectProcessingStrategy(type)
        .flatMap(strategy -> processItemsWithStrategy(strategy, items, type));
}

private Result<CategoryResult> processItemsWithStrategy(ProcessingStrategy strategy, List<Item> items, ItemType type) {
    return strategy.process(items)
        .map(results -> new CategoryResult(type, results));
}

private Result<ProcessingStrategy> selectProcessingStrategy(ItemType type) {
    return switch (type) {
        case DIGITAL -> Result.success(new DigitalItemProcessor());
        case PHYSICAL -> Result.success(new PhysicalItemProcessor());
        case SUBSCRIPTION -> Result.success(new SubscriptionProcessor());
        default -> createUnknownTypeFailure(type);
    };
}

private Result<ProcessingStrategy> createUnknownTypeFailure(ItemType type) {
    return Result.failure(Causes.of("Unknown item type: " + type));
}

private ProcessingSummary buildSummary(List<CategoryResult> results) {
    int totalProcessed = calculateTotalProcessed(results);
    Map<ItemType, Integer> countsByType = calculateCountsByType(results);
    
    return new ProcessingSummary(totalProcessed, countsByType, results);
}

private int calculateTotalProcessed(List<CategoryResult> results) {
    return results.stream()
        .mapToInt(result -> result.getResults().size())
        .sum();
}

private Map<ItemType, Integer> calculateCountsByType(List<CategoryResult> results) {
    return results.stream()
        .collect(Collectors.toMap(
            CategoryResult::getType,
            result -> result.getResults().size()
        ));
}
```

## Benefits of the Pattern

1. **Explicit Error Handling**: Errors are first-class citizens in the functional pipeline.
2. **Improved Testability**: Functions with clear inputs and outputs are easier to test.
3. **Better Readability**: Code expresses intent rather than mechanics.
4. **Maintainability**: Changes are localized to specific transformation functions.
5. **Composability**: Operations can be reused and combined in different ways.
6. **Thread Safety**: Reduced mutation leads to fewer concurrency issues.

## When to Use

- Processing collections of data
- Operations that need retries or polling
- Paginated data retrieval
- Batch processing
- Recursive traversals (directories, trees, graphs)
- Parallel or concurrent operations
- Complex workflows with potential failures

## When Not to Use

- Very simple iterations with no error handling requirements
- Performance-critical inner loops where Stream API overhead matters
- When team is unfamiliar with functional concepts and proper training isn't available

## Implementation Notes

1. Each method should operate at exactly one level of abstraction.
2. Extract complex transformations to dedicated methods.
3. Use declarative approaches over imperative ones.
4. Ensure proper error propagation through the chain.
5. Prefer immutable data flow where practical.
6. Use appropriate container types for the operation context (sync/async).

This pattern exemplifies how pragmatic functional programming principles can be applied effectively in Java to create more maintainable, composable and robust code for iterative operations, without requiring a fully functional paradigm.

User:
Example for recursive operations is overcomplicated. Simplify it, please.

Assistant:
# Simplified Recursive Operations Example

Let me provide a simplified example of recursive operations using the Iteration Pattern in Pragmatic Functional Java.

```java
Result<DirectoryContents> scanDirectory(Path directory) {
    return validateDirectory(directory)
        .flatMap(this::readDirectoryContents);
}

private Result<Path> validateDirectory(Path directory) {
    return Result.lift(() -> {
        if (!Files.exists(directory)) {
            throw new IOException("Directory not found: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("Path is not a directory: " + directory);
        }
        return directory;
    });
}

private Result<DirectoryContents> readDirectoryContents(Path directory) {
    return Result.lift(() -> Files.list(directory))
        .flatMap(this::processDirectoryContents);
}

private Result<DirectoryContents> processDirectoryContents(Stream<Path> paths) {
    try (paths) {
        List<Path> pathList = paths.collect(Collectors.toList());
        
        // Separate files and directories
        List<Path> files = pathList.stream()
            .filter(Files::isRegularFile)
            .collect(Collectors.toList());
            
        List<Path> directories = pathList.stream()
            .filter(Files::isDirectory)
            .collect(Collectors.toList());
        
        // Process files
        List<FileInfo> fileInfos = files.stream()
            .map(this::createFileInfo)
            .collect(Collectors.toList());
        
        // Recursively process subdirectories
        return processSubdirectories(directories)
            .map(subdirs -> new DirectoryContents(fileInfos, subdirs));
    }
}

private FileInfo createFileInfo(Path file) {
    try {
        long size = Files.size(file);
        String name = file.getFileName().toString();
        return new FileInfo(name, size);
    } catch (IOException e) {
        // Fallback if we can't get the size
        return new FileInfo(file.getFileName().toString(), -1);
    }
}

private Result<List<DirectoryContents>> processSubdirectories(List<Path> directories) {
    // Map each directory to its contents and collect results
    List<Result<DirectoryContents>> results = directories.stream()
        .map(this::scanDirectory)
        .collect(Collectors.toList());
    
    // Combine all results into a single Result
    return Result.collect(results);
}

// Simple value classes
class FileInfo {
    private final String name;
    private final long size;
    
    FileInfo(String name, long size) {
        this.name = name;
        this.size = size;
    }
    
    // Getters
    public String getName() { return name; }
    public long getSize() { return size; }
}

class DirectoryContents {
    private final List<FileInfo> files;
    private final List<DirectoryContents> subdirectories;
    
    DirectoryContents(List<FileInfo> files, List<DirectoryContents> subdirectories) {
        this.files = files;
        this.subdirectories = subdirectories;
    }
    
    // Getters
    public List<FileInfo> getFiles() { return files; }
    public List<DirectoryContents> getSubdirectories() { return subdirectories; }
}
```

## Key Aspects of this Simplified Example

1. **Clear Recursion Point**: The `scanDirectory` method calls itself indirectly through `processSubdirectories`.

2. **Functional Composition**: Operations are chained using `flatMap` and `map`.

3. **Error Handling**: Errors are propagated through the `Result` type.

4. **Collecting Results**: The `collect` method from Java's `Stream` class is used to gather elements into collections.

5. **Clear Separation of Concerns**:
    - Directory validation
    - Reading directory contents
    - Processing files
    - Recursively processing subdirectories

6. **Resource Management**: The stream is properly closed using try-with-resources.

This example demonstrates the core principles of the Iteration Pattern in a more concise way while maintaining its functional approach to recursive directory traversal.

User:
Rewrite it from scratch, use different problem to illustrate recursion.

Assistant:
# Recursive Operations Example: Parsing JSON Objects

Here's a new example illustrating recursion using the Iteration Pattern to parse and transform a nested JSON structure into a domain model.

```java
/**
 * Parses a JSON string into a hierarchical Category structure
 */
Result<Category> parseCategories(String json) {
    return parseJsonToMap(json)
        .flatMap(this::buildCategoryTree);
}

/**
 * Safely converts the raw JSON string to a Map
 */
private Result<Map<String, Object>> parseJsonToMap(String json) {
    return Result.lift(() -> {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    });
}

/**
 * Builds the root Category from the parsed JSON map
 */
private Result<Category> buildCategoryTree(Map<String, Object> rootMap) {
    return extractRequiredString(rootMap, "id")
        .flatMap(id -> extractRequiredString(rootMap, "name")
            .flatMap(name -> processSubcategories(rootMap)
                .map(subcategories -> new Category(id, name, subcategories))));
}

/**
 * Safely extracts a required string value from a map
 */
private Result<String> extractRequiredString(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
        return Result.failure(new ParseException("Missing required field: " + key));
    }
    if (!(value instanceof String)) {
        return Result.failure(new ParseException("Field is not a string: " + key));
    }
    return Result.success((String) value);
}

/**
 * Processes the subcategories array from the map
 */
private Result<List<Category>> processSubcategories(Map<String, Object> parentMap) {
    // Return empty list if no subcategories
    if (!parentMap.containsKey("subcategories")) {
        return Result.success(Collections.emptyList());
    }
    
    Object subcategoriesObj = parentMap.get("subcategories");
    if (!(subcategoriesObj instanceof List)) {
        return Result.failure(new ParseException("Subcategories is not an array"));
    }
    
    List<?> subcategoriesList = (List<?>) subcategoriesObj;
    return parseSubcategories(subcategoriesList);
}

/**
 * Recursively parses each subcategory
 */
private Result<List<Category>> parseSubcategories(List<?> subcategoriesList) {
    // Convert each item to a Category and collect the results
    List<Result<Category>> categoryResults = subcategoriesList.stream()
        .map(this::parseSubcategory)
        .collect(Collectors.toList());
    
    // Combine the list of Results into a single Result of a list
    return Result.collect(categoryResults);
}

/**
 * Parse a single subcategory
 */
private Result<Category> parseSubcategory(Object item) {
    if (!(item instanceof Map)) {
        return Result.failure(new ParseException("Subcategory is not an object"));
    }
    
    // Recursive call to build each subcategory
    Map<String, Object> subcategoryMap = (Map<String, Object>) item;
    return buildCategoryTree(subcategoryMap);
}

/**
 * Domain model for categories
 */
class Category {
    private final String id;
    private final String name;
    private final List<Category> subcategories;
    
    Category(String id, String name, List<Category> subcategories) {
        this.id = id;
        this.name = name;
        this.subcategories = subcategories;
    }
    
    // Getters
    public String getId() { return id; }
    public String getName() { return name; }
    public List<Category> getSubcategories() { return subcategories; }
    
    // Helper method to find a category by ID anywhere in the tree
    public Optional<Category> findById(String searchId) {
        if (id.equals(searchId)) {
            return Optional.of(this);
        }
        
        // Search through subcategories (depth-first)
        return subcategories.stream()
            .map(sub -> sub.findById(searchId))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst();
    }
    
    // Calculate the total number of categories (including this one)
    public int getTotalCount() {
        return 1 + subcategories.stream()
            .mapToInt(Category::getTotalCount)
            .sum();
    }
}

/**
 * Custom exception for parse errors
 */
class ParseException extends Exception {
    ParseException(String message) {
        super(message);
    }
}
```

## Example JSON Input

```json
{
  "id": "electronics",
  "name": "Electronics",
  "subcategories": [
    {
      "id": "computers",
      "name": "Computers",
      "subcategories": [
        {
          "id": "laptops",
          "name": "Laptops",
          "subcategories": []
        },
        {
          "id": "desktops",
          "name": "Desktop Computers",
          "subcategories": []
        }
      ]
    },
    {
      "id": "phones",
      "name": "Mobile Phones",
      "subcategories": [
        {
          "id": "smartphones",
          "name": "Smartphones",
          "subcategories": []
        },
        {
          "id": "accessories",
          "name": "Phone Accessories",
          "subcategories": []
        }
      ]
    }
  ]
}
```

## How to Use the Parser

```java
String jsonInput = "..."; // JSON content from file or API
Result<Category> result = categoryParser.parseCategories(jsonInput);

result.onSuccess(categoryTree -> {
    System.out.println("Successfully parsed " + categoryTree.getTotalCount() + " categories");
    
    // Find a specific category
    categoryTree.findById("smartphones").ifPresent(smartphone -> {
        System.out.println("Found category: " + smartphone.getName());
    });
}).onFailure(error -> {
    System.err.println("Failed to parse categories: " + error.getMessage());
});
```

## Key Aspects of this Recursive Example

1. **Recursive Structure**: The category tree is built by recursively parsing subcategories.

2. **Functional Approach**: Uses `flatMap` and `map` to chain operations and build the result.

3. **Error Handling**: Uses the `Result` type to propagate errors through the processing chain.

4. **Collection Processing**: The `collect` method from Java's `Stream` class gathers elements into collections.

5. **Result Aggregation**: `Result.collect()` combines multiple Result objects into a single Result.

6. **Domain Model**: The `Category` class itself has recursive methods (`findById` and `getTotalCount`).

7. **Type Safety**: Uses safe type checking and conversion when working with the untyped JSON data.

This example demonstrates the power of the Iteration Pattern when dealing with hierarchical data structures, showing how recursion can be implemented in a functional style while maintaining proper error handling.

User:
Please, check last examples for single level of abstraction.

Assistant:
# Revised JSON Category Parser with Single Level of Abstraction

After reviewing your code, I notice it violates the Single Level of Abstraction Principle (SLAP), which states that functions should operate at only one level of abstraction. I'll rewrite it with cleaner separation of concerns.

```java
/**
 * Class responsible for parsing JSON into a Category hierarchy
 */
public class CategoryParser {
    private final ObjectMapper mapper = new ObjectMapper();
    
    /**
     * Main entry point to parse a JSON string into a Category hierarchy
     */
    public Result<Category> parseCategories(String json) {
        return parseJsonToMap(json)
            .flatMap(this::buildCategoryTree);
    }
    
    /**
     * Parses raw JSON into a Map
     */
    private Result<Map<String, Object>> parseJsonToMap(String json) {
        return Result.lift(() -> 
            mapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
    }
    
    /**
     * Builds a category tree from the root map
     */
    private Result<Category> buildCategoryTree(Map<String, Object> rootMap) {
        return extractCategoryData(rootMap)
            .flatMap(this::createCategory);
    }
    
    /**
     * Extracts required data for a category from a map
     */
    private Result<CategoryData> extractCategoryData(Map<String, Object> map) {
        Result<String> idResult = extractRequiredString(map, "id");
        Result<String> nameResult = extractRequiredString(map, "name");
        Result<List<?>> subcategoriesResult = extractSubcategoriesList(map);
        
        return Result.combine(idResult, nameResult, subcategoriesResult, CategoryData::new);
    }
    
    /**
     * Extracts subcategories list from a map
     */
    private Result<List<?>> extractSubcategoriesList(Map<String, Object> map) {
        if (!map.containsKey("subcategories")) {
            return Result.success(Collections.emptyList());
        }
        
        Object subcategoriesObj = map.get("subcategories");
        if (!(subcategoriesObj instanceof List)) {
            return Result.failure(new ParseException("Subcategories is not an array"));
        }
        
        return Result.success((List<?>) subcategoriesObj);
    }
    
    /**
     * Creates a Category from extracted data
     */
    private Result<Category> createCategory(CategoryData data) {
        return parseSubcategories(data.subcategoriesList)
            .map(subcategories -> new Category(data.id, data.name, subcategories));
    }
    
    /**
     * Extracts a required string from a map
     */
    private Result<String> extractRequiredString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return Result.failure(new ParseException("Missing required field: " + key));
        }
        if (!(value instanceof String)) {
            return Result.failure(new ParseException("Field is not a string: " + key));
        }
        return Result.success((String) value);
    }
    
    /**
     * Parses subcategories from a list
     */
    private Result<List<Category>> parseSubcategories(List<?> subcategoriesList) {
        List<Result<Category>> categoryResults = new ArrayList<>();
        
        for (Object item : subcategoriesList) {
            categoryResults.add(parseSubcategory(item));
        }
        
        return Result.collect(categoryResults);
    }
    
    /**
     * Parses a single subcategory from an object
     */
    private Result<Category> parseSubcategory(Object item) {
        if (!(item instanceof Map)) {
            return Result.failure(new ParseException("Subcategory is not an object"));
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> subcategoryMap = (Map<String, Object>) item;
        return buildCategoryTree(subcategoryMap);
    }
    
    /**
     * Data class to hold temporary category data during parsing
     */
    private static class CategoryData {
        final String id;
        final String name;
        final List<?> subcategoriesList;
        
        CategoryData(String id, String name, List<?> subcategoriesList) {
            this.id = id;
            this.name = name;
            this.subcategoriesList = subcategoriesList;
        }
    }
}

/**
 * Domain model for categories
 */
class Category {
    private final String id;
    private final String name;
    private final List<Category> subcategories;
    
    Category(String id, String name, List<Category> subcategories) {
        this.id = id;
        this.name = name;
        this.subcategories = Collections.unmodifiableList(subcategories);
    }
    
    public String getId() { 
        return id; 
    }
    
    public String getName() { 
        return name; 
    }
    
    public List<Category> getSubcategories() { 
        return subcategories; 
    }
    
    public Optional<Category> findById(String searchId) {
        if (id.equals(searchId)) {
            return Optional.of(this);
        }
        
        return subcategories.stream()
            .map(sub -> sub.findById(searchId))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst();
    }
    
    public int getTotalCount() {
        return 1 + subcategories.stream()
            .mapToInt(Category::getTotalCount)
            .sum();
    }
}

/**
 * Custom exception for parse errors
 */
class ParseException extends Exception {
    ParseException(String message) {
        super(message);
    }
}

/**
 * Result type for error handling
 */
interface Result<T> {
    // Methods for Result implementation
    // ...
}
```

## Key Improvements for Single Level of Abstraction

1. **Smaller, Focused Methods**: Each method does exactly one thing at one level of abstraction.

2. **Data Transfer Object**: Added a `CategoryData` class to hold intermediate parsing results.

3. **Separated Concerns**:
    - JSON parsing
    - Data extraction
    - Data validation
    - Object creation

4. **Result Combination**: Used `Result.combine()` to merge multiple result operations.

5. **Clear Method Names**: Each method's name clearly describes what it does.

6. **Consistent Error Handling**: The `Result` type is used consistently throughout.

7. **Immutability**: The `Category` class is immutable with defensive copying.

8. **Clear Class Responsibilities**:
    - `CategoryParser`: Handles parsing JSON into domain objects
    - `Category`: Domain model with its own business logic
    - `ParseException`: Specific exception for parsing errors
