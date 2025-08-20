### **Introduction To Pragmatic Functional Java**

The Pragmatic Functional Java (PFJ) is an attempt to define a new idiomatic Java coding style. Coding style, which will completely utilize all features of current and upcoming Java versions. Coding style, which will involve compiler to help writing concise yet reliable and readable code.

While this style can be used even with Java 8, with Java 11 it looks much cleaner and concise. It gets even more expressive with Java 17 and benefits from every new Java language feature.

But PFJ is not a free lunch, it requires significant changes in developers’ habits and approaches. Changing habits is not easy, traditional imperative ones are especially hard to tackle.

Is it worth it? Definitely\! PFJ code is concise, expressive and reliable, easy to read and maintain. In most cases, if code compiles — it works\!

(This text is an integral part of the [Pragmatica](https://github.com/siy/pragmatica) library).

#### **Elements Of Pragmatic Functional Java**

PFJ is derived from the wonderful [Effective Java](https://www.amazon.com/dp/0134685997/) book with some additional concepts and conventions, in particular, derived from Functional Programming.

Note that despite the use of FP concepts, PFJ does not try to enforce FP-specific terminology. (Although references are provided for those who are interested in exploring those concepts further).

PFJ focuses on:

* reducing mental overhead
* improving code reliability
* improving long-term maintainability
* involving compiler to help write correct code
* making writing correct code easy and natural; writing incorrect code, while still possible, should require effort

Despite ambitious goals, there are only two key PFJ rules:

* Avoid `null` as much as possible
* No business exceptions

Below, each key rule is explored in more details:

#### **Avoid `null` As Much As Possible (ANAMAP rule)**

Nullability of variables is one of the [Special States](https://dev.to/siy/leveraging-java-type-system-to-represent-special-states-688). They are a well-known source of run-time errors and boilerplate code. To eliminate these issues and represent values which can be missing, PFJ uses the Option container. This covers all cases when such a value may appear — return values, input parameters or fields.

In some cases, for example, for performance or compatibility with existing frameworks reasons, classes may use `null` internally. These cases must be clearly documented and invisible to class users, i.e., all class APIs should use `Option<T>`.

This approach has several advantages:

* Nullable variables are immediately visible in code. No need to read documentation/check source code/rely on annotations.
* The compiler distinguishes nullable and non-nullable variables and prevents incorrect assignments between them.
* All boilerplate necessary for `null` checks is eliminated.

#### **No Business Exceptions (NBE rule)**

PFJ uses exceptions only to represent cases of fatal, unrecoverable (technical) failures. Such an exception might be intercepted only for purposes of logging and/or graceful shutdown of the application. All other exceptions and their interception are discouraged and avoided as much as possible.

Business exceptions are another case of [Special States](https://dev.to/siy/leveraging-java-type-system-to-represent-special-states-688). For propagation and handling of business level errors, PFJ uses [Result](https://github.com/siy/pragmatica/blob/main/core/src/main/java/org/pfj/lang/Result.java) container.

Again, this covers all cases when error may appear — return values, input parameters or fields. Practice shows that fields rarely (if ever) need to use this container.

There are no justified cases when business level exceptions can be used. Interfacing with existing Java libraries and legacy code performed via dedicated wrapping methods. The [Result](https://github.com/siy/pragmatica/blob/main/core/src/main/java/org/pfj/lang/Result.java) container contains an implementation of these wrapping methods.

The `No Business Exceptions` rule provides the following advantages:

* Methods which can return error are immediately visible in code. No need to read documentation/check source code/analyze call tree to check which exceptions can be thrown and under which conditions.
* The compiler enforces proper error handling and propagation.
* Virtually zero boilerplate for error handling and propagation.
* Code can be written for *happy day scenarios* and errors handled at the point where this is most convenient — the original intent of exceptions, which was never actually achieved.
* Code remains composable, easy to read and reason about, no hidden breaks or unexpected transitions in the execution flow — *what you read is what will be executed*.

#### **Transforming Legacy Code Into PFJ Style Code**

OK, key rules seems looking good and useful, but how real code will look?

Let’s start from quite typical backend code:
```java
public interface UserRepository {  
   User findById(User.Id userId);  
}

public interface UserProfileRepository {  
   UserProfile findById(User.Id userId);  
}

public class UserService {  
   private final UserRepository userRepository;  
   private final UserProfileRepository userProfileRepository;

   public UserWithProfile getUserWithProfile(User.Id userId) {  
       User user = userRepository.findById(userId);

       if (user == null) {  
           throw UserNotFoundException("User with ID " \+ userId \+ " not found");  
       }

       UserProfile details = userProfileRepository.findById(userId);

       return UserWithProfile.of(user, details == null  
           ? UserProfile.defaultDetails()  
           : details);  
   }  
}
```
Interfaces at the beginning of the example are provided for context clarity.

The main point of interest is the `getUserWithProfile` method. Let's analyze it step by step.

* The first statement retrieves the `user` variable from the user repository.
* Since user may not be present in the repository, `user` variable might be `null`. The following `null` check verifies if this is the case and throws a business exception if yes.
* The next step is the retrieval of the user profile details. Lack of details is not considered an error. Instead, when details are missing, then defaults are used for the profile.

The code above has several issues in it. First, returning `null` in case if value is not present in the repository is not obvious from the interface. We need to check documentation, look into implementation or make a guess how these repositories work. Sometimes annotations are used to provide a hint, but this still does not guarantee API behavior.

To address this issue, let’s apply *ANAMAP* rule to the repositories:
```java
public interface UserRepository {  
   Option<User> findById(User.Id userId);  
}

public interface UserProfileRepository {  
   Option<UserProfile> findById(User.Id userId);  
}
```
Now, there is no need to make any guesses — the API explicitly tells us that the returned value may not be present.

Now let’s take a look into the`getUserWithProfilethe⁣` method again. The second thing to note is that the method may return a value or may throw an exception. This is a business exception, so we can apply *NBE* rule. The main goal of the change \- make the fact that a method may return value *OR* error explicit:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {
```
OK, now we have API’s cleaned up and can start changing the code. The first change will be caused by the fact, that `userRepository` now returns `Option<User>`:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {  
   Option<User> user = userRepository.findById(userId);  
}
```
Now we need to check if the user is present and if not, return an error. With traditional imperative approach, code should look like this:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {  
   Option<User> user = userRepository.findById(userId);

   if (user.isEmpty()) {  
       return Result.failure(Causes.cause("User with ID " \+ userId \+ " not found"));  
   }  
}
```
The code does not look very appealing, but it is not worse than the original either, so let’s keep it for now as is.

The next step is to try to convert the remaining parts of code:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {  
   Option<User> user = userRepository.findById(userId);

   if (user.isEmpty()) {  
       return Result.failure(Causes.cause("User with ID " \+ userId \+ " not found"));  
   }

   Option<UserProfile> details = userProfileRepository.findById(userId);  
}
```
Here comes the catch: details and user are stored inside `Option<T>` containers, so to assemble `UserWithProfile` we need to somehow extract values. Here could be different approaches, for example, use `Option.fold()` method. The resulting code will definitely not be pretty, and most likely will violate *ANAMAP* rule.

There is another approach — use the fact that `Option<T>` is a container with special properties. In particular, it is possible to transform value inside `Option<T>` using `Option.map()` and `Option.flatMap()` methods. Also, we know that the`details` value will be, either, provided by the repository or replaced with default. For this, we can use `Option.or()` a method to extract details from container. Let's try these approaches:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {  
   Option<User> user = userRepository.findById(userId);  
        
   if (user.isEmpty()) {  
       return Result.failure(Causes.cause("User with ID " \+ userId \+ " not found"));  
   }

   UserProfile details = userProfileRepository.findById(userId).or(UserProfile.defaultDetails());  
        
   Option<UserWithProfile> userWithProfile =  user.map(userValue -> UserWithProfile.of(userValue, details));  
}
```
Now we need to write a final step — transform `userWithProfile` container from `Option<T>` to `Result<T>`:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {  
   Option<User> user = userRepository.findById(userId);  
        
   if (user.isEmpty()) {  
       return Result.failure(Causes.cause("User with ID " \+ userId \+ " not found"));  
   }

   UserProfile details = userProfileRepository.findById(userId).or(UserProfile.defaultDetails());

   Option<UserWithProfile> userWithProfile =  user.map(userValue -> UserWithProfile.of(userValue, details));

   return userWithProfile.toResult(Cause.cause(""));  
}
```
Let’s keep error cause in `return` statement empty for a moment and look again at the code. We can easily spot an issue: we definitely know that `userWithProfile` is always present \- the case, when `user` is not present, is already handled above. How can we fix this?

Note, that we can invoke `user.map()` without checking whether user is present or not. The transformation will be applied only if `user` is present, and ignored if not. This way, we can eliminate `if(user.isEmpty())` check. Let's move the retrieving of `details` and transformation of `User` into `UserWithProfile` inside the lambda passed to `user.map()`:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {  
  Option<UserWithProfile> userWithProfile = userRepository.findById(userId).map(userValue -> {  
       UserProfile details = userProfileRepository.findById(userId).or(UserProfile.defaultDetails());  
       return UserWithProfile.of(userValue, details);  
   });  
        
   return userWithProfile.toResult(Cause.cause(""));  
}
```
The last line needs to be changed now, since `userWithProfile` can be missing. The error will be the same as in the previous version, since `userWithProfile` might be missing only if the value returned by `userRepository.findById(userId)` is missing:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {  
   Option<UserWithProfile> userWithProfile = userRepository.findById(userId).map(userValue -> {  
       UserProfile details = userProfileRepository.findById(userId).or(UserProfile.defaultDetails());  
       return UserWithProfile.of(userValue, details);  
   });  
        
   return userWithProfile.toResult(Causes.cause("User with ID " \+ userId \+ " not found"));  
}
```
Finally, we can inline `details` and `userWithProfile` as they are used only once and immediately after creation:
```java
public Result<UserWithProfile> getUserWithProfile(User.Id userId) {  
   return userRepository.findById(userId)  
                        .map(userValue -> UserWithProfile.of(userValue, userProfileRepository.findById(userId)  
                                                                                             .or(UserProfile.defaultDetails())))  
                        .toResult(Causes.cause("User with ID " \+ userId \+ " not found"));  
}
```
Note how indentation helps to groupId code into logically linked parts.

Let’s analyze the resulting code.

* Code is more concise and written for `happy day scenario`, no explicit error or `null` checks, no distraction from business logic
* There is no simple way to skip or avoid error or `null` checks, writing correct and reliable code is straightforward and natural.

Less obvious observations:

* All types are automatically derived. This simplifies refactoring and removes unnecessary clutter. If necessary, types can still be added.
* If at some point repositories will start returning `Result<T>` instead of `Option<T>`, the code will remain unchanged, except the last transformation (`toResult`) will be removed.
* Aside the replacing of ternary operator with the`Option.or()` method, the resulting code looks a lot like if we would move code from original `return` statement inside lambda passed to `map()` method.

The last observation is very useful to start conveniently writing (reading usually is not an issue) PFJ-style code. It can be rewritten into the following empirical rule: *look for value on the right side*. Just compare:
```java
User user = userRepository.findById(userId);    // <-- value is on the left side of the expression
```
and
```java
return userRepository.findById(userId)  
                     .map(user -> ...); // <-- value is on the right side of the expression
```
This useful observation helps with the transition from legacy imperative code style to PFJ.

#### **Interfacing With Legacy Code**

Needless to say, that existing code does not follow PFJ approaches. It throws exceptions, returns `null` and so on and so forth. Sometimes it is possible to rework this code to make it PFJ-compatible, but quite often this not the case. This is especially true for external libraries and frameworks.

#### **Calling Legacy Code**

There are two major issues with legacy code invocation. Each of them is related to violation of corresponding PFJ rule:

#### **Handling Business Exceptions**

The `Result<T>` contains a helper method named `lift()` which covers most use cases. Method signature looks so:
```java
static <R> Result<R> lift(Fn1<? extends Cause, ? super Throwable> exceptionMapper, ThrowingSupplier<R> supplier)
```
The first parameter is the function which transforms an exception into the instance of `Cause` (which, in turn, is used to create `Result<T>` instances in failure cases).

The second parameter is the lambda, which wraps the call to actual code which needs to be made PFJ-compatible.

The simplest possible function, which transforms the exception into an instance of`Cause` is provided in the`Causes` utility class: `fromThrowable()`. Together with`Result.lift()` they can be used as follows:
```java
public static Result<URI> createURI(String uri) {  
   return Result.lift(Causes::fromThrowable, () -> URI.create(uri));  
}
```
#### **Handling `null` Value Returns**

This case is rather straightforward — if the API can return `null`, just wrap it into`Option<T>` using the`Option.option()` method.

#### **Providing Legacy API**

Sometimes it is necessary to allow legacy code call code written in PFJ style. In particular, this often happens when some smaller subsystem is converted to PFJ style, but the rest of the system remains written in the old style and the API needs to be preserved.

The most convenient way to do this is to split the implementation into two parts — PFJ style API and an adapter, which only adapts new API to old API. Here could be a very useful simple helper method like one shown below:
```java
public static <T> T unwrap(Result<T> value) {  
   return value.fold(  
       cause -> { throw new IllegalStateException(cause.message()); },  
       content -> content  
   );  
}
```
There is no ready to use helper method provided in `Result<T>` for the following reasons:

* There could be different use cases and different types of exceptions (checked and unchecked) can be thrown.
* Transformation of the `Cause` into different specific exceptions heavily depends on the particular use case.

#### **Managing Variable Scopes**

This section will be dedicated to various practical cases which appear while writing PFJ-style code.

Examples below assume use of `Result<T>`, but this is largely irrelevant, as all considerations are applicable to `Option<T>` as well. Also, examples assume that functions invoked in the examples, are converted to return `Result<T>` instead of throwing exceptions.

#### **Nested Scopes**

The functional style code intensively uses lambdas to perform computations and transformations of the values inside `Option<T>` and `Result<T>` containers. Each lambda implicitly creates scope for their parameters \- they are accessible inside the lambda body, but not accessible outside it. This is a useful property in general, but for traditional imperative code it is rather unusual and might feel inconvenient at first. Fortunately, there is a simple technique to overcome perceived inconvenience.

Let’s take a look at the following imperative code:
```java
var value1 = function1(...);                    // function1() may throw exception  
var value2 = function2(value1, ...);            // function2() may throw exception  
var value3 = function3(value1, value2, ...);    // function3() may throw exception
```
Variable `value1` should be accessible for invocation of `function2()` and `function3()`. This does mean that the following straightforward transformation to PFJ style will not work:
```java
function1(...)  
      .flatMap(value1 -> function2(value1, ...))  
      .flatMap(value2 -> function3(value1, value2, ...)); // <-- ERROR, value1 is not accessible\!
```
To keep value accessible we need to use *nested scope*, i.e., nest calls as follows:
```java
function1(...)  
      .flatMap(value1 -> function2(value1, ...)  
          .flatMap(value2 -> function3(value1, value2, ...)));
```
The second call to `flatMap()` is done for value returned by `function2` rather to value returned by the first `flatMap()`. This way we keep `value1` within the scope and make it accessible for `function3`.

Although it is possible to make arbitrarily deep nested scopes, usually more than a couple of nested scopes are harder to read and follow. In this case, it is highly recommended to extract deeper scopes into dedicated function.

#### **Parallel Scopes**

Another frequently observed case is the need to calculate/retrieve several independent values and then make a call or build an object. Let’s take a look at the example below:
```java
var value1 = function1(...);    // function1() may throw exception  
var value2 = function2(...);    // function2() may throw exception  
var value3 = function3(...);    // function3() may throw exception

return new MyObject(value1, value2, value3);
```
At first look, transformation to PFJ style can be done exactly as for nested scopes. The visibility of each value will be the same as for imperative code. Unfortunately, this will make scopes deeply nested, especially if many values need to be obtained.

For such cases, `Option<T>` and `Result<T>` provide a set of `all()` methods. These methods perform "parallel" computation of all values and return a dedicated version of `MapperX<...>` interface. This interface has only three methods \- `id()`, `map()` and `flatMap()`. The `map()` and `flatMap()` methods work exactly like the corresponding methods in `Option<T>` and `Result<T>`, except they accept lambdas with different number of parameters. Let's take a look at how it works in practice and convert the imperative code above into PFJ style:
```java
return Result.all(  
         function1(...),  
         function2(...),  
         function3(...)  
       ).map(MyObject::new);
```
Besides being compact and *flat*, this approach has a few more advantages. First, it explicitly expresses intent — calculate all values before use. Imperative code does this sequentially, hiding original intent. Second advantage — this code explicitly shows that the calculation of each value is independent. This preserves useful knowledge and reduces the context necessary to understand and reason about each function invocation.

#### **Alternative Scopes**

A less frequent, but still, important case is when we need to retrieve value, but if it is not available, then we use an alternative source of the value. Cases when more than one alternative is available are even less frequent, but even more painful when error handling is involved.

Let’s take a look at the following imperative code:
```java
MyType value;

try {  
   value = function1(...);  
} catch (MyException e1) {  
   try {  
       value = function2(...);     
   } catch(MyException e2) {  
       try {  
           value = function3(...);  
       } catch(MyException e3) {  
           ... // repeat as many times as there are alternatives  
       }  
   }  
}
```
The code is somewhat contrived because nested cases usually hidden inside other methods. Nevertheless, overall logic is far from simple, mostly because beside choosing the value, we also need to handle errors. Error handling clutters the code and makes initial intent — choose first available alternative — buried inside error handling.

Transformation into the PFJ style makes intent crystal clear:
```java
var value = Result.any(  
       function1(...),  
       function2(...),  
       function3(...));
```
Unfortunately, here is one important difference: the original imperative code calculates second and subsequent alternatives only when necessary. In some cases, this is not an issue, but often this is highly undesirable. Fortunately, there is a lazy version of the `Result.any()`. Using it, we can rewrite code as follows:
```java
var value = Result.any(  
       function1(...),  
       () -> function2(...),  
       () -> function3(...));
```
Now, converted code behaves exactly like its imperative counterpart.

#### **Brief Technical Overview of `Option<T>` and `Result<T>`**

These two containers are [monads](https://dev.to/siy/beautiful-world-of-mondas-2cd6) in the Functional Programming terms.

`Option<T>` is a rather straightforward implementation of `Option/Optional/Maybe` monad.

`Result<T>` is an intentionally simplified and specialized version of the `Either<L,R>`: left type is fixed and should implement the`Cause` interface. Specialization makes API very similar to `Option<T>` and eliminates a lot of unnecessary typing at the price of loss of universality and generality.

This particular implementation is focused on two things:

* Interoperability between each other and existing JDK classes like `Optional<T>` and `Stream<T>`
* API designed to make expression of intent clear

The last statement worth more in-depth explanation.

Each container has a few *core* methods:

* factory method(s)
* `map()` transformation method, which transforms value but does not change *special state*: present `Option<T>` remains present, success `Result<T>` remains success.
* `flatMap()` transformation method, which, beside transformation, may also change *special state*: convert present `Option<T>` into empty or success `Result<T>` into failure.
* `fold()` method, which handles both cases (present/empty for `Option<T>` and success/failure for `Result<T>`) at once.

Apart from *core* methods, there are a bunch of *helper* methods, which are useful in frequently observed use cases. Among these methods, there is a groupId of methods which are explicitly designed to produce *side effects*.

`Option<T>` has the following methods for *side effects*:

```java
Option<T> whenPresent(Consumer\<? super T> consumer);  
Option<T> whenEmpty(Runnable action);  
Option<T> apply(Runnable emptyValConsumer, Consumer<? super T> nonEmptyValConsumer);
```
`Result<T>` has the following methods for *side effects*:
```java
Result\<T> onSuccess(Consumer\<T> consumer);  
Result\<T> onSuccessDo(Runnable action);  
Result\<T> onFailure(Consumer\<? super Cause> consumer);  
Result\<T> onFailureDo(Runnable action);  
Result\<T> apply(Consumer\<? super Cause> failureConsumer, Consumer\<? super T\> successConsumer);
```
These methods provide hints to the reader that code deals with side effects rather than transformations.

#### **Other Useful Tools**

Besides `Option<T>` and `Result<T>`, PFJ employs some other general purpose classes. Below, each of them is described in more details.

#### **Functions**

JDK provided many useful functional interfaces. Unfortunately, functional interfaces for general purpose functions are limited only to two versions: single parameter `Function<T, R>` and two parameters `BiFunction<T, U, R>`.

Obviously, this is not enough in many practical cases. Also, for some reason, type parameters for these functions are reverse to how functions in Java are declared: result type is listed last, while in function declaration it is defined first.

PFJ uses a consistent set of functional interfaces for functions with 1 to 9 parameters. For brevity, they are called `Fn1`...`Fn9`. So far, there were no use cases for functions with more parameters (and usually this is a code smell). But if this is necessary, the list could be extended further.

#### **Tuples**

Tuples are a special container which can be used to store several values of different types in a single variable. Unlike classes or records, values stored inside have no names. This makes them an indispensable tool for capturing an arbitrary set of values while preserving types. A great example of this use case is the implementation of `Result.all()` and `Option.all()` sets of methods.

In some sense, tuples could be considered a *frozen set of parameters* prepared for function invocation. From this perspective, the decision to make tuple internal values accessible only via the`map()` method sounds reasonable. Nevertheless, tuple with 2 parameters has additional accessors which make possible use of `Tuple2<T1,T2>` as a replacement for various `Pair<T1,T2>` implementations.

PFJ uses a consistent set of tuple implementations with 0 to 9 values. Tuples with 0 and 1 value are provided for consistency.

#### **Conclusion**

Pragmatic Functional Java is a modern, very concise yet readable Java coding style based on Functional Programming concepts. It provides a number of benefits compared to the traditional idiomatic Java coding style:

* PFJ involves a Java compiler to help write reliable code:
* Code which compiles usually works
* Many errors shifted from run-time to compile time
* Some classes of errors, like `NullPointerException` or unhandled exceptions, are virtually eliminated
* PFJ significantly reduces the amount of boilerplate code related to error propagation and handling, as well as `null` checks
* PFJ focuses on clear expression of intent and reducing mental overhead

