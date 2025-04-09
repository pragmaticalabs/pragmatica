### **Function's Anatomy And Beyond**

*Less Art, More Engineering*

Writing clean, understandable, easy to support and maintain code is hard and requires many years of experience. At least we're used to thinking this way. What if there is a way to write such a code consciously and without spending years and years developing these skills?

#### **Functions, Functions Everywhere…**

If we look into modern code, we'll see methods and functions. A lot of them. They are the bread and butter of modern software, the basic building block. The quality of our functions almost completely defines the quality of our software. The problem is that nobody tells us how to write functions and methods. Of course, we can see tons and tons of articles and books, which tell us "do that", "don't do this" while writing functions. But how exactly we should do that and don't do this? Why we should or should not do this and that? There were no answers to those questions. (*Note that the discussion below uses the terms "function" and "method" interchangeably because for the discussion, the difference between methods and functions is irrelevant*).

#### **Less Art, More Engineering**

Writing code consciously means that we clearly understand how to write it and, more important, why it should be written in a specific way. Obviously, doing something consciously is possible only if we clearly understand internals. With our current coding practices, functions considered a black box, an atomic, indivisible element and the question "what inside the function" is carefully ignored. Let's break this tradition and define that function has specific structure. Quite interesting is that the idea of defining the function structure is not new, it just was not applied to regular code. Instead, writing tests using the “*Given/When/Then”* template is quite common.

#### **Standard Function Structure**

Before providing a more formal definition, I'd like to walk through quite typical *Traditional Imperative Java* code shown below:
```java
public Comment.Id addComment(User.Id userId, Publication.Id publicationId, String commentText) {
    validateAddCommentParameters(userId, publicationId, commentText);

    return commentService.addComment(makeComment(userId, publicationId, commentText));
}

private static Comment makeComment(User.Id userId, Publication.Id publicationId, String commentText) {
    return Comment.newComment(userId, publicationId, commentText);
}

private void validateAddCommentParameters(User.Id userId, Publication.Id publicationId, String commentText) {
    if (userId == null || userService.find(userId) == null) {
        throw new UnknownUserException();
    }

    if (publicationId == null || publicationService.find(publicationId) == null) {
        throw new UnknownPublicationException();
    }

    if (commentText == null) {
        throw new InvalidComment();
    }
}
```
The part of the function between lines 2 and 12 performs routine parameter checks typical for the methods/functions which are dealing with raw/unchecked input. Then, the part of the function at line 14 prepares intermediate data. Finally, the part of the function at line 16 performs the essential actions, i.e. do things which are declared by the method/function name. There is another, less obvious, but no less essential part spread across the whole function body: lines 3, 7, 11 and 18, which return error or actual calculated value.

Let's call these parts "phases" and give them names according to ***what they implement inside this function*** (this is a crucial moment, I'll return to it shortly). In total, we have 3+ phases:

* The first phase is ***Validation*** — it is responsible for checking function arguments. It also defines function contract (in math, we would say that it defines function domain).
* The second phase is ***Consolidation*** — it is responsible for preparing necessary intermediate data, i.e. creating new objects, calculating or retrieving necessary data from external sources, etc., etc. This phase uses validated function parameters. For convenience, let's call prepared/retrieved/calculated data and validated function parameters ***Data Dependencies***.
* The third phase is ***Action*** — it is responsible for performing things for which the function was created in the first place.
* The last (3+) phase is ***Reaction*** — its purpose is to adapt value(s) or knowledge which exists inside the function to the contract. This phase usually is spread across the function body and usually has two forms — for successful response and for error reporting. For this reason, I'm somewhat reluctant to call it a full-fledged phase, hence the "+" in the number of phases above.

With these names in mind, we are almost ready to write a more formal definition of the function structure. The last necessary element is the understanding that not every function contains all phases. So, *Function Structure* consists of:

* Zero or one ***Validation*** phase, followed by
* Zero or one ***Consolidation*** phase, followed by
* Zero or one ***Action*** phase
* Zero or more ***Reaction*** phases intermixed with phases mentioned above

Finally, let's return to the note above:

The responsibilities of ***Validation*** and ***Consolidation*** are defined relatively to ***Action*** phase, i.e. we have the function named “*addComment()*”, but code in ***Validation*** and ***Consolidation*** does not add any comments. Instead, it validates parameters and collects data dependencies. If we move code from **Validation** into the dedicated function named (for example) “*validateAddCommentParameters()*”, then the same code will become the **Action** because it performs the actions for which the function was created. The same will happen if we move code from the ***Consolidation*** phase to a dedicated method with an appropriate name.

#### **Analyzing Function Structure**

One immediate result of splitting function into phases is that now it is much more transparent for analysis and code reviews. Each phase has a clearly defined purpose, phases go in defined order. Even just writing/refactoring code with the provided above structure in mind, makes code better structured and easier to understand.

Interesting observation: since each phase has a dedicated responsibility, then function, which has ***Validation*** and/or ***Consolidation*** phases, breaks the single responsibility principle\! Interesting here not the fact that we've discovered a code smell. Most seasoned Java developers would tell that function is somewhat long. But most of them would not be able to answer what exactly wrong with the code (me too, BTW). By introducing structure, we've made the issue easy to spot even for a junior developer.

So, if the presence of these phases is an issue, then how can we solve it? Now, let's remember that each phase is relative to the ***Action*** phase. Hence, by extracting ***Validation*** and ***Consolidation*** into dedicated functions, we can avoid mixing different responsibilities inside one function:

Notice that once ***Validation*** and ***Consolidation*** become dedicated methods, they turned into regular steps of ***Action*** phase. This is the consequence of the relativity of the definition of phase responsibility.

The refactoring is quite straightforward, but it cardinally changes properties of the code:

* All three functions now consist of ***Action*** phase only (+ ***Reaction***, of course)
* Each function is focused on its own task, no more distraction from the main function purpose
* Each function step by step describes what it does. This simplifies understanding code, its further modification, support and maintenance

#### **Observing Abstraction Layering**

As mentioned [here](https://medium.com/@sergiy-yevtushenko/the-context-introduction-e7768cebdfa3#:~:text=It%20should%20be%20noted%20that%20strict%20layering%20of%20abstractions%20is%20essential%20to%20keep%20context%20comprehensible.%20Abstractions%20leaking%20to%20upper%20levels%20may%20result%20in%20uncontrolled%20growth%20of%20complexity%20and%20make%20context%20an%20incomprehensible%20mess.), strict layering of abstraction is essential. Hence, it is worth applying this requirement to the code as well. Although this is a "requirement", but in fact this is a convenient tool which enables more in-depth understanding of our code and finding design issues.

Applying this requirement to the ***Consolidation*** stage reveals an interesting property: each data dependency is independent of each other. If this is not the case, then most likely we have lower level abstraction details (dependencies) leaking to the upper level. For example:

It's quite obvious that the internals of the comment storage are leaking to the upper level here.

But independence of data dependencies useful not only for design issues detection. It allows natural, effortless parallelism if code is written in functional style (we'll take a look into this property below).

Another typical case of design issue manifests itself as "continuous Consolidation":

Basically, it's not so much different from the issue above, but usually, it is observed at the edge between ***Consolidation*** and ***Action***. This issue makes it difficult to draw a boundary between phases and exposes a hidden design issue — mixing different layers of abstraction.

#### **Writing New Code**

Although I've started from the existing code and then refactored it, function structuring paves the way for convenient writing of the new code as well. Again, nothing radically new, just a "divide and conquer" top down strategy:

* Write each function as a sequence of steps
* Split functionality as much as necessary until you'll be able to implement it with a call to an existing function/method or implement using a language constructs within a single level of nesting.

Of course, this is not a strict rule, [there are always different cases and different requirements](https://medium.com/@sergiy-yevtushenko/the-context-introduction-e7768cebdfa3#:~:text=Once%20we%20start%20thinking%20about%20project%20code%20with%20The%20Context%20in%20mind%2C%20we%20quickly%20discover%20that%20relevant%20decision%20stored%20in%20The%20Context%20control%20code%20properties%3A).

#### **Switch To Functional Code**

The code above is a typical imperative code, with all issues specific to such code, including the main one — [loss of context](https://medium.com/codex/we-should-write-java-code-differently-c32212152af1#:~:text=is%20getting%20lost%3F-,Context%20Eaters,-Context%20Eaters%20are). The code above could be written in functional style, which is much better at the preserving context. A direct rewrite of the example above (using the core part of [Pragmatica library](https://github.com/siy/pragmatica)) results in the following code:

Perhaps not ideal, although the lack of typical null-checking noise makes code much more concise. Obviously, direct rewrite didn't change the structure of the function, so it suffers from the same issue as the imperative version — mixed phases (and responsibilities). Simple refactoring addresses this issue:

The refactored version remains concise enough, but now it's much cleaner.

Few important observations of functional version:

* It preserves much more context — it is clear, from the method signature, that it accepts potentially missing values and may return error
* There is basically no way to accidentally omit checking input, the resulting code just does not compile
* The functional version explicitly relies on the fact of independence of data dependencies

The last point is essential because it exposes inherent parallelism in the code, i.e. parts which can be naturally done in parallel. With minimal changes, the functional version can be made asynchronous:

It is worth emphasizing that it does not just perform processing asynchronously, but performs two steps of validation in parallel. And this transformation required very little effort and preserved code clarity and maintainability.

#### **Conclusion**

Don't take the considerations above as a scripture. My goal is to show how powerful is the introduction of the structure into the function. You can introduce your own structure and rules which will better fit your projects and your requirements. It's hard to underestimate the value of writing code consciously, with a clear understanding, how to write it and, more importantly, why. Function structuring enables us to achieve this.

Although the example code above uses Java, function structuring is applicable to the majority of languages which enable users to write functions and/or methods.
